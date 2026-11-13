from r2_storage import upload_file, download_file, delete_file, r2_enabled
from fastapi import FastAPI, HTTPException, Header, Request, UploadFile, File, Form
from fastapi.responses import FileResponse, HTMLResponse, StreamingResponse, JSONResponse, Response, RedirectResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field
from pathlib import Path
import os, sqlite3, secrets, hashlib, hmac, time, uuid, re, mimetypes, subprocess, threading, base64
from urllib.parse import urlsplit
from db_persistence import DurableConnection, restore

DATA = Path(os.getenv('FIELDLINK_DATA', './data')).resolve()
FILES = DATA / 'files'; TMP = DATA / 'tmp'; THUMBS = DATA / 'thumbs'; DB = DATA / 'fieldlink.sqlite3'
LIMIT_BYTES = 6 * 1024**3
STORAGE_LOCK = threading.RLock()
for p in (FILES, TMP, THUMBS): p.mkdir(parents=True, exist_ok=True)
ADMIN = os.getenv('FIELDLINK_ADMIN_KEY', '')
ADMIN_USER = os.getenv('OFFSET_ADMIN_USERNAME', '')
ADMIN_PASSWORD = os.getenv('OFFSET_ADMIN_PASSWORD', '')
DEVICE = os.getenv('FIELDLINK_DEVICE_TOKEN', '')
SECRET = os.getenv('FIELDLINK_SECRET', 'development-only-change-me')
app = FastAPI(title='OFFSET API', version='1.0.0')
app.mount('/static', StaticFiles(directory='static'), name='static')

# Proxy/container metadata is separate from consented DJI Pilot camera observations.
# Never treat proxy ffprobe settings as RAW, lens, ISO, WB or shutter readings.


restore(DB)
def db():
    return DurableConnection(DB)
with db() as c:
    c.executescript('''CREATE TABLE IF NOT EXISTS projects(id TEXT PRIMARY KEY,name TEXT NOT NULL,slug TEXT UNIQUE NOT NULL,pin_hash TEXT NOT NULL,created INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS clips(id TEXT PRIMARY KEY,project_id TEXT NOT NULL,filename TEXT NOT NULL,size INTEGER NOT NULL,created INTEGER NOT NULL,status TEXT NOT NULL,sha256 TEXT,UNIQUE(project_id,filename),FOREIGN KEY(project_id) REFERENCES projects(id));
    CREATE TABLE IF NOT EXISTS uploads(id TEXT PRIMARY KEY,project_id TEXT NOT NULL,filename TEXT NOT NULL,total INTEGER NOT NULL,received INTEGER NOT NULL DEFAULT 0,temp_path TEXT NOT NULL,created INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS completed_uploads(upload_id TEXT PRIMARY KEY,project_id TEXT NOT NULL,clip_id TEXT NOT NULL,size INTEGER NOT NULL,sha256 TEXT NOT NULL);''')

with db() as c:
    if not any(row['name']=='hidden' for row in c.execute('PRAGMA table_info(clips)')):
        c.execute('ALTER TABLE clips ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0')

# Live pre-upload manifests are ephemeral and project-scoped.
with db() as c:
    c.execute("""CREATE TABLE IF NOT EXISTS incoming_clips (
        project_id TEXT NOT NULL, filename TEXT NOT NULL,
        size INTEGER NOT NULL, updated INTEGER NOT NULL,
        PRIMARY KEY(project_id,filename))""")

def ensure_metadata_schema():
    with db() as c:
        c.execute("CREATE TABLE IF NOT EXISTS clip_metadata (clip_id TEXT PRIMARY KEY, data_json TEXT NOT NULL)")
ensure_metadata_schema()

CAMERA_KEYS=('recording_format','lens','fps','iso','white_balance','shutter')
def ensure_camera_metadata_schema():
    with db() as c:
        c.execute("""CREATE TABLE IF NOT EXISTS camera_metadata (
            clip_id TEXT PRIMARY KEY, project_id TEXT NOT NULL,
            data_json TEXT NOT NULL, source TEXT NOT NULL, observed_at INTEGER NOT NULL)""")
ensure_camera_metadata_schema()

def ensure_project_access_schema():
    with db() as c:
        columns={row['name'] for row in c.execute('PRAGMA table_info(projects)').fetchall()}
        if 'public_review' not in columns:
            c.execute('ALTER TABLE projects ADD COLUMN public_review INTEGER NOT NULL DEFAULT 0')
        if 'allow_downloads' not in columns:
            c.execute('ALTER TABLE projects ADD COLUMN allow_downloads INTEGER NOT NULL DEFAULT 1')
        if 'access_nonce' not in columns:
            c.execute("ALTER TABLE projects ADD COLUMN access_nonce TEXT NOT NULL DEFAULT ''")
        if 'pin_secret' not in columns:
            c.execute("ALTER TABLE projects ADD COLUMN pin_secret TEXT NOT NULL DEFAULT ''")
        if 'pin_days' not in columns:
            c.execute('ALTER TABLE projects ADD COLUMN pin_days INTEGER NOT NULL DEFAULT 14')
        if 'pin_expires_at' not in columns:
            c.execute('ALTER TABLE projects ADD COLUMN pin_expires_at INTEGER NOT NULL DEFAULT 0')
ensure_project_access_schema()

def ensure_project_archive_schema():
    with db() as c:
        columns={row['name'] for row in c.execute('PRAGMA table_info(projects)').fetchall()}
        if 'archived' not in columns:
            c.execute('ALTER TABLE projects ADD COLUMN archived INTEGER NOT NULL DEFAULT 0')
        if 'archived_at' not in columns:
            c.execute('ALTER TABLE projects ADD COLUMN archived_at INTEGER NOT NULL DEFAULT 0')
ensure_project_archive_schema()

# Preserve last byte-level progress independently from the upload start time.
def ensure_upload_progress_schema():
    with db() as c:
        columns={row['name'] for row in c.execute('PRAGMA table_info(uploads)').fetchall()}
        if 'updated' not in columns:
            c.execute('ALTER TABLE uploads ADD COLUMN updated INTEGER NOT NULL DEFAULT 0')
            c.execute('UPDATE uploads SET updated=created WHERE updated=0')
ensure_upload_progress_schema()

# Editable public website copy; changes are stored in the durable project database.
SITE_DEFAULTS = {
    'admin_title': 'Send rushes.',
    'admin_highlight': 'Keep the story moving.',
    'admin_description': 'Create a project and share a private review page with your production team.',
    'review_title': 'Review from',
    'review_highlight': 'wherever you are.',
    'review_description': 'Private access to production footage, wherever you are.',
}
with db() as connection:
    connection.execute('CREATE TABLE IF NOT EXISTS site_settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)')

class SiteSettingsIn(BaseModel):
    admin_title: str = Field(max_length=120)
    admin_highlight: str = Field(max_length=120)
    admin_description: str = Field(max_length=400)
    review_title: str = Field(max_length=120)
    review_highlight: str = Field(max_length=120)
    review_description: str = Field(max_length=400)

def site_copy():
    with db() as connection:
        rows=connection.execute('SELECT key,value FROM site_settings').fetchall()
    return {**SITE_DEFAULTS, **{r['key']:r['value'] for r in rows if r['key'] in SITE_DEFAULTS}}

@app.get('/api/site/settings')
def public_site_settings():
    return site_copy()

@app.put('/api/admin/settings')
def update_site_settings(body:SiteSettingsIn, request:Request, x_admin_key:str=Header(default='')):
    require_admin(x_admin_key,request)
    values=body.model_dump()
    with db() as connection:
        for key,value in values.items():
            connection.execute('INSERT INTO site_settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value',(key,value.strip()))
    return site_copy()

def probe_metadata(clip_id):
    import json
    path=FILES/clip_id
    try:
        cmd=['ffprobe','-v','error','-show_format','-show_streams','-of','json',str(path)]
        result=subprocess.run(cmd,capture_output=True,text=True,timeout=25,check=True)
        raw=json.loads(result.stdout)
        video=next((x for x in raw.get('streams',[]) if x.get('codec_type')=='video'),{})
        fmt=raw.get('format',{})
        tags={str(k).lower():v for k,v in {**fmt.get('tags',{}),**video.get('tags',{})}.items()}
        def tag(*keys):
            for k in keys:
                if k.lower() in tags:return tags[k.lower()]
            return None
        out={'codec':video.get('codec_name'),'width':video.get('width'),'height':video.get('height'),
             'frame_rate':video.get('avg_frame_rate'),'duration_seconds':float(fmt['duration']) if fmt.get('duration') else None,
             'creation_time':tag('creation_time'),'camera_model':tag('com.apple.quicktime.model','model'),
             'lens':tag('lens_model','com.apple.quicktime.lens.model'),
             'iso':tag('iso','iso_speed'),'white_balance':tag('white_balance'),
             'aperture':tag('f_number','aperture'),'focal_length':tag('focal_length'),
             'shutter':tag('exposure_time','shutter_speed')}
        # DJI/X9 proprietary metadata may not be exposed by ffprobe; do not invent values.
        with db() as c: c.execute('INSERT OR REPLACE INTO clip_metadata VALUES(?,?)',(clip_id,json.dumps(out)))
        return out
    except Exception: return {}

LOGIN_ATTEMPTS = {}
LOGIN_LOCK = threading.Lock()

def check_login_limit(client_id):
    now=time.monotonic()
    with LOGIN_LOCK:
        # Bound memory use even if many distinct clients attempt login.
        if len(LOGIN_ATTEMPTS)>5000:
            for key in list(LOGIN_ATTEMPTS):
                if LOGIN_ATTEMPTS[key][1]<now:
                    del LOGIN_ATTEMPTS[key]
        count,until=LOGIN_ATTEMPTS.get(client_id,(0,now+900))
        if now>until: count,until=0,now+900
        if count>=8: raise HTTPException(429,'Too many attempts. Try again later.')
        LOGIN_ATTEMPTS[client_id]=(count+1,until)

