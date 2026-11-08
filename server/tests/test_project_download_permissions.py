"""Project-scoped admin download permission regression tests."""
import time
import uuid

import pytest
from fastapi.testclient import TestClient
import app as backend
import db_persistence

@pytest.fixture
def project(monkeypatch):
    monkeypatch.setattr(db_persistence, 'snapshot', lambda *args: None)
    monkeypatch.setattr(backend, "ADMIN_USER", "")
    monkeypatch.setattr(backend, "ADMIN", "local-admin-test-key")
    monkeypatch.setattr(backend, "r2_enabled", lambda: False)
    pid="T"+uuid.uuid4().hex[:10].upper()
    slug="test-"+uuid.uuid4().hex[:12]
    clip_id=uuid.uuid4().hex
    name="Test Rushes"
    with backend.db() as c:
        c.execute("INSERT INTO projects(id,name,slug,pin_hash,created,public_review,allow_downloads) VALUES(?,?,?,?,?,?,?)",
                  (pid,name,slug,"testhash",int(time.time()),0,1))
        c.execute("INSERT INTO clips(id,project_id,filename,size,created,status) VALUES(?,?,?,?,?,?)",
                  (clip_id,pid,"test.mp4",4,int(time.time()),"ready"))
    (backend.FILES/clip_id).write_bytes(b"TEST")
    try:
        with TestClient(backend.app) as client:
            token=backend.session_token(pid,int(time.time())+3600)
            yield client,pid,slug,clip_id,token
    finally:
        (backend.FILES/clip_id).unlink(missing_ok=True)
        with backend.db() as c:
            c.execute("DELETE FROM clips WHERE id=?",(clip_id,))
            c.execute("DELETE FROM projects WHERE id=?",(pid,))

def test_default_allows_clip_and_zip_download(project):
    client,pid,slug,cid,token=project
    h={"X-Admin-Key":"local-admin-test-key"}
    projects=client.get("/api/admin/projects",headers=h).json()
    row=next(x for x in projects if x["id"]==pid)
    assert row["allow_downloads"]==1
    assert client.get(f"/api/review/{slug}/settings?session={token}").json()=={"allow_downloads":True,"project_name":"Test Rushes"}
    assert client.get(f"/api/review/{slug}/clips/{cid}/file?session={token}&download=1").status_code==200
    assert client.get(f"/api/review/{slug}/download-selected?session={token}&ids={cid}").status_code==200

def test_admin_can_disable_and_restore_downloads(project):
    client,pid,slug,cid,token=project
    url=f"/api/admin/projects/{pid}/review-settings"
    assert client.patch(url,json={"allow_downloads":False}).status_code==401
    h={"X-Admin-Key":"local-admin-test-key"}
    assert client.patch(url,headers=h,json={"allow_downloads":False}).json()["allow_downloads"] is False
    assert client.get(f"/api/review/{slug}/settings?session={token}").json()=={"allow_downloads":False,"project_name":"Test Rushes"}
    assert client.get(f"/api/review/{slug}/clips/{cid}/file?session={token}&download=1").status_code==403
    assert client.get(f"/api/review/{slug}/download-selected?session={token}&ids={cid}").status_code==403
    # Streaming remains permitted so the review page still works.
    assert client.get(f"/api/review/{slug}/clips/{cid}/file?session={token}").status_code==200
    assert client.patch(url,headers=h,json={"allow_downloads":True}).json()["allow_downloads"] is True
    assert client.get(f"/api/review/{slug}/clips/{cid}/file?session={token}&download=1").status_code==200
