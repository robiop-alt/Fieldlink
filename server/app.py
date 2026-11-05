from r2_storage import upload_file, download_file, delete_file
from fastapi import FastAPI, HTTPException, Header, Request, UploadFile, File
from fastapi.responses import FileResponse, HTMLResponse, StreamingResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field
from pathlib import Path
import os, sqlite3, secrets, hashlib, hmac, time, uuid, re, mimetypes, subprocess, threading

DATA = Path(os.getenv('FIELDLINK_DATA', './data')).resolve()
FILES = DATA / 'files'; TMP = DATA / 'tmp'; THUMBS = DATA / 'thumbs'; DB = DATA / 'fieldlink.sqlite3'
LIMIT_BYTES = 6 * 1024**3
STORAGE_LOCK = threading.RLock()
for p in (FILES, TMP, THUMBS): p.mkdir(parents=True, exist_ok=True)
ADMIN = os.getenv('FIELDLINK_ADMIN_KEY', ''); DEVICE = os.getenv('FIELDLINK_DEVICE_TOKEN', '')
SECRET = os.getenv('FIELDLINK_SECRET', 'development-only-change-me')
app = FastAPI(title='OFFSET API', version='1.0.0')
app.mount('/static', StaticFiles(directory='static'), name='static')

# Metadata is extracted from the received original, never from client-supplied values.


def db():
    c=sqlite3.connect(DB); c.row_factory=sqlite3.Row; return c
with db() as c:
    c.executescript('''CREATE TABLE IF NOT EXISTS projects(id TEXT PRIMARY KEY,name TEXT NOT NULL,slug TEXT UNIQUE NOT NULL,pin_hash TEXT NOT NULL,created INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS clips(id TEXT PRIMARY KEY,project_id TEXT NOT NULL,filename TEXT NOT NULL,size INTEGER NOT NULL,created INTEGER NOT NULL,status TEXT NOT NULL,sha256 TEXT,UNIQUE(project_id,filename),FOREIGN KEY(project_id) REFERENCES projects(id));
    CREATE TABLE IF NOT EXISTS uploads(id TEXT PRIMARY KEY,project_id TEXT NOT NULL,filename TEXT NOT NULL,total INTEGER NOT NULL,received INTEGER NOT NULL DEFAULT 0,temp_path TEXT NOT NULL,created INTEGER NOT NULL);''')

def ensure_metadata_schema():
    with db() as c:
        c.execute("CREATE TABLE IF NOT EXISTS clip_metadata (clip_id TEXT PRIMARY KEY, data_json TEXT NOT NULL)")
ensure_metadata_schema()

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

def require_admin(key):
    if not ADMIN or not hmac.compare_digest(key or '', ADMIN): raise HTTPException(401,'Admin authentication required')
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
def create_project(body:ProjectIn, x_admin_key:str=Header(default='')):
    require_admin(x_admin_key)
    pid=uuid.uuid4().hex; slug=secrets.token_urlsafe(8).replace('-','').replace('_','')
    pin=f'{secrets.randbelow(1_000_000):06d}'
    with db() as c: c.execute('INSERT INTO projects VALUES(?,?,?,?,?)',(pid,body.name,slug,pin_digest(pin),int(time.time())))
    return {'id':pid,'name':body.name,'slug':slug,'url_path':f'/r/{slug}','pin':pin}

@app.get('/api/admin/projects')
def list_projects(x_admin_key:str=Header(default='')):
    require_admin(x_admin_key)
    with db() as c:
        rows=c.execute('SELECT p.id,p.name,p.slug,p.created,COUNT(cl.id) clips FROM projects p LEFT JOIN clips cl ON cl.project_id=p.id GROUP BY p.id ORDER BY p.created DESC').fetchall()
    return [dict(r) for r in rows]

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

@app.get('/api/review/{slug}/clips/{clip_id}/file')
def clip_file(slug:str,clip_id:str,request:Request,authorization:str=Header(default=''),session:str='',download:int=0):
    token=authorization.removeprefix('Bearer ').strip() or session; p=review_project(slug,token)
    with db() as c: clip=c.execute('SELECT * FROM clips WHERE id=? AND project_id=? AND status="ready"',(clip_id,p['id'])).fetchone()
    if not clip: raise HTTPException(404,'Clip not found')
    path=FILES/clip_id
    if not path.exists() and os.getenv("R2_ENDPOINT"):
        try:
            download_file(f"clips/{clip_id}", path)
        except Exception:
            import logging
            logging.exception("R2 download failed for clip %s", clip_id)
    if not path.exists(): raise HTTPException(404,'File missing')
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
    """Delete oldest completed clips until the next upload fits."""
    with db() as c:
        rows=c.execute('SELECT id,size FROM clips WHERE status="ready" ORDER BY created ASC,id ASC').fetchall()
        used=sum((FILES/r['id']).stat().st_size for r in rows if (FILES/r['id']).exists())
        for r in rows:
            if used+incoming <= LIMIT_BYTES: break
            f=FILES/r['id']; actual=f.stat().st_size if f.exists() else 0
            f.unlink(missing_ok=True)
            (THUMBS/(r['id']+'.jpg')).unlink(missing_ok=True)
            c.execute('DELETE FROM clips WHERE id=?',(r['id'],))
            c.execute('DELETE FROM clip_metadata WHERE clip_id=?',(r['id'],))
            used-=actual
        if used+incoming > LIMIT_BYTES: raise HTTPException(507,'Insufficient storage')

def generate_thumbnail(clip_id):
    source=FILES/clip_id; output=THUMBS/(clip_id+'.jpg')
    try:
        subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-y','-ss','2','-i',str(source),'-frames:v','1','-vf','scale=640:-2',str(output)],timeout=40,check=True)
    except Exception:
        output.unlink(missing_ok=True)

def get_clip_for_review(slug,clip_id,token):
    p=review_project(slug,token)
    with db() as c:
        clip=c.execute('SELECT * FROM clips WHERE id=? AND project_id=? AND status="ready"',(clip_id,p['id'])).fetchone()
    if not clip: raise HTTPException(404,'Clip not found')
    return clip

@app.get('/api/review/{slug}/clips/{clip_id}/thumbnail')
def thumbnail(slug:str,clip_id:str,session:str=''):
    get_clip_for_review(slug,clip_id,session)
    path=THUMBS/(clip_id+'.jpg')
    if not path.exists():
        if not (FILES/clip_id).exists(): raise HTTPException(404,'Clip missing')
        generate_thumbnail(clip_id)
    if not path.exists(): raise HTTPException(404,'Thumbnail unavailable')
    return FileResponse(path,media_type='image/jpeg')

@app.get('/api/review/{slug}/clips/{clip_id}/duration')
def duration(slug:str,clip_id:str,session:str=''):
    get_clip_for_review(slug,clip_id,session)
    try:
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
        path=FILES/cid
        if not path.exists(): raise HTTPException(404,'Clip missing')
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