def same_origin(request):
    origin=request.headers.get('origin','')
    try:
        parsed=urlsplit(origin)
        return parsed.scheme in ('https','http') and parsed.netloc==request.headers.get('host','') and not parsed.username and not parsed.password
    except ValueError:
        return False

def admin_cookie_valid(request):
    if request is None or not ADMIN_USER or not ADMIN_PASSWORD or SECRET == 'development-only-change-me':
        return False
    cookie=request.cookies.get('offset_admin','')
    try:
        username,expires,signature=cookie.split(':')
        payload=f'{username}:{expires}'
        expected=hmac.new(SECRET.encode(),payload.encode(),hashlib.sha256).hexdigest()
        return (hmac.compare_digest(username,ADMIN_USER) and int(expires)>int(time.time())
                and hmac.compare_digest(signature,expected))
    except (ValueError,TypeError):
        return False

def require_admin(key, request=None):
    if admin_cookie_valid(request):
        if request.method not in ('GET','HEAD','OPTIONS'):
            if not same_origin(request):
                raise HTTPException(403,'Invalid request origin')
        return
    if not ADMIN_USER and ADMIN and hmac.compare_digest(key or '', ADMIN):
        return
    raise HTTPException(401,'Admin authentication required')

class AdminLogin(BaseModel):
    username: str
    password: str

@app.post('/api/admin/login')
def admin_login(body:AdminLogin,request:Request,response:Response):
    if not ADMIN_USER or not ADMIN_PASSWORD or SECRET=='development-only-change-me':
        raise HTTPException(503,'Admin login is not configured')
    check_login_limit(request.client.host if request.client else 'unknown')
    valid_user=hmac.compare_digest(body.username,ADMIN_USER)
    valid_password=hmac.compare_digest(body.password,ADMIN_PASSWORD)
    if not (valid_user and valid_password):
        raise HTTPException(401,'Invalid username or password')
    with LOGIN_LOCK:
        LOGIN_ATTEMPTS.pop(request.client.host if request.client else 'unknown',None)
    expiry=int(time.time())+7*24*3600
    payload=f'{ADMIN_USER}:{expiry}'
    sig=hmac.new(SECRET.encode(),payload.encode(),hashlib.sha256).hexdigest()
    response.set_cookie('offset_admin',f'{payload}:{sig}',max_age=7*24*3600,
                        httponly=True,secure=True,samesite='strict',path='/')
    return {'ok':True,'username':ADMIN_USER}

@app.post('/api/admin/logout')
def admin_logout(request:Request,response:Response):
    if not same_origin(request): raise HTTPException(403,'Invalid request origin')
    response.delete_cookie('offset_admin',path='/',secure=True,httponly=True,samesite='strict')
    return {'ok':True}

@app.get('/api/admin/me')
def admin_me(request:Request):
    if not admin_cookie_valid(request): raise HTTPException(401,'Sign in required')
    return {'username':ADMIN_USER}

def require_device(key):
    if not DEVICE or not hmac.compare_digest(key or '', DEVICE): raise HTTPException(401,'Uploader authentication required')
def pin_digest(pin): return hashlib.sha256((pin+SECRET).encode()).hexdigest()
PIN_ALLOWED_DAYS={1,7,14,30,60,90,180,365}
def protect_pin(pin):
    salt=secrets.token_bytes(16)
    mask=hmac.new(SECRET.encode(),salt,hashlib.sha256).digest()
    return base64.urlsafe_b64encode(salt+bytes(a^b for a,b in zip(pin.encode(),mask))).decode()
def reveal_pin(value):
    if not value: return None
    try:
        raw=base64.urlsafe_b64decode(value)
        mask=hmac.new(SECRET.encode(),raw[:16],hashlib.sha256).digest()
        pin=bytes(a^b for a,b in zip(raw[16:],mask)).decode()
        return pin if re.fullmatch(r'[0-9]{6}',pin) else None
    except Exception: return None

def session_token(project_id, expiry):
    # Bind review sessions to the project's current access generation.
    # The empty nonce preserves old sessions on untouched projects.
    with db() as c:
        row=c.execute('SELECT access_nonce,public_review,pin_expires_at FROM projects WHERE id=?',(project_id,)).fetchone()
    if not row: raise HTTPException(404,'Project not found')
    if not row['public_review'] and row['pin_expires_at'] and row['pin_expires_at']<=int(time.time()):
        raise HTTPException(403,'Project PIN expired')
    payload=f'{project_id}:{expiry}'
    signing=payload+row['access_nonce']
    sig=hmac.new(SECRET.encode(),signing.encode(),hashlib.sha256).hexdigest()
    return payload+':'+sig

def check_session(token, project_id):
    try:
        pid, exp, sig=token.split(':')
        if pid!=project_id or int(exp)<=int(time.time()): return False
        with db() as c:
            row=c.execute('SELECT access_nonce,public_review,pin_expires_at FROM projects WHERE id=?',(project_id,)).fetchone()
        if not row: return False
        if not row['public_review'] and row['pin_expires_at'] and row['pin_expires_at']<=int(time.time()): return False
        signing=f'{pid}:{exp}'+row['access_nonce']
        expected=hmac.new(SECRET.encode(),signing.encode(),hashlib.sha256).hexdigest()
        return hmac.compare_digest(sig,expected)
    except Exception: return False

def safe_name(name):
    name=Path(name or 'clip.mp4').name
    name=re.sub(r'[^A-Za-z0-9._ -]','_',name).strip(' .')[:180]
    return name or 'clip.mp4'

class ProjectIn(BaseModel):
    name: str = Field(min_length=1,max_length=120)
    public_review: bool = False
class PinIn(BaseModel):
    pin: str = Field(min_length=4,max_length=12)
class UploadInit(BaseModel):
    project_id: str
    filename: str
    total_size: int = Field(gt=0, le=20_000_000_000)

@app.get('/')
def home(): return FileResponse('static/admin.html')
@app.get('/design-preview',include_in_schema=False)
def controller_design_preview():
    # Isolated browser-only demonstration: never exposes projects, credentials,
    # controller files or production APIs.
    return FileResponse('static/controller-preview.html',media_type='text/html',
        headers={'Cache-Control':'no-store',
                 'X-Robots-Tag':'noindex, nofollow, noarchive',
                 'Content-Security-Policy':"default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'none'; font-src 'self'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"})

@app.get('/r/{slug}')
def review(slug:str): return FileResponse('static/review.html')
# Installable private iPhone web app. Manifest has no project PIN or credentials.
@app.get('/app.webmanifest')
def offset_pwa_manifest(project:str=''):
    valid=bool(re.fullmatch(r'[A-Za-z0-9_-]{1,80}',project))
    start='/r/'+project if valid else '/'
    return JSONResponse({
        'id':start,'name':'OFFSET','short_name':'OFFSET',
        'description':'Private production rushes review',
        'start_url':start,'scope':'/','display':'standalone',
        'background_color':'#0b0d0f','theme_color':'#0b0d0f',
        'icons':[
            {'src':'/pwa/iphone-logo-v2-192.png','sizes':'192x192','type':'image/png','purpose':'any maskable'},
            {'src':'/pwa/iphone-logo-v2-512.png','sizes':'512x512','type':'image/png','purpose':'any maskable'}
        ]
    },media_type='application/manifest+json',headers={'Cache-Control':'public, max-age=3600'})

@app.get('/pwa/iphone-logo-v2-{size}.png')
@app.get('/pwa/logo-{size}.png')
@app.get('/pwa/icon-{size}.png')
def offset_pwa_icon(size:int):
    from pwa_assets import icon_png
    if size not in (180,192,512):
        raise HTTPException(404,'Icon not found')
    return Response(icon_png(size),media_type='image/png',
        headers={'Cache-Control':'public, max-age=604800'})

@app.get('/offset-sw.js')
def offset_pwa_worker():
    return FileResponse('static/offset-sw.js',media_type='application/javascript',
        headers={'Service-Worker-Allowed':'/','Cache-Control':'no-cache'})


# Standalone drone planner: private, persistent planning documents.
def ensure_planner_schema():
    with db() as c:
        c.execute("""CREATE TABLE IF NOT EXISTS drone_plans (
            id TEXT PRIMARY KEY, name TEXT NOT NULL,
            data_json TEXT NOT NULL, updated INTEGER NOT NULL)""")
ensure_planner_schema()

class DronePlanCreate(BaseModel):
    name: str = Field(min_length=1, max_length=120)

class DronePlanSave(BaseModel):
    name: str = Field(min_length=1, max_length=120)
    shots: list = Field(default_factory=list, max_length=200)
    marshals: list = Field(default_factory=list, max_length=200)
    center: dict | None = None
    zoom: int | None = Field(default=None, ge=0, le=22)

def validate_planner_marshals(guards):
    for guard in guards:
        if not isinstance(guard,dict): raise HTTPException(422,'Invalid road marshal')
        point=guard.get('position')
        if (not isinstance(point,list) or len(point)!=2 or
            not all(isinstance(v,(int,float)) and not isinstance(v,bool) for v in point) or
            not -90 <= point[0] <= 90 or not -180 <= point[1] <= 180):
            raise HTTPException(422,'Invalid road marshal coordinates')
        if not isinstance(guard.get('id'),str) or len(guard['id'])>100:
            raise HTTPException(422,'Invalid road marshal ID')
        if not isinstance(guard.get('label',''),str) or len(guard.get('label',''))>100:
            raise HTTPException(422,'Invalid road marshal label')

def planner_shot_marshals(shots, legacy):
    """Assign pre-shot guard data to the first shot without duplicating it."""
    shots=[{**shot, 'marshals':shot.get('marshals',[])} if isinstance(shot,dict) else shot
           for shot in shots]
    if legacy and shots and isinstance(shots[0],dict):
        guards=shots[0]['marshals']
        if isinstance(guards,list):
            ids={guard.get('id') for guard in guards if isinstance(guard,dict)}
            shots[0]['marshals']=guards+[
                guard for guard in legacy
                if not isinstance(guard,dict) or guard.get('id') not in ids]
    return shots

