"""Public SSB WFS geometry, clipped server-side for the planner's viewport.

Statistics are not an operational authorization or a count of people present.
No coordinates are sent until the user enables the map layer.
"""
from collections import OrderedDict
from concurrent.futures import ThreadPoolExecutor
import copy
import math
import re
import threading
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

from shapely.geometry import Polygon, box, mapping
from shapely.ops import transform

BASE='https://kart.ssb.no/api/mapserver/v1/wfs/'
SOURCE='https://kart.ssb.no/'
GML='{http://www.opengis.net/gml/3.2}'
WFS='{http://www.opengis.net/wfs/2.0}'
CRS='urn:ogc:def:crs:EPSG::3857'
R=6378137.0
LOCK=threading.RLock()
CACHE=OrderedDict()
YEARS={}
LIMIT=3000

def _read(endpoint, params, maximum=32*1024*1024):
    url=BASE+endpoint+'?'+urllib.parse.urlencode(params)
    request=urllib.request.Request(url,headers={'User-Agent':'OFFSET-Planner/1.0 (SSB planning reference)','Accept':'application/xml'})
    with urllib.request.urlopen(request,timeout=25) as response:
        raw=response.read(maximum+1)
    if len(raw)>maximum:
        raise ValueError('SSB response too large')
    return ET.fromstring(raw)

def _latest(endpoint, prefix):
    with LOCK:
        cached=YEARS.get(endpoint)
        if cached and time.monotonic()-cached[0]<86400:
            return cached[1]
    root=_read(endpoint,{'service':'WFS','version':'2.0.0','request':'GetCapabilities'},1024*1024)
    years=[]
    for element in root.iter():
        if element.tag.split('}')[-1]=='Name':
            match=re.fullmatch(r'ms:'+re.escape(prefix)+r'_(20\d\d)',element.text or '')
            if match:years.append(int(match.group(1)))
    if not years:raise ValueError('No SSB dataset advertised')
    year=max(years)
    with LOCK:YEARS[endpoint]=(time.monotonic(),year)
    return year

def _mercator(lon,lat):
    return R*math.radians(lon),R*math.log(math.tan(math.pi/4+math.radians(lat)/2))

def _lonlat(x,y,z=None):
    return math.degrees(x/R),math.degrees(2*math.atan(math.exp(y/R))-math.pi/2)

def _ring(element):
    node=element.find('.//'+GML+'posList')
    if node is None or node.get('srsDimension','2')!='2':raise ValueError('Unexpected SSB coordinate format')
    nums=[float(n) for n in (node.text or '').split()]
    if len(nums)<8 or len(nums)%2 or not all(math.isfinite(n) for n in nums):raise ValueError('Invalid SSB ring')
    points=list(zip(nums[::2],nums[1::2]))
    if points[0]!=points[-1]:raise ValueError('Unclosed SSB ring')
    return points

