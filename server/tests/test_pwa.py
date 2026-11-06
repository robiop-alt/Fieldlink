"""Installable OFFSET iPhone app keeps access to rushes network-only."""
from fastapi.testclient import TestClient
import app

client=TestClient(app.app)

def test_manifest_admin_start():
    r=client.get('/app.webmanifest')
    assert r.status_code==200
    m=r.json()
    assert m['start_url']=='/'
    assert m['display']=='standalone'
    assert m['icons'][0]['type']=='image/png'

def test_manifest_project_deeplink_and_invalid_slug():
    r=client.get('/app.webmanifest?project=ABC_123')
    assert r.json()['start_url']=='/r/ABC_123'
    assert r.json()['id']=='/r/ABC_123'
    assert client.get('/app.webmanifest?project=invalid%2Fpath').json()['start_url']=='/'

def test_icons_are_real_png():
    for size in (180,192,512):
        r=client.get('/pwa/icon-'+str(size)+'.png')
        assert r.status_code==200
        assert r.headers['content-type']=='image/png'
        assert r.content[:8]==bytes([137,80,78,71,13,10,26,10])
    assert client.get('/pwa/icon-999.png').status_code==404

def test_service_worker_and_install_html():
    r=client.get('/offset-sw.js')
    assert r.status_code==200
    assert 'no-cache' in r.headers['cache-control']
    assert b"request.mode" in r.content
    for page in ('/','/r/test-project'):
        html=client.get(page).text
        assert 'apple-touch-icon' in html
        assert '/app.webmanifest' in html
