from r2_storage import upload_file, download_file, delete_file, r2_enabled
from fastapi import FastAPI, HTTPException, Header, Request, UploadFile, File
from fastapi.responses import FileResponse, HTMLResponse, StreamingResponse, JSONResponse, Response
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field
from pathlib import Path
import os, sqlite3, secrets, hashlib, hmac, time, uuid, re, mimetypes, subprocess, threading
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

# Metadata is extracted from the received original, never from client-supplied values.


restore(DB)
def db():
    return DurableConnection(DB)
with db() as c:
    c.executescript('''CREATE TABLE IF NOT EXISTS projects(id TEXT PRIMARY KEY,name TEXT NOT NULL,slug TEXT UNIQUE NOT NULL,pin_hash TEXT NOT NULL,created INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS clips(id TEXT PRIMARY KEY,project_id TEXT NOT NULL,filename TEXT NOT NULL,size INTEGER NOT NULL,created INTEGER NOT NULL,status TEXT NOT NULL,sha256 TEXT,UNIQUE(project_id,filename),FOREIGN KEY(project_id) REFERENCES projects(id));
    CREATE TABLE IF NOT EXISTS uploads(id TEXT PRIMARY KEY,project_id TEXT NOT NULL,filename TEXT NOT NULL,total INTEGER NOT NULL,received INTEGER NOT NULL DEFAULT 0,temp_path TEXT NOT NULL,created INTEGER NOT NULL);''')

def ensure_metadata_schema():
    with db() as c:
        c.execute("CREATE TABLE IF NOT EXISTS clip_metadata (clip_id TEXT PRIMARY KEY, data_json TEXT NOT NULL)")
ensure_metadata_schema()

def ensure_project_access_schema():
    with db() as c:
        columns={row['name'] for row in c.execute('PRAGMA table_info(projects)').fetchall()}
        if 'public_review' not in columns:
            c.execute('ALTER TABLE projects ADD COLUMN public_review INTEGER NOT NULL DEFAULT 0')
ensure_project_access_schema()

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
def session_token(project_id, expiry):
    payload=f'{project_id}:{expiry}'; sig=hmac.new(SECRET.encode(),payload.encode(),hashlib.sha256).hexdigest()
    return payload+':'+sig
def check_session(token, project_id):
    try:
        pid, exp, sig=token.split(':'); payload=f'{pid}:{exp}'
        return pid==project_id and int(exp)>int(time.time()) and hmac.compare_digest(sig,hmac.new(SECRET.encode(),payload.encode(),hashlib.sha256).hexdigest())
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
@app.get('/r/{slug}')
def review(slug:str): return FileResponse('static/review.html')
@app.get('/health')
def health(): return {'ok':True,'product':'OFFSET','version':'1.0.0'}

@app.post('/api/admin/projects')
def create_project(body:ProjectIn, request:Request, x_admin_key:str=Header(default='')):
    require_admin(x_admin_key,request)
    with db() as c:
        alphabet='ABCDEFGHJKLMNPQRSTUVWXYZ'
        for _ in range(20):
            pid=''.join(secrets.choice(alphabet) for _ in range(8))
            if not c.execute('SELECT 1 FROM projects WHERE id=?',(pid,)).fetchone(): break
        else: raise HTTPException(503,'Could not allocate a unique project ID')
    slug=secrets.token_urlsafe(8).replace('-','').replace('_','')
    pin=f'{secrets.randbelow(1_000_000):06d}'
    with db() as c: c.execute('INSERT INTO projects(id,name,slug,pin_hash,created,public_review) VALUES(?,?,?,?,?,?)',(pid,body.name,slug,pin_digest(pin),int(time.time()),int(body.public_review)))
    return {'id':pid,'name':body.name,'slug':slug,'url_path':f'/r/{slug}','pin':None if body.public_review else pin,'public_review':body.public_review}