def planner_document(row):
    import json
    try: contents=json.loads(row['data_json'])
    except (ValueError, TypeError): contents={}
    shots=planner_shot_marshals(contents.get('shots',[]),contents.get('marshals',[]))
    return {'id':row['id'],'name':row['name'],
            'shots':shots,
            'marshals':[] if shots else contents.get('marshals',[]),
            'center':contents.get('center'),
            'zoom':contents.get('zoom'),
            'updated':row['updated']}

# PRIVATE PREVIEW ONLY: the Cesium ion token is shared exclusively with
# authenticated OFFSET planner admins. Before any public release, remove this
# shared-token endpoint, rotate the Cesium token and review quota/billing.
# The token is encrypted at rest with a key derived from FIELDLINK_SECRET.
def planner_token_cipher():
    from cryptography.fernet import Fernet
    key=base64.urlsafe_b64encode(hashlib.sha256(
        ('offset-cesium-private-token-v1:'+SECRET).encode()).digest())
    return Fernet(key)

class PlannerCesiumTokenSave(BaseModel):
    token: str = Field(min_length=10,max_length=8192)

@app.get('/api/admin/planner/cesium-token')
def get_planner_cesium_token(request:Request):
    require_admin('',request)
    with db() as c:
        c.execute('CREATE TABLE IF NOT EXISTS planner_private_settings (name TEXT PRIMARY KEY, encrypted_value TEXT NOT NULL)')
        row=c.execute('SELECT encrypted_value FROM planner_private_settings WHERE name=?',('cesium_ion_token',)).fetchone()
    token=''
    if row:
        try: token=planner_token_cipher().decrypt(row['encrypted_value'].encode()).decode()
        except Exception: raise HTTPException(503,'Stored Cesium token cannot be decrypted; enter a new token')
    return JSONResponse({'token':token,'configured':bool(token)},
        headers={'Cache-Control':'no-store','Pragma':'no-cache','X-Robots-Tag':'noindex'})

@app.put('/api/admin/planner/cesium-token')
def save_planner_cesium_token(body:PlannerCesiumTokenSave,request:Request):
    require_admin('',request)
    # Explicitly restrict to the current authenticated admin and same origin.
    if not admin_cookie_valid(request) or not same_origin(request):
        raise HTTPException(403,'Admin login required')
    token=body.token.strip()
    if not token or any(ch.isspace() for ch in token):
        raise HTTPException(422,'Invalid token')
    encoded=planner_token_cipher().encrypt(token.encode()).decode()
    with db() as c:
        c.execute('CREATE TABLE IF NOT EXISTS planner_private_settings (name TEXT PRIMARY KEY, encrypted_value TEXT NOT NULL)')
        c.execute('INSERT OR REPLACE INTO planner_private_settings (name,encrypted_value) VALUES (?,?)',
                  ('cesium_ion_token',encoded))
    return JSONResponse({'configured':True},headers={'Cache-Control':'no-store'})

@app.get('/planner', include_in_schema=False)
def planner_page(request: Request):
    require_admin('', request)
    return FileResponse('static/planner.html', media_type='text/html',
                        headers={'Cache-Control':'no-store','X-Robots-Tag':'noindex, nofollow'})

def selected_pdf_plan(plan, selection):
    if selection is None:return plan
    parts=selection.split(',')
    if len(parts)>200 or any(not re.fullmatch(r'[0-9]{1,3}',part) for part in parts):
        raise HTTPException(400,'Choose at least one valid shot')
    selected={int(part) for part in parts}
    shots=plan.get('shots',[])
    if any(index>=len(shots) for index in selected):
        raise HTTPException(400,'Selected shot no longer exists')
    indices=[i for i in range(len(shots)) if i in selected]
    return {**plan,'shots':[shots[i] for i in indices],
            '_pdf_shot_numbers':[i+1 for i in indices],'_pdf_total_shots':len(shots)}

@app.get('/api/admin/planner/{plan_id}/pdf')
def export_drone_plan_pdf(plan_id: str, request: Request,
                          x_admin_key: str = Header(default=''), shots: str | None = None):
    require_admin(x_admin_key, request)
    with db() as c:
        row=c.execute('SELECT * FROM drone_plans WHERE id=?',(plan_id,)).fetchone()
    if not row:
        raise HTTPException(404, 'Planner project not found')
    plan=selected_pdf_plan(planner_document(row),shots)
    try:
        from planner_pdf import make_planner_pdf
        pdf=make_planner_pdf(plan)
    except Exception:
        import logging
        logging.getLogger('offset.planner_pdf').exception('Planner PDF rendering failed')
        raise HTTPException(500, 'Could not generate PDF')
    safe=re.sub(r'[^A-Za-z0-9_-]+','-',row['name']).strip('-')[:60] or 'drone-plan'
    return Response(pdf, media_type='application/pdf',
                    headers={'Content-Disposition': f'inline; filename="{safe}.pdf"',
                             'Cache-Control': 'private, no-store',
                             'X-Content-Type-Options': 'nosniff'})


class PlannerElevationRequest(BaseModel):
    points: list[list[float]] = Field(min_length=1, max_length=30)


@app.get('/api/admin/planner/buildings')
def planner_3d_buildings(request: Request, lat: float, lon: float, radius: int = 500,
                         x_admin_key: str = Header(default='')):
    """Same-origin OSM building geometry for the storyboard 3D preview."""
    require_admin(x_admin_key, request)
    try:
        from planner_buildings import buildings_near
        data = buildings_near(lat, lon, radius)
    except ValueError:
        raise HTTPException(422, 'Invalid 3D building location')
    except Exception:
        import logging
        logging.getLogger('offset.planner_3d').warning('OSM buildings unavailable', exc_info=True)
        raise HTTPException(503, 'Building data temporarily unavailable')
    return JSONResponse(data, headers={'Cache-Control':'private, max-age=900'})

@app.post('/api/admin/planner/elevation')
def planner_route_elevation(body: PlannerElevationRequest, request: Request,
                            x_admin_key: str = Header(default='')):
    """Sample 90-metre terrain heights. A reference, not obstacle clearance data."""
    require_admin(x_admin_key, request)
    for p in body.points:
        if (len(p)!=2 or
            not all(isinstance(v,(int,float)) and __import__('math').isfinite(v) for v in p) or
            not -90<=p[0]<=90 or not -180<=p[1]<=180):
            raise HTTPException(422, 'Invalid elevation coordinate')
    from urllib.parse import urlencode
    import json, urllib.request
    params=urlencode({
        'latitude': ','.join(f'{p[0]:.6f}' for p in body.points),
        'longitude': ','.join(f'{p[1]:.6f}' for p in body.points)
    })
    req=urllib.request.Request(
        'https://api.open-meteo.com/v1/elevation?'+params,
        headers={'User-Agent':'OFFSET-Planner/1.0', 'Accept':'application/json'})
    try:
        with urllib.request.urlopen(req,timeout=9) as response:
            data=json.loads(response.read(16000))
        elevations=data.get('elevation')
        if not isinstance(elevations,list) or len(elevations)!=len(body.points):
            raise ValueError('Missing terrain samples')
        values=[float(x) for x in elevations]
        if not all(__import__('math').isfinite(v) for v in values):
            raise ValueError('Invalid terrain samples')
    except Exception:
        import logging
        logging.getLogger('offset.planner').warning('Terrain API unavailable',exc_info=True)
        raise HTTPException(503,'Terrain elevation data unavailable')
    return JSONResponse({'elevation':values,'resolution_m':90,'source':'Open-Meteo / Copernicus DEM'},
                        headers={'Cache-Control':'private, max-age=600'})


@app.get('/api/admin/planner/zones')
def planner_drone_geozones(request: Request, south: float, west: float, north: float, east: float,
                          x_admin_key: str = Header(default='')):
    require_admin(x_admin_key, request)
    if not (-90 <= south < north <= 90 and -180 <= west < east <= 180):
        raise HTTPException(422, 'Invalid geographic bounds')
    if north - south > 8 or east - west > 12:
        raise HTTPException(422, 'Zoom closer to view drone zones')
    try:
        from drone_zones import features_in_bbox
        result = features_in_bbox((south, west, north, east))
    except Exception:
        raise HTTPException(503, 'Official drone zone data temporarily unavailable')
    return JSONResponse(result, headers={'Cache-Control':'private, max-age=300'})

@app.get('/api/admin/planner')
def list_drone_plans(request: Request, x_admin_key: str = Header(default='')):
    require_admin(x_admin_key,request)
    with db() as c:
        rows=c.execute('SELECT * FROM drone_plans ORDER BY updated DESC').fetchall()
    return {'plans':[planner_document(row) for row in rows]}

@app.post('/api/admin/planner')
def create_drone_plan(body: DronePlanCreate, request: Request, x_admin_key: str = Header(default='')):
    require_admin(x_admin_key,request)
    import json
    plan_id=uuid.uuid4().hex
    updated=int(time.time())
    contents={'shots':[], 'marshals':[], 'center':None, 'zoom':None}
    with db() as c:
        c.execute('INSERT INTO drone_plans(id,name,data_json,updated) VALUES(?,?,?,?)',
                  (plan_id,body.name.strip(),json.dumps(contents),updated))
    return {'id':plan_id,'name':body.name.strip(),'shots':[],'marshals':[],'updated':updated}

