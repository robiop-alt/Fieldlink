"""Official Norwegian UAS geozone overlay for OFFSET Planner.
Source: Luftfartstilsynet / dronesoner.no (NLOD 2.0).
Planning reference only. NOTAM may be stale; do not use for flight authorization.
"""
import json
import logging
import re
import threading
import time
import urllib.request
from urllib.parse import urljoin

INDEX_URL = "https://dronesoner.no/"
MIRROR_URL = "https://raw.githubusercontent.com/mradeck/skycheck-project/master/data/uas-zones-no.json"
_LOG = logging.getLogger("offset.drone_zones")
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


def _validate_zones(data):
    # Dronesoner ED-269 is normally an array. Some third-party snapshots
    # wrap the array in an object; never accept a format we cannot interpret.
    if isinstance(data, dict):
        data = data.get("zones") or data.get("uasZones") or data.get("data") or data.get("features")
    if not isinstance(data, list):
        raise RuntimeError("Unsupported ED-269 dataset format")
    valid = [z for z in data if isinstance(z, dict) and isinstance(z.get("geometry"), list)]
    if not valid:
        raise RuntimeError("Downloaded file contains no usable ED-269 geometry")
    return valid


def get_zones():
    now = time.time()
    with _LOCK:
        if _CACHE["zones"] and now - _CACHE["loaded"] < CACHE_SECONDS:
            return _CACHE.copy()
        errors = []
        try:
            html = _download(INDEX_URL, 2_000_000).decode("utf-8")
            matches = sorted(set(DOWNLOAD_RE.findall(html)))
            if not matches:
                raise RuntimeError("No ED-269 download link found on source page")
            path = matches[-1]
            url = urljoin(INDEX_URL, path.lstrip("/"))
            zones = _validate_zones(json.loads(_download(url, MAX_DOWNLOAD_BYTES)))
            date = re.search(r"\d{4}-\d{2}-\d{2}", path).group(0)
            _CACHE.update({
                "loaded": now, "zones": zones, "url": url,
                "source_date": date, "stale": False,
                "origin": "official",
            })
            return _CACHE.copy()
        except Exception as exc:
            errors.append("Official source: " + repr(exc))
            _LOG.warning("Drone zones: official download failed: %s", exc)

        # Independently maintained weekly ED-269 snapshot sourced from
        # Luftfartstilsynet, not a real-time replacement for active NOTAM.
        try:
            zones = _validate_zones(json.loads(_download(MIRROR_URL, MAX_DOWNLOAD_BYTES)))
            _CACHE.update({
                "loaded": now, "zones": zones, "url": MIRROR_URL,
                "source_date": "weekly snapshot (date unverified)",
                "stale": True, "origin": "community mirror",
            })
            _LOG.warning("Drone zones: using weekly snapshot; not live official data")
            return _CACHE.copy()
        except Exception as exc:
            errors.append("Backup source: " + repr(exc))
            _LOG.error("Drone zones: backup download failed: %s", exc)

        # If an earlier successful dataset exists, display it with a conspicuous
        # staleness warning rather than claiming the airspace is clear.
        if _CACHE["zones"]:
            _CACHE["stale"] = True
            _CACHE["loaded"] = now
            return _CACHE.copy()
        raise RuntimeError("Failed to load geozone data: " + " | ".join(errors))


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


def _as_geojson(projection):
    """Convert both ED-269 polygons and circles into Leaflet-compatible GeoJSON."""
    if not isinstance(projection, dict):
        return None
    shape = projection.get("type")
    coords = projection.get("coordinates")
    if shape in ("Polygon", "MultiPolygon") and isinstance(coords, list):
        return {"type": shape, "coordinates": coords}
    if shape == "Circle":
        import math
        center = projection.get("center")
        radius = projection.get("radius")
        if (not isinstance(center, list) or len(center) != 2 or
                not all(isinstance(v, (float, int)) for v in center) or
                not isinstance(radius, (float, int)) or radius <= 0):
            return None
        lon, lat = center
        lat1, lon1 = math.radians(lat), math.radians(lon)
        delta = radius / 6371008.8
        ring = []
        for idx in range(49):
            bearing = idx * 2 * math.pi / 48
            lat2 = math.asin(math.sin(lat1) * math.cos(delta)
                             + math.cos(lat1) * math.sin(delta) * math.cos(bearing))
            lon2 = lon1 + math.atan2(math.sin(bearing) * math.sin(delta) * math.cos(lat1),
                                    math.cos(delta) - math.sin(lat1) * math.sin(lat2))
            ring.append([((math.degrees(lon2) + 540) % 360) - 180, math.degrees(lat2)])
        return {"type": "Polygon", "coordinates": [ring]}
    return None


def features_in_bbox(bbox, max_features=350):
    data = get_zones()
    features = []
    for z in data["zones"]:
        for geometry in z.get("geometry", []):
            projection = geometry.get("horizontalProjection") or {}
            normalized = _as_geojson(projection)
            if not normalized or not _touches_bbox(normalized["coordinates"], bbox):
                continue
            restriction = str(z.get("restriction") or "UNKNOWN")
            feature = {
                "type": "Feature",
                "geometry": normalized,
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
        "origin": data.get("origin", "unknown"),
        "source_date": data["source_date"],
        "stale": bool(data.get("stale")),
        "truncated": truncated,
        "notice": "Planning only. Always verify AIP, active NOTAM (ippc.no), local restrictions and permissions before flying.",
    }
