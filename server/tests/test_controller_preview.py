"""Browser controller preview must stay isolated from all production endpoints."""
from pathlib import Path
from fastapi.testclient import TestClient
import app as backend

client=TestClient(backend.app)

def test_preview_is_public_demo_with_no_live_data():
    response=client.get('/design-preview')
    assert response.status_code==200
    assert 'text/html' in response.headers['content-type']
    assert 'no-store' in response.headers['cache-control']
    assert 'noindex' in response.headers['x-robots-tag']
    assert "connect-src 'none'" in response.headers['content-security-policy']
    assert 'Controller Design Preview' in response.text
    assert 'No live DJI or production data' in response.text
    assert 'controller-preview.js?v=1' in response.text

def test_preview_assets_are_static_and_do_not_call_production_api():
    script=client.get('/static/controller-preview.js')
    style=client.get('/static/controller-preview.css')
    assert script.status_code==200
    assert style.status_code==200
    assert 'text/javascript' in script.headers['content-type'] or 'javascript' in script.headers['content-type']
    assert "fetch(" not in script.text
    assert "XMLHttpRequest" not in script.text
    assert "localStorage" not in script.text
    assert "sessionStorage" not in script.text
    assert "/api/" not in script.text
    for feature in ('wifi_only','charging_only','smart_recovery','adaptive_speed','sd_backup_status','startup_health_check','error_alerts'):
        assert feature in script.text
    assert 'Start demo copy' in script.text
    assert '.frame.mobile' in style.text
