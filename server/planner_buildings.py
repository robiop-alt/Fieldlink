"""Small authenticated OFFSET building-footprint proxy for 3D shot previsualisation.

Avoid browser CORS failures, trim OSM payloads and cache repeat requests.
Voluntary OSM height tags are approximate, never obstacle-clearance data.
"""
from collections import OrderedDict
from threading import Lock
from urllib.parse import urlencode
import json
import math
import time
import urllib.request

_CACHE = OrderedDict()
_LOCK = Lock()
_TTL = 60 * 60 * 6
_MAX = 64
_PROVIDERS = (
    "https://overpass.private.coffee/api/interpreter",
    "https://overpass-api.de/api/interpreter",
)


def buildings_near(lat: float, lon: float, radius: int = 500):
    if not (math.isfinite(lat) and math.isfinite(lon)
            and -90 <= lat <= 90 and -180 <= lon <= 180
            and 150 <= radius <= 750):
        raise ValueError("Invalid location or search radius")
    key = (round(lat, 3), round(lon, 3), int(radius))
    now = time.monotonic()
    with _LOCK:
        cached = _CACHE.get(key)
        if cached and now - cached[0] < _TTL:
            _CACHE.move_to_end(key)
            return dict(cached[1], cached=True)
    query = (
        '[out:json][timeout:16];'
        '(way["building"](around:%d,%.6f,%.6f);'
        'way["building:part"](around:%d,%.6f,%.6f););'
        'out geom;'
    ) % (radius, lat, lon, radius, lat, lon)
    last_error = None
    for endpoint in _PROVIDERS:
        request = urllib.request.Request(
            endpoint + "?" + urlencode({"data": query}),
            headers={
                "User-Agent": "OFFSET-DronePlanner/1.0 (user-requested 3D shot preview)",
                "Accept": "application/json",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                payload = response.read(5_000_000)
            body = json.loads(payload)
            if not isinstance(body.get("elements"), list):
                raise ValueError("Invalid building response")
            results = []
            for obj in body["elements"]:
                geometry = obj.get("geometry")
                if obj.get("type") != "way" or not isinstance(geometry, list):
                    continue
                points = [
                    {"lon": pt["lon"], "lat": pt["lat"]}
                    for pt in geometry
                    if isinstance(pt, dict)
                    and isinstance(pt.get("lat"), (int, float))
                    and isinstance(pt.get("lon"), (int, float))
                    and math.isfinite(pt["lat"]) and math.isfinite(pt["lon"])
                ][:400]
                if len(points) < 3:
                    continue
                tags = obj.get("tags") or {}
                tags = {k: str(tags[k])[:40] for k in
                        ("height", "building:levels", "min_height",
                         "building:min_level", "building", "building:part")
                        if k in tags}
                results.append({"geometry": points, "tags": tags})
                if len(results) >= 900:
                    break
            result = {
                "elements": results, "count": len(results),
                "source": "OpenStreetMap via Overpass", "cached": False,
                "notice": "Approximate models. Never use for collision avoidance.",
            }
            with _LOCK:
                _CACHE[key] = (now, result)
                _CACHE.move_to_end(key)
                while len(_CACHE) > _MAX:
                    _CACHE.popitem(last=False)
            return result
        except Exception as exc:
            last_error = exc
    raise RuntimeError("Building data provider unavailable") from last_error