def parse_features(root, kind, year, bounds):
    """GML 3.2 with explicit EPSG:3857 axes; never guess EPSG:4326 order."""
    if root.tag!=WFS+'FeatureCollection':raise ValueError('Unexpected SSB response')
    south,west,north,east=bounds
    xmin,ymin=_mercator(west,south);xmax,ymax=_mercator(east,north)
    viewport=box(xmin,ymin,xmax,ymax)
    features=[]
    for member in root.findall(WFS+'member'):
        if len(member)!=1:raise ValueError('Invalid SSB feature')
        feature=member[0]
        props={child.tag.split('}')[-1]:child.text for child in feature if len(child)==0}
        surfaces=feature.findall('.//'+GML+'MultiSurface')
        if not surfaces:surfaces=feature.findall('.//'+GML+'Polygon')
        if not surfaces:raise ValueError('No SSB polygon geometry')
        for surface in surfaces:
            if surface.get('srsName')!=CRS:raise ValueError('Unexpected SSB coordinate system')
            polygons=[surface] if surface.tag==GML+'Polygon' else surface.findall('.//'+GML+'Polygon')
            for polygon in polygons:
                exterior=polygon.find(GML+'exterior')
                if exterior is None:raise ValueError('SSB polygon has no exterior')
                shape=Polygon(_ring(exterior),[_ring(h) for h in polygon.findall(GML+'interior')])
                if not shape.is_valid:raise ValueError('Invalid SSB polygon')
                clipped=shape.intersection(viewport)
                if clipped.is_empty or clipped.area==0:continue
                if clipped.geom_type not in ('Polygon','MultiPolygon'):raise ValueError('Unexpected clipped SSB geometry')
                geometry=mapping(transform(_lonlat,clipped))
                if kind=='settlement':
                    properties={'kind':kind,'year':year,'name':props.get('tettstedsnavn') or 'SSB settlement'}
                else:
                    # Confidential/missing values remain unknown, never zero.
                    value=props.get('pop_tot')
                    try:residents=int(value) if value is not None else None
                    except (ValueError,TypeError):residents=None
                    if residents is not None and residents<0:residents=None
                    properties={'kind':kind,'year':year,'residents':residents,'density':residents*16 if residents is not None else None,'cell_m':250}
                features.append({'type':'Feature','geometry':geometry,'properties':properties})
    matched=root.get('numberMatched','unknown')
    returned=root.get('numberReturned')
    complete=(returned is not None and returned.isdigit() and int(returned)==len(root.findall(WFS+'member'))
              and int(returned)<=LIMIT and not root.get('next')
              and (matched=='unknown' or matched.isdigit() and int(matched)==int(returned)))
    return features,complete

def _fetch_kind(kind,bounds):
    endpoint,prefix=('tettsteder','tettsted') if kind=='settlement' else ('befolkning_paa_rutenett','befolkning_250m')
    year=_latest(endpoint,prefix)
    south,west,north,east=bounds
    xmin,ymin=_mercator(west,south);xmax,ymax=_mercator(east,north)
    root=_read(endpoint,{'service':'WFS','version':'2.0.0','request':'GetFeature','typeNames':f'ms:{prefix}_{year}',
        'srsName':CRS,'bbox':f'{xmin},{ymin},{xmax},{ymax},{CRS}','count':str(LIMIT+1)})
    features,complete=parse_features(root,kind,year,bounds)
    if not complete:
        # Incomplete layers cannot support an outside-settlement conclusion.
        raise ValueError('Incomplete SSB data; zoom closer')
    return features,year

def features_in_bbox(bounds):
    south,west,north,east=bounds
    if not all(math.isfinite(v) for v in bounds) or not (57<=south<north<=72 and 3<=west<east<=33):
        raise ValueError('SSB layer currently covers mainland Norway')
    if north-south>.25 or east-west>.5:raise ValueError('Zoom closer to view SSB data')
    # Reuse small, outward-rounded viewports while keeping coverage explicit.
    step=.002
    key=(math.floor(south/step)*step,math.floor(west/step)*step,math.ceil(north/step)*step,math.ceil(east/step)*step)
    with LOCK:
        cached=CACHE.get(key)
        if cached and time.monotonic()-cached[0]<3600:
            CACHE.move_to_end(key)
            return copy.deepcopy(cached[1])
    with ThreadPoolExecutor(max_workers=2) as pool:
        results=list(pool.map(lambda kind:_fetch_kind(kind,key),['settlement','population']))
    collection={'type':'FeatureCollection','features':results[0][0]+results[1][0],
        'coverage':list(key),'years':{'settlements':results[0][1],'population':results[1][1]},
        'source':'Statistics Norway (SSB)','source_url':SOURCE,'complete':True,'cell_m':250}
    with LOCK:
        CACHE[key]=(time.monotonic(),collection);CACHE.move_to_end(key)
        while len(CACHE)>12:CACHE.popitem(last=False)
    return copy.deepcopy(collection)