@app.put('/api/admin/planner/{plan_id}')
def save_drone_plan(plan_id: str, body: DronePlanSave, request: Request, x_admin_key: str = Header(default='')):
    require_admin(x_admin_key,request)
    import json
    # Refuse malformed paths before storing them; this is a storyboard, not an autopilot command.
    for shot in body.shots:
        if not isinstance(shot,dict): raise HTTPException(422,'Invalid shot')
        route=shot.get('route',[])
        if not isinstance(route,list) or len(route)>500: raise HTTPException(422,'Too many waypoints')
        for point in route + ([shot['target']] if shot.get('target') is not None else []):
            if (not isinstance(point,list) or len(point)!=2 or
                not all(isinstance(v,(int,float)) and -180<=v<=180 for v in point) or
                not -90<=point[0]<=90):
                raise HTTPException(422,'Invalid map coordinate')
    for shot in body.shots:
        if 'pdfClosureArea' in shot and not isinstance(shot['pdfClosureArea'],bool):
            raise HTTPException(422,'Invalid PDF exclusion area option')
        boxes=shot.get('textBoxes',[])
        if not isinstance(boxes,list) or len(boxes)>100:
            raise HTTPException(422,'Maximum 100 text boxes per shot')
        for box in boxes:
            if (not isinstance(box,dict) or not isinstance(box.get('id'),str)
                or len(box['id'])>100 or not isinstance(box.get('text'),str)
                or not box['text'].strip() or len(box['text'])>300):
                raise HTTPException(422,'Invalid map text box')
            point=box.get('position')
            if (not isinstance(point,list) or len(point)!=2
                or not all(isinstance(v,(int,float)) and not isinstance(v,bool) for v in point)
                or not -90<=point[0]<=90 or not -180<=point[1]<=180):
                raise HTTPException(422,'Invalid text box coordinate')
    # Keep inline shot references small so the SQLite plan store and PDF stay fast.
    for shot in body.shots:
        image=shot.get('referenceImage','')
        if image and (not isinstance(image,str) or len(image)>250000
                      or not image.startswith('data:image/jpeg;base64,')):
            raise HTTPException(422,'Reference image must be an optimised JPEG under 185 KB')
    # Guard positions belong to their shot. Accept older clients' plan-level guards
    # as first-shot data so saved plans survive the transition without spreading.
    shots=planner_shot_marshals(body.shots,body.marshals)
    guard_groups=[shot['marshals'] for shot in shots]
    if not shots:
        guard_groups.append(body.marshals)
    for guards in guard_groups:
        if not isinstance(guards,list) or len(guards)>200:
            raise HTTPException(422,'Maximum 200 road marshals per shot')
        validate_planner_marshals(guards)
    payload=json.dumps({'shots':shots,'marshals':[] if shots else body.marshals,'center':body.center,'zoom':body.zoom})
    if len(payload)>1_000_000: raise HTTPException(413,'Plan is too large')
    updated=int(time.time())
    with db() as c:
        cursor=c.execute('UPDATE drone_plans SET name=?,data_json=?,updated=? WHERE id=?',
                         (body.name.strip(),payload,updated,plan_id))
        if not cursor.rowcount: raise HTTPException(404,'Plan not found')
    return {'ok':True,'updated':updated}

@app.delete('/api/admin/planner/{plan_id}')
def delete_drone_plan(plan_id: str, request: Request, x_admin_key: str = Header(default='')):
    require_admin(x_admin_key,request)
    with db() as c:
        cursor=c.execute('DELETE FROM drone_plans WHERE id=?',(plan_id,))
        if not cursor.rowcount: raise HTTPException(404,'Plan not found')
    return {'ok':True}

@app.get('/health/drone-zones', include_in_schema=False)
def drone_zones_health():
    # Minimal public source-health check. Never exposes restricted admin data.
    try:
        from drone_zones import get_zones
        snapshot = get_zones()
        return {'available': True, 'source': snapshot.get('origin'),
                'source_date': snapshot.get('source_date'),
                'stale': bool(snapshot.get('stale')),
                'count': len(snapshot['zones'])}
    except Exception:
        return JSONResponse({'available': False,
                             'message': 'Norwegian drone-zone data unavailable'},
                            status_code=503, headers={'Cache-Control': 'no-store'})

@app.get('/health')
def health(): return {'ok':True,'product':'OFFSET','version':'1.0.0'}

@app.post('/api/admin/projects')
def create_project(body:ProjectIn, request:Request, x_admin_key:str=Header(default='')):
    require_admin(x_admin_key,request)
    with db() as c:
        alphabet='ABCDEFGHJKLMNPQRSTUVWXYZ'
        for _ in range(20):
            pid=''.join(secrets.choice(alphabet) for _ in range(5))
            if not c.execute('SELECT 1 FROM projects WHERE id=?',(pid,)).fetchone(): break
        else: raise HTTPException(503,'Could not allocate a unique project ID')
    slug=secrets.token_urlsafe(8).replace('-','').replace('_','')
    pin=f'{secrets.randbelow(1_000_000):06d}'
    expires=int(time.time())+14*86400 if not body.public_review else 0
    with db() as c: c.execute('INSERT INTO projects(id,name,slug,pin_hash,created,public_review,pin_secret,pin_days,pin_expires_at) VALUES(?,?,?,?,?,?,?,?,?)',(pid,body.name,slug,pin_digest(pin),int(time.time()),int(body.public_review),protect_pin(pin) if not body.public_review else '',14,expires))
    return {'id':pid,'name':body.name,'slug':slug,'url_path':f'/r/{slug}','pin':None if body.public_review else pin,'public_review':body.public_review}

@app.get('/api/admin/projects')
def list_projects(request:Request, x_admin_key:str=Header(default='')):
    require_admin(x_admin_key,request)
    with db() as c:
        rows=c.execute('SELECT p.id,p.name,p.slug,p.created,p.public_review,p.allow_downloads,p.archived,p.archived_at,COUNT(cl.id) clips,COALESCE(SUM(CASE WHEN cl.status=\'ready\' THEN cl.size ELSE 0 END),0) storage_bytes,MAX(CASE WHEN cl.status=\'ready\' THEN cl.created ELSE NULL END) last_uploaded_at FROM projects p LEFT JOIN clips cl ON cl.project_id=p.id GROUP BY p.id ORDER BY p.created DESC').fetchall()
    return [dict(r) for r in rows]


class ProjectArchiveIn(BaseModel):
    archived: bool

@app.patch('/api/admin/projects/{project_id}/archive')
def set_project_archive(project_id:str, body:ProjectArchiveIn, request:Request,
                        x_admin_key:str=Header(default='')):
    """Soft archive: preserve clips and review links; pause new clip uploads."""
    require_admin(x_admin_key,request)
    with db() as c:
        project=c.execute('SELECT id,archived FROM projects WHERE id=?',(project_id,)).fetchone()
        if not project: raise HTTPException(404,'Project not found')
        if body.archived and not project['archived']:
            pending=c.execute('SELECT COUNT(*) AS n FROM uploads WHERE project_id=?',(project_id,)).fetchone()['n']
            if pending: raise HTTPException(409,'An upload is active. Archive after it finishes.')
        c.execute('UPDATE projects SET archived=?,archived_at=? WHERE id=?',
                  (int(body.archived),int(time.time()) if body.archived else 0,project_id))
    return {'id':project_id,'archived':body.archived}

class ClipVisibilityIn(BaseModel):
    hidden: bool

@app.get('/api/admin/projects/{project_id}/clips')
def admin_project_clips(project_id:str, request:Request):
    require_admin('',request)
    with db() as c:
        if not c.execute('SELECT id FROM projects WHERE id=?',(project_id,)).fetchone():
            raise HTTPException(404,'Project not found')
        rows=c.execute(
            'SELECT id,filename,size,hidden FROM clips WHERE project_id=? AND status=? ORDER BY filename,id',
            (project_id,'ready')).fetchall()
    return JSONResponse([dict(row) for row in rows],headers={'Cache-Control':'no-store'})

@app.patch('/api/admin/projects/{project_id}/clips/{clip_id}/visibility')
def set_clip_visibility(project_id:str, clip_id:str, body:ClipVisibilityIn, request:Request):
    require_admin('',request)
    with db() as c:
        updated=c.execute(
            'UPDATE clips SET hidden=? WHERE id=? AND project_id=? AND status=?',
            (int(body.hidden),clip_id,project_id,'ready'))
        if not updated.rowcount:
            raise HTTPException(404,'Clip not found')
    return {'id':clip_id,'hidden':body.hidden}

@app.delete('/api/admin/projects/{project_id}/clips/{clip_id}')
def admin_delete_uploaded_clip(project_id:str, clip_id:str, request:Request):
    require_admin('',request)
    return _delete_uploaded_clip(project_id,clip_id)

@app.get('/api/admin/projects/{project_id}/activity')
def admin_project_activity(project_id:str, request:Request, x_admin_key:str=Header(default='')):
    """Project-scoped, server-authoritative recent transfers and actionable warnings."""
    require_admin(x_admin_key,request)
    now=int(time.time())
    with db() as c:
        p=c.execute('SELECT id,archived FROM projects WHERE id=?',(project_id,)).fetchone()
        if not p: raise HTTPException(404,'Project not found')
        clips=c.execute('SELECT id,filename,size,created FROM clips WHERE project_id=? AND status=? ORDER BY created DESC LIMIT 30',
                        (project_id,'ready')).fetchall()
        active=c.execute('SELECT id,filename,total,received,created,updated FROM uploads WHERE project_id=? ORDER BY created DESC LIMIT 30',
                         (project_id,)).fetchall()
    activity=[{'kind':'received','filename':r['filename'],'time':r['created'],'size':r['size']}
              for r in clips]
    warnings=[]
    for r in active:
        last=r['updated'] or r['created']
        delayed=now-last>90
        activity.append({'kind':'stalled' if delayed else 'uploading','filename':r['filename'],
                         'time':last,'size':r['total'],'received':r['received']})
        if delayed:
            warnings.append({'kind':'stalled','filename':r['filename'],
                             'message':'No upload progress in over 90 seconds'})
    activity.sort(key=lambda x:x['time'],reverse=True)
    return JSONResponse({'activity':activity[:40],'warnings':warnings,
        'archived':bool(p['archived'])},headers={'Cache-Control':'no-store'})

@app.get('/api/admin/projects/{project_id}/qr')
def admin_project_qr(project_id:str, request:Request, x_admin_key:str=Header(default='')):
    """QR always points at canonical project URL, honoring current PIN settings."""
    require_admin(x_admin_key,request)
    with db() as c:
        p=c.execute('SELECT slug FROM projects WHERE id=?',(project_id,)).fetchone()
    if not p: raise HTTPException(404,'Project not found')
    import io, qrcode, qrcode.image.svg
    url=str(request.base_url).rstrip('/')+'/r/'+p['slug']
    qr=qrcode.QRCode(version=None,error_correction=qrcode.constants.ERROR_CORRECT_M,
                     box_size=5,border=2)
    qr.add_data(url);qr.make(fit=True)
    output=io.BytesIO()
    qr.make_image(image_factory=qrcode.image.svg.SvgPathImage).save(output)
    return Response(content=output.getvalue(),media_type='image/svg+xml',
                    headers={'Cache-Control':'no-store','X-Content-Type-Options':'nosniff'})