@app.get('/api/admin/projects')
def list_projects(request:Request, x_admin_key:str=Header(default='')):
    require_admin(x_admin_key,request)
    with db() as c:
        rows=c.execute('SELECT p.id,p.name,p.slug,p.created,p.public_review,COUNT(cl.id) clips FROM projects p LEFT JOIN clips cl ON cl.project_id=p.id GROUP BY p.id ORDER BY p.created DESC').fetchall()
    return [dict(r) for r in rows]

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
        conn.execute('DELETE FROM clips WHERE project_id=?',(project_id,))
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
            match=re.fullmatch(r'offset-android-(\\d+)',tag)
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
            pid=''.join(secrets.choice(alphabet) for _ in range(8))
            if not conn.execute('SELECT 1 FROM projects WHERE id=?',(pid,)).fetchone():
                break
        else:
            raise HTTPException(503,'Could not allocate a unique project ID')
        slug=secrets.token_urlsafe(8).replace('-','').replace('_','')
        pin=f'{secrets.randbelow(1_000_000):06d}'
        conn.execute(
            'INSERT INTO projects(id,name,slug,pin_hash,created,public_review) VALUES(?,?,?,?,?,?)',
            (pid,body.name,slug,pin_digest(pin),int(time.time()),int(body.public_review))
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
            'SELECT id,filename,size,created FROM clips WHERE project_id=? AND status=? ORDER BY created DESC LIMIT 100',
            (project_id,'ready')
        ).fetchall()
    return {'project_id':project_id,'clips':[dict(row) for row in rows]}

@app.get('/api/device/projects/{project_id}/share')
def device_project_share(project_id:str, request:Request, pinless:bool=False, x_device_token:str=Header(default='')):
    """Generate a standard PIN link or an expiring bearer link for the device operator."""
    require_device(x_device_token)
    with db() as c:
        p=c.execute('SELECT id,name,slug FROM projects WHERE id=?',(project_id,)).fetchone()
    if not p: raise HTTPException(404,'Project not found')
    base=str(request.base_url).rstrip('/')
    url=f"{base}/r/{p['slug']}"
    if pinless:
        expiry=int(time.time())+24*3600
        share=signed_share_token(p['id'],expiry)
        # Fragment is not sent to the web server or included in HTTP referrers.
        url+=f"#share={share}"
    return {'name':p['name'],'url':url,'pinless':pinless}

class ShareIn(BaseModel):
    share_token: str

@app.post('/api/review/{slug}/share-unlock')
def share_unlock(slug:str, body:ShareIn):
    with db() as c:
        p=c.execute('SELECT id FROM projects WHERE slug=?',(slug,)).fetchone()
    if not p: raise HTTPException(403,'Invalid share link')
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
    if not p or not hmac.compare_digest(p['pin_hash'],pin_digest(body.pin)): raise HTTPException(403,'Wrong link or PIN')
    expiry=int(time.time())+8*3600
    return {'token':session_token(p['id'],expiry),'expires':expiry}

def review_project(slug, token):
    with db() as c: p=c.execute('SELECT * FROM projects WHERE slug=?',(slug,)).fetchone()
    if not p or not check_session(token,p['id']): raise HTTPException(401,'Enter project PIN')
    return p

@app.get('/api/review/{slug}/clips')
def clips(slug:str, authorization:str=Header(default='')):
    token=authorization.removeprefix('Bearer ').strip(); p=review_project(slug,token)
    with db() as c: rows=c.execute('SELECT id,filename,size,created,status FROM clips WHERE project_id=? AND status="ready" ORDER BY created DESC',(p['id'],)).fetchall()
    import json
    out=[]
    with db() as c:
        for row in rows:
            item=dict(row)
            meta=c.execute('SELECT data_json FROM clip_metadata WHERE clip_id=?',(item['id'],)).fetchone()
            item['metadata']=json.loads(meta['data_json']) if meta else {}
            out.append(item)
    return out

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
    with db() as c: clip=c.execute('SELECT * FROM clips WHERE id=? AND project_id=? AND status="ready"',(clip_id,p['id'])).fetchone()
    if not clip: raise HTTPException(404,'Clip not found')
    path=ensure_clip_local(clip_id)
    if download: return FileResponse(path,media_type='application/octet-stream',filename=clip['filename'])
    size=path.stat().st_size; range_header=request.headers.get('range')
    if range_header:
        m=re.match(r'bytes=(\d*)-(\d*)',range_header)
        if not m: raise HTTPException(416,'Invalid range')
        start=int(m.group(1) or 0); end=int(m.group(2) or size-1); end=min(end,size-1)
        if start>end or start>=size: raise HTTPException(416,'Range not satisfiable')
        def stream():
            with open(path,'rb') as f:
                f.seek(start); remaining=end-start+1
                while remaining:
                    chunk=f.read(min(1024*1024,remaining))
                    if not chunk: break
                    remaining-=len(chunk); yield chunk
        return StreamingResponse(stream(),status_code=206,media_type='video/mp4',headers={'Content-Range':f'bytes {start}-{end}/{size}','Accept-Ranges':'bytes','Content-Length':str(end-start+1)})
    return FileResponse(path,media_type='video/mp4',filename=clip['filename'],headers={'Accept-Ranges':'bytes'})

