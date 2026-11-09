"""Regression tests for OFFSET admin authentication (isolated CI credentials)."""
import pytest
from fastapi.testclient import TestClient
import app as backend


@pytest.fixture
def client(monkeypatch):
    monkeypatch.setattr(backend, "ADMIN_USER", "offset-admin")
    monkeypatch.setattr(backend, "ADMIN_PASSWORD", "test-password")
    monkeypatch.setattr(backend, "SECRET", "test-only-ci-signing-secret")
    monkeypatch.setattr(backend, "ADMIN", "")
    with backend.LOGIN_LOCK:
        backend.LOGIN_ATTEMPTS.clear()
    with TestClient(backend.app, base_url="https://testserver") as client:
        yield client


def test_admin_endpoints_require_login(client):
    assert client.get("/api/admin/me").status_code == 401
    assert client.get("/api/admin/projects").status_code == 401


def test_wrong_password_rejected(client):
    response = client.post("/api/admin/login", json={
        "username": "offset-admin", "password": "wrong"
    })
    assert response.status_code == 401
    assert "offset_admin" not in client.cookies


def test_login_cookie_and_logout(client):
    response = client.post("/api/admin/login", json={
        "username": "offset-admin", "password": "test-password"
    })
    assert response.status_code == 200
    cookie = response.headers["set-cookie"].lower()
    assert "httponly" in cookie
    assert "secure" in cookie
    assert "samesite=strict" in cookie
    assert client.get("/api/admin/me").json()["username"] == "offset-admin"
    assert client.post("/api/admin/logout", headers={
        "origin": "https://testserver"
    }).status_code == 200
    assert client.get("/api/admin/me").status_code == 401


def test_cross_origin_mutation_rejected(client):
    assert client.post("/api/admin/login", json={
        "username": "offset-admin", "password": "test-password"
    }).status_code == 200
    response = client.put("/api/admin/settings",
        headers={"origin": "https://untrusted.example"},
        json={
            "admin_title": "A", "admin_highlight": "B",
            "admin_description": "C", "review_title": "D",
            "review_highlight": "E", "review_description": "F"
        })
    assert response.status_code == 403


def test_rate_limit_rejects_repeated_bad_logins(client):
    for _ in range(8):
        assert client.post("/api/admin/login", json={
            "username": "offset-admin", "password": "bad"
        }).status_code == 401
    assert client.post("/api/admin/login", json={
        "username": "offset-admin", "password": "bad"
    }).status_code == 429


def test_admin_can_manage_project_clip(client, monkeypatch):
    import db_persistence
    monkeypatch.setattr(db_persistence, "snapshot", lambda *args: None)
    import time
    import uuid

    pid, clip_id = uuid.uuid4().hex, uuid.uuid4().hex
    slug = 'test-' + pid
    now = int(time.time())
    with backend.db() as db:
        db.execute(
            'INSERT INTO projects(id,name,slug,pin_hash,created) VALUES(?,?,?,?,?)',
            (pid, 'Visibility test', slug, 'unused', now))
        db.execute(
            'INSERT INTO clips(id,project_id,filename,size,created,status) VALUES(?,?,?,?,?,?)',
            (clip_id, pid, 'test.mp4', 4, now, 'ready'))
    path = backend.FILES / clip_id
    path.write_bytes(b'TEST')
    try:
        token = backend.session_token(pid, now + 3600)
        review_headers = {'Authorization': 'Bearer ' + token}
        review = f'/api/review/{slug}'
        admin = f'/api/admin/projects/{pid}/clips'
        visibility = f'{admin}/{clip_id}/visibility'
        deletion = f'{admin}/{clip_id}'
        assert client.delete(deletion, headers=review_headers).status_code == 401
        assert client.get(admin).status_code == 401
        assert client.patch(visibility, headers=review_headers, json={'hidden': True}).status_code == 401
        assert [clip['id'] for clip in client.get(review + '/clips', headers=review_headers).json()] == [clip_id]
        assert client.post('/api/admin/login', json={
            'username': 'offset-admin', 'password': 'test-password'
        }).status_code == 200
        assert client.patch(visibility, headers={'Origin': 'https://untrusted.example'},
                            json={'hidden': True}).status_code == 403
        origin = {'Origin': 'https://testserver'}
        assert client.patch(visibility, headers=origin, json={'hidden': True}).json()['hidden'] is True
        assert client.get(admin).json()[0]['hidden'] == 1
        assert client.get(review + '/clips', headers=review_headers).json() == []
        for suffix in ('file', 'file?download=1', 'thumbnail', 'duration'):
            separator = '&' if '?' in suffix else '?'
            assert client.get(f'{review}/clips/{clip_id}/{suffix}{separator}session={token}').status_code == 404
        assert client.get(f'{review}/download-selected?session={token}&ids={clip_id}').status_code == 404
        assert path.read_bytes() == b'TEST'
        assert client.patch(visibility, headers=origin, json={'hidden': False}).json()['hidden'] is False
        assert [clip['id'] for clip in client.get(review + '/clips', headers=review_headers).json()] == [clip_id]
        assert client.delete(deletion, headers={'Origin': 'https://untrusted.example'}).status_code == 403
        assert client.delete(f'/api/admin/projects/wrong/clips/{clip_id}', headers=origin).status_code == 404
        objects = []
        monkeypatch.setattr(backend, 'r2_enabled', lambda: True)
        monkeypatch.setattr(backend, 'delete_file', objects.append)
        with backend.db() as db:
            db.execute('INSERT INTO completed_uploads VALUES(?,?,?,?,?)',
                       ('completed-' + clip_id, pid, clip_id, 4, 'test-sha'))
        assert client.delete(deletion, headers=origin).json()['deleted'] is True
        assert objects == [f'thumbnails/{clip_id}.jpg', f'clips/{clip_id}']
        assert not path.exists()
        assert client.get(admin).json() == []
        assert client.get(review + '/clips', headers=review_headers).json() == []
        with backend.db() as db:
            assert db.execute('SELECT 1 FROM completed_uploads WHERE clip_id=?',
                              (clip_id,)).fetchone() is None
    finally:
        path.unlink(missing_ok=True)
        with backend.db() as db:
            db.execute('DELETE FROM clips WHERE id=?', (clip_id,))
            db.execute('DELETE FROM projects WHERE id=?', (pid,))
