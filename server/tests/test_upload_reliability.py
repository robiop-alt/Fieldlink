"""Regression tests for interrupted completion acknowledgments and file integrity."""
import hashlib
import uuid
import pytest
from fastapi.testclient import TestClient
import app as backend
import r2_storage
import db_persistence


@pytest.fixture
def client(tmp_path, monkeypatch):
    monkeypatch.setattr(db_persistence, "snapshot", lambda *args: None)
    monkeypatch.setattr(backend, "DEVICE", "ci-device")
    monkeypatch.setattr(backend, "TMP", tmp_path / "tmp")
    monkeypatch.setattr(backend, "FILES", tmp_path / "files")
    backend.TMP.mkdir()
    backend.FILES.mkdir()
    monkeypatch.setenv("R2_ENDPOINT", "https://example.invalid")
    monkeypatch.setattr(r2_storage, "upload_clip_if_enabled", lambda *args: True)
    monkeypatch.setattr(backend, "make_room", lambda *args: None)
    monkeypatch.setattr(backend, "generate_thumbnail", lambda *args: None)
    monkeypatch.setattr(backend, "probe_metadata", lambda *args: None)
    project = uuid.uuid4().hex
    with backend.db() as connection:
        connection.execute("INSERT INTO projects(id,name,slug,pin_hash,created) VALUES(?,?,?,?,?)",
                           (project, "Upload test", project, "fake-pin-hash", 0))
    with TestClient(backend.app) as test_client:
        yield test_client, project


def upload_bytes(client, project, data):
    headers = {"X-Device-Token": "ci-device"}
    response = client.post("/api/upload/init", headers=headers, json={
        "project_id": project, "filename": "test.mp4", "total_size": len(data)
    })
    assert response.status_code == 200, response.text
    upload_id = response.json()["upload_id"]
    chunk = client.put(f"/api/upload/{upload_id}/chunk", headers={**headers, "X-Offset": "0"},
                       files={"chunk": ("chunk.bin", data, "application/octet-stream")})
    assert chunk.status_code == 200, chunk.text
    return upload_id


def test_completion_is_idempotent_after_lost_response(client):
    app_client, project = client
    payload = b"safe drone footage"
    digest = hashlib.sha256(payload).hexdigest()
    upload_id = upload_bytes(app_client, project, payload)
    headers = {"X-Device-Token": "ci-device", "X-Content-SHA256": digest}
    first = app_client.post(f"/api/upload/{upload_id}/complete", headers=headers)
    assert first.status_code == 200, first.text
    assert first.json()["sha256"] == digest
    second = app_client.post(f"/api/upload/{upload_id}/complete", headers=headers)
    assert second.status_code == 200, second.text
    assert second.json()["clip_id"] == first.json()["clip_id"]
    status = app_client.get(f"/api/upload/{upload_id}", headers=headers)
    assert status.json()["status"] == "ready"
    assert status.json()["received"] == len(payload)
    with backend.db() as connection:
        clips = connection.execute("SELECT COUNT(*) FROM clips WHERE id=?", (first.json()["clip_id"],)).fetchone()[0]
    assert clips == 1


def test_wrong_sha_never_marks_upload_ready(client):
    app_client, project = client
    upload_id = upload_bytes(app_client, project, b"real contents")
    headers = {"X-Device-Token": "ci-device", "X-Content-SHA256": "0" * 64}
    response = app_client.post(f"/api/upload/{upload_id}/complete", headers=headers)
    assert response.status_code == 422
    status = app_client.get(f"/api/upload/{upload_id}", headers=headers)
    assert status.status_code == 200
    assert "status" not in status.json()