@app.post('/api/upload/init')
def upload_init(body:UploadInit,x_device_token:str=Header(default='')):
    require_device(x_device_token)
    with db() as c: p=c.execute('SELECT id FROM projects WHERE id=?',(body.project_id,)).fetchone()
    if not p: raise HTTPException(404,'Project not found')
    if body.total_size > LIMIT_BYTES: raise HTTPException(413,'Clip exceeds 6 GB limit')
    uid=uuid.uuid4().hex; tmp=TMP/uid
    tmp.touch()
    with db() as c: c.execute('INSERT INTO uploads VALUES(?,?,?,?,?,?,?)',(uid,body.project_id,safe_name(body.filename),body.total_size,0,str(tmp),int(time.time())))
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
    with db() as c: c.execute('UPDATE uploads SET received=received+? WHERE id=?',(len(data),upload_id))
    return {'received':u['received']+len(data),'total':u['total']}

@app.post('/api/upload/{upload_id}/complete')
def upload_complete(upload_id:str,x_device_token:str=Header(default='')):
    require_device(x_device_token)
    with db() as c: u=c.execute('SELECT * FROM uploads WHERE id=?',(upload_id,)).fetchone()
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
            c.execute('INSERT INTO clips VALUES(?,?,?,?,?,?,?)',(clip_id,u['project_id'],final_name,u['total'],int(time.time()),'ready',digest))
            c.execute('DELETE FROM uploads WHERE id=?',(upload_id,))
    generate_thumbnail(clip_id)
    probe_metadata(clip_id)
    return {'clip_id':clip_id,'status':'ready','sha256':digest}

@app.get('/api/upload/{upload_id}')
def upload_status(upload_id:str,x_device_token:str=Header(default='')):
    require_device(x_device_token)
    with db() as c: u=c.execute('SELECT id,total,received,filename FROM uploads WHERE id=?',(upload_id,)).fetchone()
    if not u: raise HTTPException(404,'Upload not found')
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
        clip=c.execute('SELECT * FROM clips WHERE id=? AND project_id=? AND status="ready"',(clip_id,p['id'])).fetchone()
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
    if not path.exists():
        ensure_clip_local(clip_id)
        generate_thumbnail(clip_id)
    if not path.exists(): raise HTTPException(404,'Thumbnail unavailable')
    return FileResponse(path,media_type='image/jpeg')

@app.get('/api/review/{slug}/clips/{clip_id}/duration')
def duration(slug:str,clip_id:str,session:str=''):
    get_clip_for_review(slug,clip_id,session)
    try:
        ensure_clip_local(clip_id)
        result=subprocess.run(['ffprobe','-v','error','-show_entries','format=duration','-of','default=noprint_wrappers=1:nokey=1',str(FILES/clip_id)],capture_output=True,text=True,timeout=12,check=True)
        return {'seconds':float(result.stdout.strip())}
    except Exception: return {'seconds':None}

@app.get('/api/review/{slug}/download-selected')
def download_selected(slug:str,ids:str='',session:str=''):
    import zipstream
    p=review_project(slug,session)
    wanted=list(dict.fromkeys(x for x in ids.split(',') if re.fullmatch(r'[0-9a-f]{32}',x)))
    if not wanted or len(wanted)>100: raise HTTPException(400,'Select 1-100 clips')
    with db() as c:
        rows=c.execute('SELECT id,filename FROM clips WHERE project_id=? AND status="ready"',(p['id'],)).fetchall()
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
    review_project(slug,session)
    used=sum(p.stat().st_size for p in FILES.iterdir() if p.is_file())
    return {'used_bytes':used,'limit_bytes':LIMIT_BYTES}
