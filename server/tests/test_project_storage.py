"""Project storage includes uploaded cloud clips, independent of local cache."""
import time
import uuid
import pytest
from fastapi.testclient import TestClient
import app as backend
import db_persistence

@pytest.fixture
def projects(monkeypatch, tmp_path):
    monkeypatch.setattr(db_persistence, 'snapshot', lambda *args: None)
    monkeypatch.setattr(backend, 'FILES', tmp_path)
    pid, other = uuid.uuid4().hex, uuid.uuid4().hex
    with backend.db() as c:
        for ident in (pid, other):
            c.execute("INSERT INTO projects(id,name,slug,pin_hash,created) VALUES(?,?,?,?,?)",
                      (ident, 'Storage test', ident, 'unused', int(time.time())))
        for project_id, name, size, status in (
            (pid, 'first.mp4', 104857600, 'ready'),
            (pid, 'second.mp4', 52428800, 'ready'),
            (pid, 'unfinished.mp4', 999999999, 'uploading'),
            (other, 'other.mp4', 888888888, 'ready'),
        ):
            c.execute("INSERT INTO clips(id,project_id,filename,size,created,status) VALUES(?,?,?,?,?,?)",
                      (uuid.uuid4().hex, project_id, name, size, int(time.time()), status))
    token=backend.session_token(pid,int(time.time())+3600)
    try:
        with TestClient(backend.app) as client:
            yield client,pid,token
    finally:
        with backend.db() as c:
            c.execute("DELETE FROM clips WHERE project_id IN (?,?)",(pid,other))
            c.execute("DELETE FROM projects WHERE id IN (?,?)",(pid,other))

def test_cloud_media_counted_with_empty_local_cache(projects):
    client,pid,token=projects
    assert list(backend.FILES.iterdir())==[]
    response=client.get(f'/api/review/{pid}/storage?session={token}')
    assert response.status_code==200
    assert response.json()=={'used_bytes':157286400,'clip_count':2}
    assert response.headers['cache-control']=='no-store'

def test_project_storage_requires_its_review_session(projects):
    client,pid,token=projects
    assert client.get(f'/api/review/{pid}/storage').status_code==401

def test_empty_project_and_deleted_clips_are_reflected(projects):
    client,pid,token=projects
    with backend.db() as c:
        c.execute("DELETE FROM clips WHERE project_id=?",(pid,))
    assert client.get(f'/api/review/{pid}/storage?session={token}').json()=={
        'used_bytes':0,'clip_count':0}
