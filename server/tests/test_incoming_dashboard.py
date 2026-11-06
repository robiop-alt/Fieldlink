"""Project-isolated incoming upload progress and recent activity regression tests."""
import time
import uuid
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
import app as backend
import db_persistence


@pytest.fixture
def context(tmp_path, monkeypatch):
    monkeypatch.setattr(db_persistence, "snapshot", lambda *_: None)
    monkeypatch.setattr(backend, "DEVICE", "progress-device")
    monkeypatch.setattr(backend, "TMP", tmp_path / "tmp")
    backend.TMP.mkdir(parents=True)
    project_ids=[uuid.uuid4().hex for _ in range(2)]
    with backend.db() as c:
        for pid in project_ids:
            c.execute("INSERT INTO projects(id,name,slug,pin_hash,created) VALUES(?,?,?,?,?)",
                      (pid, "Upload dashboard test", pid, "fakehash", int(time.time())))
    try:
        with TestClient(backend.app) as client:
            yield client,project_ids
    finally:
        with backend.db() as c:
            for pid in project_ids:
                ids=c.execute("SELECT temp_path FROM uploads WHERE project_id=?", (pid,)).fetchall()
                for item in ids: Path(item["temp_path"]).unlink(missing_ok=True)
                c.execute("DELETE FROM uploads WHERE project_id=?", (pid,))
                c.execute("DELETE FROM incoming_clips WHERE project_id=?", (pid,))
                c.execute("DELETE FROM projects WHERE id=?", (pid,))


def incoming(client,pid,token):
    return client.get(f"/api/review/{pid}/incoming",headers={"Authorization":f"Bearer {token}"})


def test_queue_byte_progress_and_reconnection_are_real(context):
    client,(pid,other)=context
    token=backend.session_token(pid,int(time.time())+3600)
    device={"X-Device-Token":"progress-device"}
    assert incoming(client,pid,token).json()["summary"]["total_clips"]==0

    # Only trusted controller can advertise an upcoming clip.
    endpoint=f"/api/device/projects/{pid}/incoming"
    assert client.put(endpoint,json={"clips":[{"filename":"A.mp4","size":100}]}).status_code==401
    assert client.put(endpoint,headers=device,json={"clips":[
        {"filename":"A.mp4","size":100},{"filename":"B.mp4","size":200}
    ]}).status_code==200

    queued=incoming(client,pid,token).json()
    assert queued["summary"]["total_clips"]==2
    assert queued["summary"]["total_bytes"]==300
    assert queued["summary"]["received_bytes"]==0
    assert queued["summary"]["counts"]["queued"]==2

    # Start A, send exactly 40 bytes, then ensure bytes are not estimated.
    upload=client.post("/api/upload/init",headers=device,json={
        "project_id":pid,"filename":"A.mp4","total_size":100
    })
    assert upload.status_code==200,upload.text
    uid=upload.json()["upload_id"]
    active=incoming(client,pid,token).json()
    a=next(c for c in active["clips"] if c["filename"]=="A.mp4")
    assert a["status"]=="starting"
    chunk=client.put(f"/api/upload/{uid}/chunk",headers={**device,"X-Offset":"0"},
        files={"chunk":("chunk.bin",b"x"*40,"application/octet-stream")})
    assert chunk.status_code==200,chunk.text
    progress=incoming(client,pid,token).json()
    assert progress["summary"]["received_bytes"]==40
    assert progress["summary"]["total_bytes"]==300
    assert progress["summary"]["percent"]==13
    a=next(c for c in progress["clips"] if c["filename"]=="A.mp4")
    assert a["percent"]==40 and a["status"]=="uploading"

    # A very old upload with a fresh chunk must NOT look disconnected.
    with backend.db() as c:
        c.execute("UPDATE uploads SET created=?,updated=? WHERE id=?",
                  (int(time.time())-100000,int(time.time()),uid))
    assert next(c for c in incoming(client,pid,token).json()["clips"] if c["filename"]=="A.mp4")["status"]=="uploading"
    with backend.db() as c:
        c.execute("UPDATE uploads SET updated=? WHERE id=?",(int(time.time())-120,uid))
    stalled=next(c for c in incoming(client,pid,token).json()["clips"] if c["filename"]=="A.mp4")
    assert stalled["status"]=="reconnecting" and stalled["received"]==40

    # Receiving the rest means 'processing', never 'ready' before verification.
    with backend.db() as c:
        c.execute("UPDATE uploads SET received=total,updated=? WHERE id=?",(int(time.time()),uid))
    final=next(c for c in incoming(client,pid,token).json()["clips"] if c["filename"]=="A.mp4")
    assert final["status"]=="processing" and final["percent"]==100
    assert incoming(client,pid,backend.session_token(other,int(time.time())+3600)).status_code==401


def test_private_project_and_other_project_isolation(context):
    client,(pid,other)=context
    device={"X-Device-Token":"progress-device"}
    client.put(f"/api/device/projects/{pid}/incoming",headers=device,json={
        "clips":[{"filename":"Secret.mp4","size":4096}]
    })
    anonymous=client.get(f"/api/review/{pid}/incoming")
    assert anonymous.status_code==401
    token_other=backend.session_token(other,int(time.time())+3600)
    assert incoming(client,other,token_other).json()["clips"]==[]
    token=backend.session_token(pid,int(time.time())+3600)
    assert incoming(client,pid,token).json()["summary"]["total_bytes"]==4096
