"""Appearance is public to read, admin-only to change, and independent of copy."""
import contextlib
import sqlite3
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from fastapi.testclient import TestClient
import app as backend

class AppearanceTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        database=Path(self.temp.name)/'appearance.sqlite3'
        @contextlib.contextmanager
        def connect():
            c=sqlite3.connect(database);c.row_factory=sqlite3.Row
            try:
                with c:yield c
            finally:c.close()
        with connect() as c:
            c.execute('CREATE TABLE site_settings(key TEXT PRIMARY KEY,value TEXT NOT NULL)')
            c.execute('INSERT INTO site_settings VALUES(?,?)',('admin_title','Keep this copy'))
        self.connect=connect
        self.patches=[patch.object(backend,'db',connect),patch.object(backend,'ADMIN_USER','appearance-admin'),patch.object(backend,'ADMIN_PASSWORD','test-only-password'),patch.object(backend,'SECRET','appearance-test-only-secret'),patch.object(backend,'ADMIN','')]
        for p in self.patches:p.start()
        with backend.LOGIN_LOCK:backend.LOGIN_ATTEMPTS.clear()
        self.client=TestClient(backend.app,base_url='https://testserver',headers={'origin':'https://testserver'})
    def tearDown(self):
        self.client.close()
        for p in reversed(self.patches):p.stop()
        self.temp.cleanup()
    def login(self):
        self.assertEqual(self.client.post('/api/admin/login',json={'username':'appearance-admin','password':'test-only-password'}).status_code,200)
    def test_public_default_and_authenticated_persistence(self):
        r=self.client.get('/api/site/appearance')
        self.assertEqual(r.json(),{'accent_color':'#57b62f'})
        self.assertEqual(r.headers['cache-control'],'no-store')
        self.assertEqual(self.client.put('/api/admin/appearance',json={'accent_color':'#9dd9ff'}).status_code,401)
        self.login()
        self.assertEqual(self.client.put('/api/admin/appearance',json={'accent_color':'#9DD9FF'}).json(),{'accent_color':'#9dd9ff'})
        self.client.post('/api/admin/logout')
        self.assertEqual(self.client.get('/api/site/appearance').json(),{'accent_color':'#9dd9ff'})
        with self.connect() as c:self.assertEqual(c.execute('SELECT value FROM site_settings WHERE key=?',('admin_title',)).fetchone()['value'],'Keep this copy')
    def test_validation_cross_origin_and_default_reset(self):
        self.login()
        for color in ['red','#fff','url(javascript:alert(1))','#123456;display:none','<script>']:
            self.assertEqual(self.client.put('/api/admin/appearance',json={'accent_color':color}).status_code,422)
        self.assertEqual(self.client.put('/api/admin/appearance',headers={'origin':'https://untrusted.example'},json={'accent_color':'#9dd9ff'}).status_code,403)
        self.client.put('/api/admin/appearance',json={'accent_color':'#9dd9ff'})
        self.client.put('/api/admin/appearance',json={'accent_color':'#57b62f'})
        self.assertEqual(self.client.get('/api/site/appearance').json()['accent_color'],'#57b62f')
    def test_invalid_stored_color_falls_back(self):
        with self.connect() as c:c.execute('INSERT INTO site_settings VALUES(?,?)',('accent_color','invalid'))
        self.assertEqual(self.client.get('/api/site/appearance').json()['accent_color'],'#57b62f')