class ProjectUpdateIn(BaseModel):
    name: str = Field(min_length=1,max_length=120)

@app.patch('/api/admin/projects/{project_id}')
def update_project(project_id:str, body:ProjectUpdateIn, request:Request, x_admin_key:str=Header(default='')):
    require_admin(x_admin_key,request)
    name=body.name.strip()
    if not name: raise HTTPException(422,'Project name is required')
    with db() as c:
        existing=c.execute('SELECT id FROM projects WHERE id=?',(project_id,)).fetchone()
        if not existing: raise HTTPException(404,'Project not found')
        c.execute('UPDATE projects SET name=? WHERE id=?',(name,project_id))
    return {'id':project_id,'name':name}

class ProjectReviewSettingsIn(BaseModel):
    allow_downloads: bool
    public_review: bool | None = None
    pin_days: int | None = None
    renew_pin: bool = False

@app.get('/api/admin/projects/{project_id}/review-settings')
def admin_project_review_settings(project_id:str, request:Request, x_admin_key:str=Header(default='')):
    require_admin(x_admin_key,request)
    with db() as c:
        p=c.execute('SELECT public_review,allow_downloads,pin_secret,pin_days,pin_expires_at FROM projects WHERE id=?',(project_id,)).fetchone()
    if not p: raise HTTPException(404,'Project not found')
    return JSONResponse({'public_review':bool(p['public_review']),
        'allow_downloads':bool(p['allow_downloads']),
        'pin':reveal_pin(p['pin_secret']) if not p['public_review'] else None,
        'pin_days':p['pin_days'],'pin_expires_at':p['pin_expires_at'],
        'pin_expired':bool(not p['public_review'] and p['pin_expires_at'] and p['pin_expires_at']<=int(time.time())),
        'legacy_pin':bool(not p['public_review'] and not p['pin_secret'])},
        headers={'Cache-Control':'no-store'})

@app.patch('/api/admin/projects/{project_id}/review-settings')
def update_project_review_settings(project_id:str, body:ProjectReviewSettingsIn,
                                   request:Request, x_admin_key:str=Header(default='')):
    require_admin(x_admin_key,request)
    now=int(time.time())
    with db() as c:
        existing=c.execute('SELECT public_review,pin_days,pin_secret FROM projects WHERE id=?',(project_id,)).fetchone()
        if not existing: raise HTTPException(404,'Project not found')
        days=body.pin_days if body.pin_days is not None else existing['pin_days']
        if days not in PIN_ALLOWED_DAYS: raise HTTPException(422,'Unsupported PIN duration')
        old_public=bool(existing['public_review'])
        new_public=old_public if body.public_review is None else body.public_review
        changed_mode=new_public!=old_public
        changed_duration=days!=existing['pin_days']
        new_pin=None
        if new_public:
            if changed_mode:
                c.execute('UPDATE projects SET public_review=1,allow_downloads=?,pin_secret=?,pin_expires_at=0,access_nonce=? WHERE id=?',
                    (int(body.allow_downloads),'',secrets.token_hex(16),project_id))
            else:
                c.execute('UPDATE projects SET allow_downloads=? WHERE id=?',(int(body.allow_downloads),project_id))
        else:
            rotate=changed_mode or body.renew_pin
            if rotate:
                new_pin=f'{secrets.randbelow(1_000_000):06d}'
                c.execute('UPDATE projects SET public_review=0,allow_downloads=?,pin_secret=?,pin_hash=?,pin_days=?,pin_expires_at=?,access_nonce=? WHERE id=?',
                    (int(body.allow_downloads),protect_pin(new_pin),pin_digest(new_pin),days,now+days*86400,secrets.token_hex(16),project_id))
            elif changed_duration:
                c.execute('UPDATE projects SET allow_downloads=?,pin_days=?,pin_expires_at=?,access_nonce=? WHERE id=?',
                    (int(body.allow_downloads),days,now+days*86400,secrets.token_hex(16),project_id))
            else:
                c.execute('UPDATE projects SET allow_downloads=? WHERE id=?',(int(body.allow_downloads),project_id))
    return {'id':project_id,'allow_downloads':body.allow_downloads,'public_review':new_public,
            'pin':new_pin,'pin_days':days}

@app.get('/api/review/{slug}/settings')
def review_settings(slug:str, authorization:str=Header(default=''), session:str=''):
    token=authorization.removeprefix('Bearer ').strip() or session
    p=review_project(slug,token)
    return {'allow_downloads':bool(p['allow_downloads']),'project_name':p['name']}

@app.delete('/api/admin/projects/{project_id}')
def delete_project(project_id:str,request:Request,confirm_clips:bool=False,x_admin_key:str=Header(default='')):
    require_admin(x_admin_key,request)
    return _delete_project(project_id,confirm_clips)

def _delete_project(project_id:str,confirm_clips:bool=False):
    with db() as conn:
        project=conn.execute('SELECT id,name FROM projects WHERE id=?',(project_id,)).fetchone()
        if not project: raise HTTPException(404,'Project not found')
        clips=conn.execute('SELECT id FROM clips WHERE project_id=?',(project_id,)).fetchall()
        uploads=conn.execute('SELECT id FROM uploads WHERE project_id=?',(project_id,)).fetchall()
        if uploads: raise HTTPException(409,'Project has uploads in progress. Finish or cancel uploads before deleting.')
        if clips and not confirm_clips: raise HTTPException(409,'Confirm deletion of project clips')
        # Remove cloud media before removing the database records; do not claim success on storage failure.
        if r2_enabled():
            try:
                for clip in clips: delete_file(f"clips/{clip['id']}")
            except Exception:
                import logging
                logging.exception('Cloud clip deletion failed for project %s',project_id)
                raise HTTPException(502,'Could not delete cloud media. Project was not deleted; please retry.')
        for clip in clips:
            cid=clip['id']
            (FILES/cid).unlink(missing_ok=True)
            (THUMBS/(cid+'.jpg')).unlink(missing_ok=True)
            conn.execute('DELETE FROM clip_metadata WHERE clip_id=?',(cid,))
            conn.execute('DELETE FROM camera_metadata WHERE clip_id=?',(cid,))
        conn.execute('DELETE FROM clips WHERE project_id=?',(project_id,))
        conn.execute('DELETE FROM incoming_clips WHERE project_id=?',(project_id,))
        conn.execute('DELETE FROM projects WHERE id=?',(project_id,))
    return {'deleted':True,'clips_deleted':len(clips)}

def signed_share_token(project_id, expiry):
    payload=f"share:{project_id}:{expiry}"
    signature=hmac.new(SECRET.encode(),payload.encode(),hashlib.sha256).hexdigest()
    return f"{payload}:{signature}"

@app.delete('/api/device/projects/{project_id}')
def device_delete_project(project_id:str, confirm_clips:bool=False,
                          x_device_token:str=Header(default='')):
    require_device(x_device_token)
    return _delete_project(project_id,confirm_clips)

@app.get('/api/device/app-update')
def device_app_update(x_device_token:str=Header(default='')):
    require_device(x_device_token)
    # Only publish a release after its signed APK has been hosted at a trusted HTTPS URL.
    version_code=int(os.getenv('OFFSET_ANDROID_RELEASE_CODE','0'))
    version_name=os.getenv('OFFSET_ANDROID_RELEASE_VERSION','')
    apk_url=os.getenv('OFFSET_ANDROID_RELEASE_URL','')
    # Public GitHub Releases work without manually updating Render variables.
    # If the repository is private, configure an HTTPS release URL explicitly.
    if not apk_url:
        try:
            import json
            from urllib.request import Request as UrlRequest, urlopen
            req=UrlRequest('https://api.github.com/repos/robiop-alt/Fieldlink/releases/latest',
                           headers={'Accept':'application/vnd.github+json','User-Agent':'OFFSET-updates'})
            with urlopen(req,timeout=5) as response:
                release=json.load(response)
            tag=release.get('tag_name','')
            match=re.fullmatch(r'offset-android-(\d+)',tag)
            if match:
                assets=release.get('assets',[])
                asset=next((a for a in assets if a.get('name')=='OFFSET.apk'),None)
                if asset:
                    version_code=int(match.group(1))
                    version_name=release.get('name',tag)
                    apk_url=asset.get('browser_download_url','')
        except Exception:
            pass
    if not apk_url.startswith('https://'):
        apk_url=''
    return {'version_code':version_code,'version_name':version_name,'download_url':apk_url}

@app.get('/api/device/projects')
def device_list_projects(x_device_token:str=Header(default='')):
    require_device(x_device_token)
    with db() as conn:
        rows=conn.execute(
            'SELECT p.id,p.name,p.created,COUNT(cl.id) AS clips FROM projects p '
            'LEFT JOIN clips cl ON cl.project_id=p.id GROUP BY p.id ORDER BY p.created DESC'
        ).fetchall()
    return [dict(row) for row in rows]

@app.post('/api/device/projects')
def device_create_project(body:ProjectIn, x_device_token:str=Header(default='')):
    # The device token authorizes all projects; keep it secret on trusted controllers.
    require_device(x_device_token)
    alphabet='ABCDEFGHJKLMNPQRSTUVWXYZ'
    with db() as conn:
        for _ in range(20):
            pid=''.join(secrets.choice(alphabet) for _ in range(5))
            if not conn.execute('SELECT 1 FROM projects WHERE id=?',(pid,)).fetchone():
                break
        else:
            raise HTTPException(503,'Could not allocate a unique project ID')
        slug=secrets.token_urlsafe(8).replace('-','').replace('_','')
        pin=f'{secrets.randbelow(1_000_000):06d}'
        conn.execute(
            'INSERT INTO projects(id,name,slug,pin_hash,created,public_review,pin_secret,pin_days,pin_expires_at) VALUES(?,?,?,?,?,?,?,?,?)',
            (pid,body.name,slug,pin_digest(pin),int(time.time()),int(body.public_review),protect_pin(pin) if not body.public_review else '',14,int(time.time())+14*86400 if not body.public_review else 0)
        )
    return {'id':pid,'name':body.name,'slug':slug,'url_path':f'/r/{slug}',
            'pin':None if body.public_review else pin,'public_review':body.public_review}

