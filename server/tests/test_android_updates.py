import io
import json
import os
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
import android_updates as updates

class UpdateTests(unittest.TestCase):
    def setUp(self):
        self.env=patch.dict(os.environ,{'OFFSET_GITHUB_RELEASE_TOKEN':'test-server-token'})
        self.env.start();self.addCleanup(self.env.stop)
        self.release={'tag_name':'offset-android-207','name':'OFFSET 207','assets':[{'id':123,'name':'app-release.apk','browser_download_url':'https://github.com/example.apk'}]}
    def test_private_metadata_contains_ticket_but_no_github_token(self):
        with patch.object(updates,'_open',return_value=io.BytesIO(json.dumps(self.release).encode())):
            data=updates.latest_update('https://offset.example','secret','device')
        self.assertEqual(data['version_code'],207)
        self.assertIn('/api/device/app-download?asset=123',data['download_url'])
        self.assertNotIn('test-server-token',str(data));self.assertNotIn('device',data['download_url'].split('?')[1])
    def test_public_fallback_preserves_existing_updater(self):
        with patch.dict(os.environ,{'OFFSET_GITHUB_RELEASE_TOKEN':''}),patch.object(updates,'_open',return_value=io.BytesIO(json.dumps(self.release).encode())):
            self.assertEqual(updates.latest_update('https://offset.example','s','d')['download_url'],'https://github.com/example.apk')
    def test_invalid_expired_or_revoked_ticket_does_not_fetch(self):
        with patch.object(updates,'_open') as fetch,patch.object(updates.time,'time',return_value=1000):
            for expiry,signature,device in [(999,'bad','d'),(2000,'bad','d'),(1100,updates._signature(123,1100,'s','old'),'new')]:
                with self.assertRaises(PermissionError):updates.download_release(123,expiry,signature,'s',device)
            fetch.assert_not_called()
    def test_github_redirect_is_returned_without_forwarding_credentials(self):
        error=HTTPError('https://api.github.com/asset',302,'Found',{'Location':'https://release-assets.githubusercontent.com/file?signature=short-lived'},io.BytesIO())
        with patch.object(updates.time,'time',return_value=1000),patch.object(updates,'_open',side_effect=error) as fetch:
            url,content=updates.download_release(123,1100,updates._signature(123,1100,'s','d'),'s','d')
        self.assertTrue(url.startswith('https://release-assets.'));self.assertIsNone(content);self.assertEqual(fetch.call_count,1)
    def test_untrusted_redirect_rejected(self):
        error=HTTPError('https://api.github.com/asset',302,'Found',{'Location':'https://evil.example/file'},io.BytesIO())
        with patch.object(updates.time,'time',return_value=1000),patch.object(updates,'_open',side_effect=error),self.assertRaises(ValueError):
            updates.download_release(123,1100,updates._signature(123,1100,'s','d'),'s','d')
    def test_streamed_binary_response(self):
        with patch.object(updates.time,'time',return_value=1000),patch.object(updates,'_open',return_value=io.BytesIO(b'PK-test-apk')):
            self.assertEqual(updates.download_release(123,1100,updates._signature(123,1100,'s','d'),'s','d'),(None,b'PK-test-apk'))
    def test_redirect_handler_never_follows(self):
        self.assertIsNone(updates.NoRedirect().redirect_request(None,None,302,'',{},'https://evil.example'))

class UpdateRouteTests(unittest.TestCase):
    def test_routes_require_device_or_valid_download_ticket(self):
        import ast
        from pathlib import Path
        from fastapi import FastAPI, Header, HTTPException
        from fastapi.responses import Response, RedirectResponse
        from fastapi.testclient import TestClient
        source=ast.parse((Path(__file__).parents[1]/'app.py').read_text())
        nodes=[n for n in source.body if isinstance(n,ast.FunctionDef) and n.name in ('device_app_update','device_app_download')]
        app=FastAPI()
        def require_device(token):
            if token!='test-device':raise HTTPException(401,'Unauthorized')
        scope=dict(app=app,Header=Header,HTTPException=HTTPException,Response=Response,RedirectResponse=RedirectResponse,os=os,SECRET='test-secret',DEVICE='test-device',require_device=require_device)
        exec(compile(ast.Module(body=nodes,type_ignores=[]),'update-routes','exec'),scope)
        with TestClient(app) as client,patch.dict(os.environ,{'OFFSET_ANDROID_RELEASE_URL':''}),patch.object(updates,'latest_update',return_value={'version_code':208,'download_url':'https://offset.example/ticket'}) as latest:
            self.assertEqual(client.get('/api/device/app-update').status_code,401)
            latest.assert_not_called()
            self.assertEqual(client.get('/api/device/app-update',headers={'X-Device-Token':'test-device'}).json()['version_code'],208)
            self.assertEqual(client.get('/api/device/app-download',params={'asset':123,'expires':1,'signature':'bad'}).status_code,403)

if __name__=='__main__':unittest.main()
