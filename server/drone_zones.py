"""Official Norwegian UAS geozone overlay for OFFSET Planner.
Source: Luftfartstilsynet / dronesoner.no (NLOD 2.0).
Planning reference only. NOTAM may be stale; do not use for flight authorization.
"""
import json
import re
import threading
import time
import urllib.request
from urllib.parse import urljoin

INDEX_URL = "https://dronesoner.no/"
DOWNLOAD_RE = re.compile(r"(?:/)?Downloads/NOR_ED269_compliant_geozones_\d{4}-\d{2}-\d{2}\.json")
_LOCK = threading.RLock()
_CACHE = {"loaded": 0, "zones": [], "url": "", "source_date": ""}
MAX_DOWNLOAD_BYTES = 30_000_000
CACHE_SECONDS = 6 * 3600


def _download(url, max_size):
    request = urllib.request.Request(url, headers={
        "User-Agent": "OFFSET-Planner/1.0 (drone geozone planning; source dronesoner.no)",
        "Accept": "application/json,text/html;q=0.8",
    })
    with urllib.request.urlopen(request, timeout=15) as response:
        raw = response.read(max_size + 1)
    if len(raw) > max_size:
        raise RuntimeError("Geozone download exceeds size limit")
    return raw


def get_zones():
    now = time.time()
    with _LOCK:
        if _CACHE["zones"] and now - _CACHE["loaded"] < CACHE_SECONDS:
            return _CACHE.copy()
        try:
            html = _download(INDEX_URL, 2_000_000).decode("utf-8")
            matches = sorted(set(DOWNLOAD_RE.findall(html)))
            if not matches:
                raise RuntimeError("No current ED-269 download found")
            path = matches[-1]
            url = urljoin(INDEX_URL, path.lstrip("/"))
            data = json.loads(_download(url, MAX_DOWNLOAD_BYTES))
            if not isinstance(data, list):
                raise RuntimeError("Unexpected ED-269 format")
            valid = [z for z in data if isinstance(z, dict) and isinstance(z.get("geometry"), list)]
            if not valid:
                raise RuntimeError("No geozones available")
            _CACHE.update({
                "loaded": now, "zones": valid, "url": url,
                "source_date": re.search(r"\d{4}-\d{2}-\d{2}", path).group(0),
            })
        except Exception:
            # Old zones must not silently masquerade as live data.
            if not _CACHE["zones"]:
                raise
            _CACHE["stale"] = True
        else:
            _CACHE["stale"] = False
        return _CACHE.copy()


def _flatten_coordinates(value):
    if isinstance(value, (list, tuple)):
        if len(value) >= 2 and all(isinstance(v, (int, float)) for v in value[:2]):
            yield value[:2]
        else:
            for item in value:
                yield from _flatten_coordinates(item)


def _touches_bbox(coords, bbox):
    south, west, north, east = bbox
    west_bound, south_bound = float("inf"), float("inf")
    east_bound, north_bound = float("-inf"), float("-inf")
    for lng, lat in _flatten_coordinates(coords):
        west_bound = min(west_bound, lng)
        east_bound = max(east_bound, lng)
        south_bound = min(south_bound, lat)
        north_bound = max(north_bound, lat)
    return south_bound <= north and north_bound >= south and west_bound <= east and east_bound >= west


def features_in_bbox(bbox, max_features=350):
    data = get_zones()
    features = []
    for z in data["zones"]:
        for geometry in z.get("geometry", []):
            projection = geometry.get("horizontalProjection") or {}
            shape = projection.get("type")
            coords = projection.get("coordinates")
            if shape not in ("Polygon", "MultiPolygon") or not coords:
                continue
            if not _touches_bbox(coords, bbox):
                continue
            restriction = str(z.get("restriction") or "UNKNOWN")
            feature = {
                "type": "Feature",
                "geometry": {"type": shape, "coordinates": coords},
                "properties": {
                    "identifier": str(z.get("identifier") or "")[:100],
                    "name": str(z.get("name") or "Unnamed zone")[:200],
                    "restriction": restriction[:50],
                    "reason": [str(x)[:60] for x in (z.get("reason") or [])][:8],
                    "message": str(z.get("message") or "")[:600],
                    "lowerLimit": geometry.get("lowerLimit"),
                    "upperLimit": geometry.get("upperLimit"),
                    "lowerVerticalReference": str(geometry.get("lowerVerticalReference") or "")[:20],
                    "upperVerticalReference": str(geometry.get("upperVerticalReference") or "")[:20],
                },
            }
            features.append(feature)
            if len(features) >= max_features:
                return _result(features, data, True)
    return _result(features, data, False)


def _result(features, data, truncated):
    return {
        "type": "FeatureCollection", "features": features,
        "source": "Luftfartstilsynet / dronesoner.no",
        "source_date": data["source_date"],
        "stale": bool(data.get("stale")),
        "truncated": truncated,
        "notice": "Planning only. Always verify AIP, active NOTAM (ippc.no), local restrictions and permissions before flying.",
    }