@app.get('/api/device/projects/{project_id}/uploads')
def device_project_uploads(project_id:str, x_device_token:str=Header(default='')):
    """Authenticated, project-scoped list of completed server uploads."""
    require_device(x_device_token)
    with db() as conn:
        project=conn.execute('SELECT id FROM projects WHERE id=?',(project_id,)).fetchone()
        if not project: raise HTTPException(404,'Project not found')
        rows=conn.execute(
            'SELECT id,filename,size,created FROM clips WHERE project_id=? AND status=? ORDER BY filename ASC LIMIT 1000',
            (project_id,'ready')
        ).fetchall()
    return {'project_id':project_id,'clips':[dict(row) for row in rows]}

@app.delete('/api/device/projects/{project_id}/clips/{clip_id}')
def device_delete_uploaded_clip(project_id:str, clip_id:str, x_device_token:str=Header(default='')):
    """Remove a delivered proxy from review. Never delete source media on the controller."""
    require_device(x_device_token)
    return _delete_uploaded_clip(project_id,clip_id)

def _delete_uploaded_clip(project_id:str, clip_id:str):
    with db() as conn:
        clip=conn.execute(
            'SELECT id,filename FROM clips WHERE id=? AND project_id=? AND status=?',
            (clip_id,project_id,'ready')
        ).fetchone()
        if not clip: raise HTTPException(404,'Uploaded clip not found in project')
        in_progress=conn.execute(
            'SELECT 1 FROM uploads WHERE project_id=? AND filename=?',
            (project_id,clip['filename'])
        ).fetchone()
        if in_progress: raise HTTPException(409,'Clip is being uploaded; stop or finish the upload first')
        if r2_enabled():
            try:
                delete_file(f"thumbnails/{clip_id}.jpg")
                delete_file(f"clips/{clip_id}")
            except Exception:
                import logging
                logging.exception('Unable to delete cloud clip %s',clip_id)
                raise HTTPException(502,'Cloud deletion failed. Clip was not removed from the project.')
        (FILES/clip_id).unlink(missing_ok=True)
        (THUMBS/(clip_id+'.jpg')).unlink(missing_ok=True)
        conn.execute('DELETE FROM clip_metadata WHERE clip_id=?',(clip_id,))
        conn.execute('DELETE FROM camera_metadata WHERE clip_id=?',(clip_id,))
        conn.execute('DELETE FROM completed_uploads WHERE clip_id=? AND project_id=?',(clip_id,project_id))
        conn.execute('DELETE FROM clips WHERE id=? AND project_id=?',(clip_id,project_id))
    return {'deleted':True,'id':clip_id,'project_id':project_id}

@app.get('/api/device/projects/{project_id}/share')
def device_project_share(project_id:str, request:Request, pinless:bool=False, x_device_token:str=Header(default='')):
    """Canonical project QR follows the project's public_review setting.

    The URL never contains an expiring access token. A public project is
    unlocked by the existing /public-unlock endpoint; private projects ask
    for their configured PIN. The legacy pinless query has no effect.
    """
    require_device(x_device_token)
    with db() as c:
        p=c.execute('SELECT id,name,slug,public_review FROM projects WHERE id=?',(project_id,)).fetchone()
    if not p: raise HTTPException(404,'Project not found')
    base=str(request.base_url).rstrip('/')
    url=f"{base}/r/{p['slug']}"
    is_public=bool(p['public_review'])
    return {'name':p['name'],'url':url,'pinless':is_public,'public_review':is_public}

class ShareIn(BaseModel):
    share_token: str

@app.post('/api/review/{slug}/share-unlock')
def share_unlock(slug:str, body:ShareIn):
    with db() as c:
        p=c.execute('SELECT id,public_review,access_nonce FROM projects WHERE slug=?',(slug,)).fetchone()
    if not p: raise HTTPException(403,'Invalid share link')
    # A prior PIN-free share link must never bypass a newly enabled PIN.
    if not p['public_review'] and p['access_nonce']:
        raise HTTPException(403,'Project now requires a PIN')
    try:
        purpose,pid,expiry,signature=body.share_token.split(':')
        payload=f"{purpose}:{pid}:{expiry}"
        expected=hmac.new(SECRET.encode(),payload.encode(),hashlib.sha256).hexdigest()
        valid=(purpose=='share' and pid==p['id'] and int(expiry)>int(time.time())
               and int(expiry)<=int(time.time())+24*3600 and hmac.compare_digest(signature,expected))
    except (ValueError,TypeError):
        valid=False
    if not valid: raise HTTPException(403,'Invalid or expired share link')
    session_expiry=min(int(expiry),int(time.time())+8*3600)
    return {'token':session_token(p['id'],session_expiry),'expires':session_expiry}

@app.post('/api/review/{slug}/public-unlock')
def public_unlock(slug:str):
    with db() as c:
        p=c.execute('SELECT id,public_review FROM projects WHERE slug=?',(slug,)).fetchone()
    if not p or not p['public_review']: raise HTTPException(403,'Project requires a PIN')
    expiry=int(time.time())+8*3600
    return {'token':session_token(p['id'],expiry),'expires':expiry}

@app.post('/api/review/{slug}/unlock')
def unlock(slug:str, body:PinIn):
    with db() as c: p=c.execute('SELECT * FROM projects WHERE slug=?',(slug,)).fetchone()
    if not p or p['public_review'] or not hmac.compare_digest(p['pin_hash'],pin_digest(body.pin)):
        raise HTTPException(403,'Wrong link or PIN')
    if p['pin_expires_at'] and p['pin_expires_at']<=int(time.time()):
        raise HTTPException(403,'Project PIN expired; contact the administrator')
    expiry=int(time.time())+8*3600
    return {'token':session_token(p['id'],expiry),'expires':expiry}

def review_project(slug, token):
    with db() as c: p=c.execute('SELECT * FROM projects WHERE slug=?',(slug,)).fetchone()
    if not p or not check_session(token,p['id']): raise HTTPException(401,'Enter project PIN')
    return p

class IncomingItem(BaseModel):
    filename: str = Field(min_length=1,max_length=240)
    size: int = Field(ge=0,le=20_000_000_000)

class IncomingManifest(BaseModel):
    clips: list[IncomingItem] = Field(max_length=500)

@app.put('/api/device/projects/{project_id}/incoming')
def publish_incoming(project_id:str,body:IncomingManifest,x_device_token:str=Header(default='')):
    """Report only clips approved for this project's upload queue."""
    require_device(x_device_token)
    now=int(time.time())
    with db() as c:
        if not c.execute('SELECT 1 FROM projects WHERE id=?',(project_id,)).fetchone():
            raise HTTPException(404,'Project not found')
        c.execute('DELETE FROM incoming_clips WHERE project_id=?',(project_id,))
        for item in body.clips:
            filename=safe_name(item.filename)
            if not filename.lower().endswith('.mp4'): continue
            c.execute('INSERT OR REPLACE INTO incoming_clips(project_id,filename,size,updated) VALUES(?,?,?,?)',
                (project_id,filename,item.size,now))
    return {'ok':True,'count':len(body.clips)}

def incoming_thumb_path(project_id:str,filename:str):
    key=hashlib.sha256((project_id+'\\0'+filename).encode('utf-8')).hexdigest()
    return THUMBS / ('incoming-'+project_id+'-'+key+'.jpg'), f'incoming-thumbs/{project_id}/{key}.jpg'

@app.post('/api/device/projects/{project_id}/incoming-thumbnail')
def incoming_thumbnail_upload(project_id:str,filename:str=Form(...),
                              image:UploadFile=File(...),x_device_token:str=Header(default='')):
    """DJI-generated JPG preview, not video bytes. Only authorized upload manifests."""
    require_device(x_device_token)
    filename=safe_name(filename)
    with db() as c:
        existing=c.execute('SELECT 1 FROM incoming_clips WHERE project_id=? AND filename=?',
                           (project_id,filename)).fetchone()
        transferring=c.execute('SELECT 1 FROM uploads WHERE project_id=? AND filename=?',
                               (project_id,filename)).fetchone()
    if not existing and not transferring: raise HTTPException(404,'Clip not in upload queue')
    data=image.file.read(2*1024*1024+1)
    if len(data)>2*1024*1024 or len(data)<4 or not data.startswith(b'\xff\xd8\xff'):
        raise HTTPException(415,'Expected JPEG under 2 MB')
    path,key=incoming_thumb_path(project_id,filename)
    path.write_bytes(data)
    if r2_enabled():
        try: upload_file(path,key)
        except Exception:
            import logging
            logging.exception('Unable to persist incoming preview')
            raise HTTPException(503,'Preview storage unavailable')
    return {'ok':True}

@app.get('/api/review/{slug}/incoming-thumbnail')
def incoming_thumbnail_review(slug:str,filename:str,authorization:str=Header(default=''),session:str=''):
    token=authorization.removeprefix('Bearer ').strip() or session
    project=review_project(slug,token)
    filename=safe_name(filename)
    now=int(time.time())
    with db() as c:
        if c.execute(
            'SELECT 1 FROM clips WHERE project_id=? AND filename=? AND status=? AND hidden=1',
            (project['id'],filename,'ready')).fetchone():
            raise HTTPException(404,'Not incoming')
        incoming=c.execute('SELECT 1 FROM incoming_clips WHERE project_id=? AND filename=? AND updated>?',
                           (project['id'],filename,now-150)).fetchone()
        active=c.execute('SELECT 1 FROM uploads WHERE project_id=? AND filename=?',
                         (project['id'],filename)).fetchone()
    if not incoming and not active: raise HTTPException(404,'Not incoming')
    path,key=incoming_thumb_path(project['id'],filename)
    if not path.exists() and r2_enabled():
        try: download_file(key,path)
        except Exception: pass
    if not path.exists(): raise HTTPException(404,'No preview yet')
    return FileResponse(path,media_type='image/jpeg',headers={'Cache-Control':'private, max-age=15'})

