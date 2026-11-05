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
