"""Upload ages originate from verified server timestamps, never device claims."""
import time
import uuid
from fastapi.testclient import TestClient
import app as backend

client=TestClient(backend.app)

def test_review_returns_verified_upload_timestamp():
    project_id=uuid.uuid4().hex
    slug=uuid.uuid4().hex
    clip_id=uuid.uuid4().hex
    stamp=int(time.time())-120
    with backend.db() as c:
        c.execute("INSERT INTO projects(id,name,slug,pin_hash,created) VALUES(?,?,?,?,?)",
            (project_id,"Uploaded at test",slug,"unused",stamp))
        c.execute("INSERT INTO clips(id,project_id,filename,size,created,status,sha256) VALUES(?,?,?,?,?,?,?)",
            (clip_id,project_id,"age-test.mp4",1024,stamp,"ready","0"*64))
    try:
        token=backend.session_token(project_id,int(time.time())+3600)
        response=client.get(f"/api/review/{slug}/clips",
            headers={"Authorization":f"Bearer {token}"})
        assert response.status_code==200,response.text
        entry=next(c for c in response.json() if c['id']==clip_id)
        assert entry['created']==stamp
        assert client.get(f"/api/review/{slug}/clips").status_code==401
    finally:
        with backend.db() as c:
            c.execute("DELETE FROM clips WHERE id=?",(clip_id,))
            c.execute("DELETE FROM projects WHERE id=?",(project_id,))

def test_relative_time_script_and_review_include_live_labels():
    response=client.get("/static/upload-time.js")
    assert response.status_code==200
    for phrase in ("Uploaded just now"," sec ago"," min ago"," hr ago"," day"," months"):
        assert phrase in response.text
    assert "setInterval" in response.text
    assert "data-uploaded-at" in response.text
    html=client.get("/r/test-slug").text
    assert 'data-uploaded-at=' in html
    assert "OffsetUploadTime?.update()" in html

def test_admin_latest_verified_timestamp_query_and_ui():
    html=client.get("/").text
    assert "p.last_uploaded_at" in html
    assert "No uploads yet" in html
    assert "upload-time.js?v=1" in html