@app.get('/api/review/{slug}/incoming')
def review_incoming(slug:str,authorization:str=Header(default='')):
    """Live incoming/queued status; never grants video access before completion."""
    token=authorization.removeprefix('Bearer ').strip()
    project=review_project(slug,token)
    now=int(time.time())
    with db() as c:
        ready={r['filename'] for r in c.execute(
            "SELECT filename FROM clips WHERE project_id=? AND status='ready'",
            (project['id'],)).fetchall()}
        uploads=c.execute(
            'SELECT id,filename,total,received,created,updated FROM uploads WHERE project_id=? ORDER BY created',
            (project['id'],)).fetchall()
        queued=c.execute(
            'SELECT filename,size,updated FROM incoming_clips WHERE project_id=? AND updated>? ORDER BY filename',
            (project['id'],now-150)).fetchall()
    results=[]
    active_names=set()
    for u in uploads:
        if u['filename'] in ready: continue
        active_names.add(u['filename'])
        # A full transfer can still be undergoing durable storage and media probing.
        # A stalled upload is determined by the last confirmed chunk, never its age.
        last_progress=u['updated'] or u['created']
        age=max(0,now-last_progress)
        stage=('processing' if u['received']>=u['total'] else
               'reconnecting' if age>90 else 'uploading' if u['received'] else 'starting')
        results.append({'id':u['id'],'filename':u['filename'],'size':u['total'],
            'received':u['received'],'percent':min(100,int(100*u['received']/max(u['total'],1))),
            'status':stage,'created':u['created'],'updated':last_progress,
            'stalled_seconds':age if stage=='reconnecting' else 0})
    for q in queued:
        if q['filename'] in ready or q['filename'] in active_names: continue
        results.append({'id':'queued:'+q['filename'],'filename':q['filename'],
            'size':q['size'],'received':0,'percent':0,'status':'queued',
            'created':q['updated'],'updated':q['updated']})
    total_bytes=sum(max(0,int(item['size'])) for item in results)
    received_bytes=sum(max(0,min(int(item['received']),int(item['size']))) for item in results)
    counts={stage:sum(1 for item in results if item['status']==stage)
            for stage in ('queued','starting','uploading','reconnecting','processing')}
    return {'clips':results,'updated':now,
            'summary':{'total_clips':len(results),'total_bytes':total_bytes,
                       'received_bytes':received_bytes,
                       'percent':round(100*received_bytes/total_bytes) if total_bytes else 0,
                       'counts':counts}}

class CameraMetadataIn(BaseModel):
    project_id: str
    values: dict[str,str] = Field(default_factory=dict)
    observed_at: int = Field(gt=0)

@app.put('/api/device/clips/{clip_id}/camera-metadata')
def record_camera_metadata(clip_id:str, body:CameraMetadataIn, x_device_token:str=Header(default='')):
    """Authenticated DJI Pilot camera readings only, bound to an actual ready clip."""
    require_device(x_device_token)
    now=int(time.time())
    if abs(now-body.observed_at)>86400:
        raise HTTPException(422,'Camera observation is too old')
    with db() as c:
        clip=c.execute('SELECT id,project_id,created FROM clips WHERE id=? AND status=?',
                       (clip_id,'ready')).fetchone()
    if not clip or clip['project_id']!=body.project_id:
        raise HTTPException(404,'Clip not found in selected project')
    # A reading must originate near recording/transfer, never at an arbitrary later point.
    if body.observed_at>clip['created']+120 or body.observed_at<clip['created']-86400:
        raise HTTPException(422,'Observation outside clip recording window')
    clean={}
    for key,value in body.values.items():
        if key not in CAMERA_KEYS or not isinstance(value,str):
            continue
        value=value.strip()
        if value and len(value)<=32 and all(ch.isprintable() for ch in value):
            clean[key]=value
    if not clean: raise HTTPException(422,'No confirmed camera settings')
    import json
    with db() as c:
        c.execute("""INSERT OR REPLACE INTO camera_metadata
            (clip_id,project_id,data_json,source,observed_at) VALUES(?,?,?,?,?)""",
            (clip_id,body.project_id,json.dumps(clean),'DJI Pilot screen',body.observed_at))
    return {'ok':True,'fields':list(clean)}

@app.get('/api/review/{slug}/clips')
def clips(slug:str, authorization:str=Header(default='')):
    token=authorization.removeprefix('Bearer ').strip(); p=review_project(slug,token)
    with db() as c: rows=c.execute('SELECT id,filename,size,created,status FROM clips WHERE project_id=? AND status="ready" AND hidden=0 ORDER BY created DESC',(p['id'],)).fetchall()
    import json
    out=[]
    with db() as c:
        for row in rows:
            item=dict(row)
            meta=c.execute('SELECT data_json FROM clip_metadata WHERE clip_id=?',(item['id'],)).fetchone()
            item['metadata']=json.loads(meta['data_json']) if meta else {}
            camera=c.execute('SELECT data_json FROM camera_metadata WHERE clip_id=? AND project_id=?',
                             (item['id'],p['id'])).fetchone()
            item['camera_metadata']=json.loads(camera['data_json']) if camera else {}
            out.append(item)
    return JSONResponse(out,headers={'Cache-Control':'private, no-store'})

def ensure_clip_local(clip_id):
    """Restore a missing original from R2 for authenticated review endpoints."""
    path = FILES / clip_id
    if not path.exists() and os.getenv("R2_ENDPOINT"):
        try:
            download_file(f"clips/{clip_id}", path)
        except Exception:
            import logging
            logging.exception("R2 download failed for clip %s", clip_id)
    if not path.exists():
        raise HTTPException(404, "Clip missing")
    return path

@app.get('/api/review/{slug}/clips/{clip_id}/file')
def clip_file(slug:str,clip_id:str,request:Request,authorization:str=Header(default=''),session:str='',download:int=0):
    token=authorization.removeprefix('Bearer ').strip() or session; p=review_project(slug,token)
    with db() as c: clip=c.execute('SELECT * FROM clips WHERE id=? AND project_id=? AND status="ready" AND hidden=0',(clip_id,p['id'])).fetchone()
    if not clip: raise HTTPException(404,'Clip not found')
    if download and not p['allow_downloads']:
        raise HTTPException(403,'Downloads disabled for this project')
    if not download and r2_enabled():
        # Let R2 serve Safari's byte-range requests directly, rather than first
        # downloading the entire video to Render and proxying each range.
        try:
            from r2_storage import presigned_clip_url
            return RedirectResponse(
                presigned_clip_url(clip_id),
                status_code=307,
                headers={'Cache-Control':'private, no-store'}
            )
        except Exception:
            import logging
            logging.exception('R2 playback redirect failed; falling back to local streaming')
    path=ensure_clip_local(clip_id)
    if download:
        return FileResponse(path,media_type='application/octet-stream',filename=clip['filename'],
                            headers={'Cache-Control':'private, no-store'})
    size=path.stat().st_size
    range_header=request.headers.get('range')
    headers={'Accept-Ranges':'bytes','Cache-Control':'private, no-store',
             'Content-Disposition':'inline'}
    if range_header:
        # Safari may request suffix ranges (bytes=-N) to read MP4 metadata
        # from the end of the file. A missing start is NOT byte zero.
        m=re.fullmatch(r'bytes=(\d*)-(\d*)',range_header.strip())
        if not m or (not m.group(1) and not m.group(2)):
            return Response(status_code=416,headers={**headers,'Content-Range':f'bytes */{size}'})
        if m.group(1):
            start=int(m.group(1))
            end=min(int(m.group(2)),size-1) if m.group(2) else size-1
        else:
            suffix=int(m.group(2))
            start=max(0,size-suffix)
            end=size-1
        if start>end or start>=size or size==0:
            return Response(status_code=416,headers={**headers,'Content-Range':f'bytes */{size}'})
        def stream():
            with open(path,'rb') as file:
                file.seek(start)
                remaining=end-start+1
                while remaining:
                    chunk=file.read(min(1024*1024,remaining))
                    if not chunk: break
                    remaining-=len(chunk)
                    yield chunk
        return StreamingResponse(
            stream(),status_code=206,media_type='video/mp4',
            headers={**headers,'Content-Range':f'bytes {start}-{end}/{size}',
                     'Content-Length':str(end-start+1)}
        )
    return FileResponse(path,media_type='video/mp4',headers=headers)

@app.post('/api/upload/init')
def upload_init(body:UploadInit,x_device_token:str=Header(default='')):
    require_device(x_device_token)
    with db() as c: p=c.execute('SELECT id FROM projects WHERE id=?',(body.project_id,)).fetchone()
    if not p: raise HTTPException(404,'Project not found')
    if body.total_size > LIMIT_BYTES: raise HTTPException(413,'Clip exceeds 6 GB limit')
    with db() as c:
        project=c.execute('SELECT archived FROM projects WHERE id=?',(body.project_id,)).fetchone()
    if not project: raise HTTPException(404,'Project not found')
    if project['archived']: raise HTTPException(409,'Project is archived; restore in admin to upload')

    uid=uuid.uuid4().hex; tmp=TMP/uid
    tmp.touch()
    with db() as c: c.execute('INSERT INTO uploads(id,project_id,filename,total,received,temp_path,created,updated) VALUES(?,?,?,?,?,?,?,?)',(uid,body.project_id,safe_name(body.filename),body.total_size,0,str(tmp),int(time.time()),int(time.time())))
    return {'upload_id':uid,'chunk_size':4*1024*1024}

