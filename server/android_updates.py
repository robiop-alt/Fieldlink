"""Private GitHub release access; credentials stay on the OFFSET server."""
import hashlib
import hmac
import json
import os
import re
import time
from urllib.error import HTTPError
from urllib.parse import urlencode, urlsplit
from urllib.request import Request, HTTPRedirectHandler, build_opener

RELEASES='https://api.github.com/repos/robiop-alt/Fieldlink/releases'
MAX_APK=150*1024*1024
DOWNLOAD_TTL_SECONDS=3600

class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # Never forward the GitHub credential to the release storage host.
        return None


def _open(url, binary=False):
    headers={'Accept':'application/octet-stream' if binary else 'application/vnd.github+json',
             'User-Agent':'OFFSET-updates','X-GitHub-Api-Version':'2022-11-28'}
    token=os.getenv('OFFSET_GITHUB_RELEASE_TOKEN','').strip()
    if token:headers['Authorization']='Bearer '+token
    return build_opener(NoRedirect()).open(Request(url,headers=headers),timeout=12)


def _signature(asset, expiry, secret, device):
    return hmac.new(secret.encode(),f'apk:{asset}:{expiry}:{device}'.encode(),hashlib.sha256).hexdigest()


def latest_update(base_url, secret, device):
    with _open(RELEASES+'/latest') as response:
        release=json.loads(response.read(2*1024*1024))
    match=re.fullmatch(r'offset-android-(\d+)',release.get('tag_name',''))
    asset=next((a for a in release.get('assets',[]) if a.get('name') in ('OFFSET.apk','app-release.apk')),None)
    if not match or not asset or release.get('draft'):
        return {'version_code':0,'version_name':'','download_url':''}
    if os.getenv('OFFSET_GITHUB_RELEASE_TOKEN','').strip():
        asset_id=int(asset['id']);expiry=int(time.time())+DOWNLOAD_TTL_SECONDS
        query=urlencode({'asset':asset_id,'expires':expiry,'signature':_signature(asset_id,expiry,secret,device)})
        url=base_url.rstrip('/')+'/api/device/app-download?'+query
    else:
        url=asset.get('browser_download_url','')
    if not url.startswith('https://'):raise ValueError('Update requires HTTPS')
    return {'version_code':int(match.group(1)), 'version_name':release.get('name') or release['tag_name'],'download_url':url}


def download_release(asset, expires, signature, secret, device):
    now=int(time.time())
    if asset<=0 or not now<=expires<=now+DOWNLOAD_TTL_SECONDS or not hmac.compare_digest(signature,_signature(asset,expires,secret,device)):
        raise PermissionError('Expired or invalid update link. Check for updates again.')
    try:
        with _open(RELEASES+'/assets/'+str(asset),binary=True) as response:
            # GitHub may stream the asset instead of returning a signed redirect.
            content=response.read(MAX_APK+1)
            if len(content)>MAX_APK or not content.startswith(b'PK'):
                raise ValueError('Invalid APK response')
            return None,content
    except HTTPError as error:
        try:
            if error.code not in (301,302,303,307,308):raise ValueError('Release asset unavailable') from None
            location=error.headers.get('Location','')
            parsed=urlsplit(location)
            host=parsed.hostname or ''
            if parsed.scheme!='https' or parsed.username or parsed.password or not (host=='release-assets.githubusercontent.com' or host=='objects.githubusercontent.com' or host.endswith('.githubusercontent.com')):
                raise ValueError('Unexpected release storage host')
            return location,None
        finally:error.close()
