/* Only a public offline page is cached. Never cache authenticated requests or media. */
const CACHE='offset-private-shell-v1';
const OFFLINE='/static/offset-offline.html';
self.addEventListener('install', e=>{e.waitUntil(caches.open(CACHE).then(c=>c.add(OFFLINE)));self.skipWaiting();});
self.addEventListener('activate', e=>{e.waitUntil(caches.keys().then(keys=>Promise.all(keys.filter(k=>k.startsWith('offset-private-shell-')&&k!==CACHE).map(k=>caches.delete(k)))));self.clients.claim();});
self.addEventListener('fetch', e=>{if(e.request.mode!=='navigate'||new URL(e.request.url).origin!==self.location.origin)return;e.respondWith(fetch(e.request).catch(()=>caches.match(OFFLINE)));});
