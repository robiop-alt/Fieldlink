"""Project access changes can toggle PIN protection and revoke old sessions."""
import time
import uuid
import pytest
from fastapi.testclient import TestClient
import app as backend
import db_persistence

@pytest.fixture
def project(monkeypatch):
    monkeypatch.setattr(db_persistence,"snapshot",lambda *args:None)
    monkeypatch.setattr(backend,"ADMIN_USER","")
    monkeypatch.setattr(backend,"ADMIN","test-admin-key")
    pid="P"+uuid.uuid4().hex[:10].upper()
    slug="pin-"+uuid.uuid4().hex[:12]
    with backend.db() as c:
        c.execute("INSERT INTO projects(id,name,slug,pin_hash,created,public_review) VALUES(?,?,?,?,?,?)",
            (pid,"PIN test",slug,backend.pin_digest("123456"),int(time.time()),1))
    try:
        with TestClient(backend.app) as client:
            yield client,pid,slug
    finally:
        with backend.db() as c:
            c.execute("DELETE FROM projects WHERE id=?",(pid,))

def test_toggle_pin_protection_revokes_old_sessions(project):
    client,pid,slug=project
    headers={"X-Admin-Key":"test-admin-key"}
    old=client.post(f"/api/review/{slug}/public-unlock").json()["token"]
    assert client.get(f"/api/review/{slug}/settings?session={old}").status_code==200
    settings=f"/api/admin/projects/{pid}/review-settings"
    assert client.patch(settings,json={"allow_downloads":True,"public_review":False}).status_code==401
    enabled=client.patch(settings,headers=headers,json={"allow_downloads":False,"public_review":False})
    assert enabled.status_code==200,enabled.text
    new_pin=enabled.json()["pin"]
    assert len(new_pin)==6 and new_pin.isdigit()
    assert enabled.json()["public_review"] is False
    assert client.post(f"/api/review/{slug}/public-unlock").status_code==403
    assert client.get(f"/api/review/{slug}/settings?session={old}").status_code==401
    assert client.post(f"/api/review/{slug}/unlock",json={"pin":"123456"}).status_code==403
    fresh=client.post(f"/api/review/{slug}/unlock",json={"pin":new_pin})
    assert fresh.status_code==200
    pin_session=fresh.json()["token"]
    assert client.get(f"/api/review/{slug}/settings?session={pin_session}").json()=={"allow_downloads":False}
    same=client.patch(settings,headers=headers,json={"allow_downloads":True,"public_review":False})
    assert same.json()["pin"] is None
    assert client.get(f"/api/review/{slug}/settings?session={pin_session}").status_code==200
    public=client.patch(settings,headers=headers,json={"allow_downloads":True,"public_review":True})
    assert public.json()["pin"] is None
    assert public.json()["public_review"] is True
    assert client.get(f"/api/review/{slug}/settings?session={pin_session}").status_code==401
    assert client.post(f"/api/review/{slug}/public-unlock").status_code==200

def test_other_project_unchanged_and_bad_project_returns_404(project):
    client,pid,slug=project
    r=client.patch('/api/admin/projects/doesnotexist/review-settings',
        headers={"X-Admin-Key":"test-admin-key"},
        json={"allow_downloads":True,"public_review":False})
    assert r.status_code==404

def test_admin_pin_visibility_duration_and_expiration(project):
    client,pid,slug=project
    headers={'X-Admin-Key':'test-admin-key'}
    url=f'/api/admin/projects/{pid}/review-settings'
    assert client.get(url).status_code==401
    setting=client.get(url,headers=headers).json()
    assert setting['legacy_pin'] is True
    assert setting['pin'] is None
    enabled=client.patch(url,headers=headers,json={
        'allow_downloads':True,'public_review':False,'pin_days':14
    }).json()
    pin=enabled['pin']
    now=int(time.time())
    info=client.get(url,headers=headers).json()
    assert info['pin']==pin
    assert info['pin_days']==14
    assert now+13*86400 <= info['pin_expires_at'] <= now+15*86400
    valid=client.post(f'/api/review/{slug}/unlock',json={'pin':pin}).json()['token']
    changed=client.patch(url,headers=headers,json={
        'allow_downloads':True,'public_review':False,'pin_days':30
    }).json()
    assert changed['pin'] is None
    assert client.get(url,headers=headers).json()['pin']==pin
    assert client.get(url,headers=headers).json()['pin_days']==30
    assert client.get(f'/api/review/{slug}/settings?session={valid}').status_code==401
    fresh=client.post(f'/api/review/{slug}/unlock',json={'pin':pin}).json()['token']
    with backend.db() as c:
        c.execute('UPDATE projects SET pin_expires_at=? WHERE id=?',(int(time.time())-1,pid))
    assert client.post(f'/api/review/{slug}/unlock',json={'pin':pin}).status_code==403
    assert client.get(f'/api/review/{slug}/settings?session={fresh}').status_code==401
    renewed=client.patch(url,headers=headers,json={
        'allow_downloads':True,'public_review':False,'pin_days':7,'renew_pin':True
    }).json()
    assert renewed['pin'] and renewed['pin']!=pin
    assert client.get(url,headers=headers).json()['pin_days']==7
    assert client.post(f'/api/review/{slug}/unlock',json={'pin':renewed['pin']}).status_code==200