@app.put('/api/upload/{upload_id}/chunk')
def upload_chunk(upload_id:str, chunk:UploadFile=File(...), x_device_token:str=Header(default=''), x_offset:int=Header(default=-1)):
    require_device(x_device_token)
    with db() as c: u=c.execute('SELECT * FROM uploads WHERE id=?',(upload_id,)).fetchone()
    if not u: raise HTTPException(404,'Upload not found')
    if x_offset != u['received']: raise HTTPException(409,f'Offset mismatch; expected {u["received"]}')
    data=chunk.file.read(4*1024*1024+1)
    if len(data)>4*1024*1024: raise HTTPException(413,'Chunk too large')
    if u['received']+len(data)>u['total']: raise HTTPException(400,'Too much data')
    with open(u['temp_path'],'ab') as f: f.write(data)
    with db() as c: c.execute('UPDATE uploads SET received=received+?, updated=? WHERE id=?',(len(data),int(time.time()),upload_id))
    return {'received':u['received']+len(data),'total':u['total']}

@app.post('/api/upload/{upload_id}/complete')
def upload_complete(upload_id:str,x_device_token:str=Header(default=''),x_content_sha256:str=Header(default='')):
    require_device(x_device_token)
    with db() as c:
        done=c.execute('SELECT * FROM completed_uploads WHERE upload_id=?',(upload_id,)).fetchone()
        u=c.execute('SELECT * FROM uploads WHERE id=?',(upload_id,)).fetchone()
    if done:
        if x_content_sha256 and not hmac.compare_digest(x_content_sha256.lower(),done['sha256']):
            raise HTTPException(422,'SHA-256 mismatch')
        return {'clip_id':done['clip_id'],'status':'ready','sha256':done['sha256'],'size':done['size']}
    if not u: raise HTTPException(404,'Upload not found')
    tmp=Path(u['temp_path'])
    if not tmp.exists() or tmp.stat().st_size!=u['total']: raise HTTPException(400,'Upload incomplete')
    # Keep filenames unique within a project while retaining the original name when possible.
    final_name=u['filename']
    with db() as c:
        exists=c.execute('SELECT 1 FROM clips WHERE project_id=? AND filename=?',(u['project_id'],final_name)).fetchone()
        if exists:
            stem=Path(final_name).stem; suffix=Path(final_name).suffix
            final_name=f'{stem}_{int(time.time())}{suffix}'
    clip_id=uuid.uuid4().hex; h=hashlib.sha256()
    with open(tmp,'rb') as source:
        for block in iter(lambda: source.read(1024*1024), b''): h.update(block)
    digest=h.hexdigest()
    if x_content_sha256 and not hmac.compare_digest(x_content_sha256.lower(),digest):
        raise HTTPException(422,'SHA-256 mismatch')
    # Upload the original to durable storage BEFORE acknowledging completion.
    # On failure, keep the temporary upload and DB record so the client can retry.
    if os.getenv("R2_ENDPOINT"):
        try:
            from r2_storage import upload_clip_if_enabled
            if not upload_clip_if_enabled(tmp, clip_id):
                raise RuntimeError("R2 credentials are incomplete")
        except Exception:
            import logging
            logging.exception("R2 upload failed for clip %s", clip_id)
            raise HTTPException(503, "Permanent video storage unavailable; retry completion")
    with STORAGE_LOCK:
        make_room(u['total'])
        tmp.replace(FILES/clip_id)
        with db() as c:
            c.execute('INSERT INTO clips(id,project_id,filename,size,created,status,sha256) VALUES(?,?,?,?,?,?,?)',(clip_id,u['project_id'],final_name,u['total'],int(time.time()),'ready',digest))
            c.execute('INSERT INTO completed_uploads VALUES(?,?,?,?,?)',(upload_id,u['project_id'],clip_id,u['total'],digest))
            c.execute('DELETE FROM uploads WHERE id=?',(upload_id,))
    generate_thumbnail(clip_id)
    probe_metadata(clip_id)
    return {'clip_id':clip_id,'status':'ready','sha256':digest,'size':u['total']}

@app.get('/api/upload/{upload_id}')
def upload_status(upload_id:str,x_device_token:str=Header(default='')):
    require_device(x_device_token)
    with db() as c: u=c.execute('SELECT id,total,received,filename FROM uploads WHERE id=?',(upload_id,)).fetchone()
    if not u:
        with db() as c: done=c.execute('SELECT * FROM completed_uploads WHERE upload_id=?',(upload_id,)).fetchone()
        if done: return {'status':'ready','clip_id':done['clip_id'],'sha256':done['sha256'],'total':done['size'],'received':done['size']}
        raise HTTPException(404,'Upload not found')
    return dict(u)


# OFFSET additions

def make_room(incoming):
    """Evict local copies only after verifying each complete original exists in R2."""
    from r2_storage import r2_enabled, r2_client
    if not r2_enabled():
        raise HTTPException(503, 'Durable object storage is required before cache eviction')
    with db() as connection:
        rows=connection.execute('SELECT id,size FROM clips WHERE status="ready" ORDER BY created ASC,id ASC').fetchall()
    used=sum(p.stat().st_size for p in FILES.iterdir() if p.is_file())
    for row in rows:
        if used+incoming<=LIMIT_BYTES:
            break
        file=FILES/row['id']
        if not file.exists():
            continue
        size=file.stat().st_size
        try:
            remote=r2_client().head_object(Bucket=os.environ.get('R2_BUCKET','offset-rushes'),Key=f"clips/{row['id']}")
            if int(remote['ContentLength'])!=size or size!=row['size']:
                continue
        except Exception:
            # Never remove the only known copy of a clip.
            continue
        file.unlink()
        (THUMBS/(row['id']+'.jpg')).unlink(missing_ok=True)
        used-=size
    if used+incoming>LIMIT_BYTES:
        raise HTTPException(507,'Insufficient verified local cache storage')


def generate_thumbnail(clip_id):
    source=FILES/clip_id; output=THUMBS/(clip_id+'.jpg')
    try:
        # First frame works for short proxy clips; seeking to 2s can exceed their duration.
        subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-y','-i',str(source),'-frames:v','1','-vf','scale=640:-2',str(output)],timeout=40,check=True)
        if r2_enabled():
            upload_file(output, f'thumbnails/{clip_id}.jpg')
    except Exception:
        output.unlink(missing_ok=True)

def get_clip_for_review(slug,clip_id,token):
    p=review_project(slug,token)
    with db() as c:
        clip=c.execute('SELECT * FROM clips WHERE id=? AND project_id=? AND status="ready" AND hidden=0',(clip_id,p['id'])).fetchone()
    if not clip: raise HTTPException(404,'Clip not found')
    return clip

@app.post('/api/device/clips/{clip_id}/thumbnail')
async def device_clip_thumbnail(clip_id:str, image:UploadFile=File(...), x_device_token:str=Header(default='')):
    require_device(x_device_token)
    with db() as conn:
        clip=conn.execute('SELECT id FROM clips WHERE id=? AND status=?',(clip_id,'ready')).fetchone()
    if not clip: raise HTTPException(404,'Clip not found')
    content=await image.read(2*1024*1024+1)
    if len(content)>2*1024*1024 or len(content)<4 or not content.startswith(bytes.fromhex('ffd8ff')):
        raise HTTPException(400,'Expected JPEG thumbnail under 2 MB')
    output=THUMBS/(clip_id+'.jpg')
    output.write_bytes(content)
    if r2_enabled():
        upload_file(output,f'thumbnails/{clip_id}.jpg')
    return {'status':'ready'}

@app.get('/api/review/{slug}/clips/{clip_id}/thumbnail')
def thumbnail(slug:str,clip_id:str,session:str=''):
    get_clip_for_review(slug,clip_id,session)
    path=THUMBS/(clip_id+'.jpg')
    if not path.exists() and r2_enabled():
        try:
            download_file(f'thumbnails/{clip_id}.jpg',path)
        except Exception:
            pass
    if not path.exists(): raise HTTPException(404,'Thumbnail unavailable')
    return FileResponse(path,media_type='image/jpeg',headers={'Cache-Control':'private, no-store'})

@app.get('/api/review/{slug}/clips/{clip_id}/duration')
def duration(slug:str,clip_id:str,session:str=''):
    import json
    get_clip_for_review(slug,clip_id,session)
    with db() as c:
        row=c.execute('SELECT data_json FROM clip_metadata WHERE clip_id=?',(clip_id,)).fetchone()
    return {'seconds':json.loads(row['data_json']).get('duration_seconds') if row else None}


@app.get('/api/review/{slug}/download-selected')
def download_selected(slug:str,ids:str='',session:str=''):
    import zipstream
    p=review_project(slug,session)
    if not p['allow_downloads']:
        raise HTTPException(403,'Downloads disabled for this project')
    wanted=list(dict.fromkeys(x for x in ids.split(',') if re.fullmatch(r'[0-9a-f]{32}',x)))
    if not wanted or len(wanted)>100: raise HTTPException(400,'Select 1-100 clips')
    with db() as c:
        rows=c.execute('SELECT id,filename FROM clips WHERE project_id=? AND status="ready" AND hidden=0',(p['id'],)).fetchall()
    by_id={r['id']:r for r in rows}
    zs=zipstream.ZipStream(compress_type=zipstream.ZIP_STORED)
    used=set()
    for cid in wanted:
        if cid not in by_id: raise HTTPException(404,'Clip unavailable')
        path=ensure_clip_local(cid)
        name=by_id[cid]['filename']
        if name in used: name=cid[:8]+'_'+name
        used.add(name)
        zs.add_path(path,arcname=name)
    return StreamingResponse(zs,media_type='application/zip',headers={'Content-Disposition':'attachment; filename="OFFSET-clips.zip"','Cache-Control':'no-store'})

@app.get('/api/review/{slug}/storage')
def storage(slug:str,session:str=''):
    project=review_project(slug,session)
    # The clip ledger includes cloud-backed media even when its local cache is empty.
    with db() as c:
        totals=c.execute(
            "SELECT COUNT(*) AS clip_count,COALESCE(SUM(size),0) AS used_bytes "
            "FROM clips WHERE project_id=? AND status='ready'",(project['id'],)
        ).fetchone()
    return JSONResponse(dict(totals),headers={'Cache-Control':'no-store'})
