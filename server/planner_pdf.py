"""Reliable downloadable OFFSET Drone Planner PDF export.
All rendering runs server-side, so Safari and iPhone need no print support.
Includes real street basemap tiles with OpenStreetMap attribution when available.
"""
import io
import math
import logging
import urllib.request
import concurrent.futures
import textwrap
from datetime import datetime, timezone, timedelta
from zoneinfo import ZoneInfo
from xml.sax.saxutils import escape

from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.utils import simpleSplit
from reportlab.pdfbase.pdfmetrics import stringWidth
from reportlab.pdfgen import canvas
from reportlab.lib.utils import ImageReader
from PIL import Image, ImageOps

INK=colors.HexColor("#15231d")
MUTED=colors.HexColor("#69766e")
GREEN=colors.HexColor("#339e60")
LIGHT_GREEN=colors.HexColor("#d8eddf")
RED=colors.HexColor("#c63d4a")
GREY=colors.HexColor("#e9eeeb")
PW,PH=A4
M=43


def _safe(value, limit=300):
    return str(value or "").replace("\n"," ").replace("\r"," ")[:limit]


def _coord(point):
    return (isinstance(point,list) and len(point)==2
            and all(isinstance(v,(int,float)) and math.isfinite(v) for v in point)
            and -90<=point[0]<=90 and -180<=point[1]<=180)


def _line(c,text,x,y,size=10,color=INK,bold=False,width=None):
    c.setFont("Helvetica-Bold" if bold else "Helvetica",size)
    c.setFillColor(color)
    if width:
        for part in simpleSplit(_safe(text,1000), "Helvetica",size,width):
            c.drawString(x,y,part)
            y-=size+4
    else:
        c.drawString(x,y,_safe(text,130))
        y-=size+4
    return y


def _page(c, title, heading):
    c.setFillColor(colors.HexColor("#101b17"))
    c.rect(0,PH-84,PW,84,stroke=0,fill=1)
    c.setFillColor(colors.HexColor("#88d6a0"))
    c.setFont("Helvetica-Bold",12)
    c.drawString(M,PH-37,"OFFSET.")
    c.setFont("Helvetica",9)
    c.setFillColor(colors.white)
    c.drawString(M,PH-57,heading)
    c.setStrokeColor(colors.HexColor("#d6ddd8"))
    c.line(M,40,PW-M,40)
    c.setFillColor(MUTED)
    c.setFont("Helvetica",8)
    c.drawString(M,27,"Production planning reference only. Check airspace, permits and active NOTAM.")
    c.drawRightString(PW-M,27,"OFFSET Drone Planner")
    c.setFillColor(INK)
    c.setFont("Helvetica-Bold",16)
    c.drawString(M,PH-112,_safe(title,70))
    return PH-136



# Tiles are requested on-demand only for PDF exports, never for prefetching.
# © OpenStreetMap contributors (ODbL). OSM maps are not flight clearance.
_TILE_SIZE=256
_TILE_CACHE={}
_TILE_LOCK=__import__("threading").Lock()
_LOG=logging.getLogger("offset.planner_pdf")


def _mercator(p, zoom):
    lat=math.radians(max(-85.05112878,min(85.05112878,p[0])))
    scale=_TILE_SIZE*(2**zoom)
    return ((p[1]+180)/360*scale,
            (1-math.asinh(math.tan(lat))/math.pi)/2*scale)


def _fit_map(points, pixel_width, pixel_height):
    valid=[p for p in points if _coord(p)]
    if not valid:return None
    for zoom in range(18,6,-1):
        projected=[_mercator(p,zoom) for p in valid]
        xs=[p[0] for p in projected];ys=[p[1] for p in projected]
        if max(xs)-min(xs)<=pixel_width*.72 and max(ys)-min(ys)<=pixel_height*.66:
            return zoom,(max(xs)+min(xs))/2,(max(ys)+min(ys))/2
    projected=[_mercator(p,7) for p in valid]
    return 7,(max(p[0] for p in projected)+min(p[0] for p in projected))/2,(max(p[1] for p in projected)+min(p[1] for p in projected))/2


def _get_tile(zoom, tx, ty):
    if not(0<=tx<2**zoom and 0<=ty<2**zoom):
        return None
    key=(zoom,tx,ty)
    with _TILE_LOCK:
        cached=_TILE_CACHE.get(key)
        if cached is not None:
            return cached.copy()
    url=f"https://tile.openstreetmap.org/{zoom}/{tx}/{ty}.png"
    request=urllib.request.Request(url,headers={
        "User-Agent":"OFFSET-Planner/1.0 (small user-initiated PDF map export; contact via service site)",
        "Accept":"image/png",
    })
    try:
        with urllib.request.urlopen(request,timeout=5) as response:
            content=response.read(350_000)
        tile=Image.open(io.BytesIO(content)).convert("RGB")
        if tile.size!=(_TILE_SIZE,_TILE_SIZE):
            return None
        with _TILE_LOCK:
            if len(_TILE_CACHE)>120:
                _TILE_CACHE.clear()
            _TILE_CACHE[key]=tile.copy()
        return tile
    except Exception as exc:
        _LOG.warning("PDF map tile unavailable z=%d x=%d y=%d: %s",zoom,tx,ty,exc)
        return None


def _basemap(zoom,cx,cy,width,height):
    image=Image.new("RGB",(width,height),(239,242,239))
    left=cx-width/2;top=cy-height/2
    x_start=math.floor(left/_TILE_SIZE);x_end=math.floor((left+width-1)/_TILE_SIZE)
    y_start=math.floor(top/_TILE_SIZE);y_end=math.floor((top+height-1)/_TILE_SIZE)
    locations=[(xx,yy) for xx in range(x_start,x_end+1) for yy in range(y_start,y_end+1)]
    if len(locations)>20:return None
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as executor:
        results=list(executor.map(lambda q:_get_tile(zoom,*q),locations))
    present=sum(tile is not None for tile in results)
    if not present:return None
    for (tx,ty),tile in zip(locations,results):
        if tile is None:continue
        image.paste(tile,(round(tx*_TILE_SIZE-left),round(ty*_TILE_SIZE-top)))
    # The PDF map matches the Planner's calmer monochrome styling.
    gray=ImageOps.grayscale(image).convert("RGB")
    return Image.blend(image,gray,.88)



# NOAA-style approximate solar ephemeris. Sunrise/set use -0.833 degree
# elevation; golden-hour thresholds are -0.833 to +6 degrees.
def _solar_timezone(point):
    # The Planner currently records coordinates, not an IANA timezone.
    # Norway is identified explicitly; elsewhere use UTC instead of
    # inventing local clock times. The PDF labels the chosen timezone.
    if point and 57 <= point[0] <= 72 and 4 <= point[1] <= 32:
        return ZoneInfo("Europe/Oslo"), "Europe/Oslo"
    return timezone.utc, "UTC"


def _sun_at(point, moment):
    day_of_year = moment.timetuple().tm_yday
    days = 366 if (moment.year % 4 == 0 and
                   (moment.year % 100 != 0 or moment.year % 400 == 0)) else 365
    local_hour = moment.hour + moment.minute / 60 + moment.second / 3600
    gamma = 2 * math.pi / days * (day_of_year - 1 + (local_hour - 12) / 24)
    equation = 229.18 * (0.000075 + 0.001868 * math.cos(gamma)
               - 0.032077 * math.sin(gamma) - 0.014615 * math.cos(2*gamma)
               - 0.040849 * math.sin(2*gamma))
    decl = (0.006918 - 0.399912 * math.cos(gamma)
            + 0.070257 * math.sin(gamma)
            - 0.006758 * math.cos(2*gamma) + 0.000907 * math.sin(2*gamma)
            - 0.002697 * math.cos(3*gamma) + 0.00148 * math.sin(3*gamma))
    offset_hours = (moment.utcoffset() or timedelta()).total_seconds() / 3600
    hour_angle = math.radians((local_hour * 60 + equation +
                                4 * point[1] - 60 * offset_hours) / 4 - 180)
    latitude = math.radians(point[0])
    sine = (math.sin(latitude)*math.sin(decl) +
            math.cos(latitude)*math.cos(decl)*math.cos(hour_angle))
    elevation = math.degrees(math.asin(max(-1, min(1, sine))))
    azimuth = (math.degrees(math.atan2(
        math.sin(hour_angle),
        math.cos(hour_angle)*math.sin(latitude) -
        math.tan(decl)*math.cos(latitude))) + 180) % 360
    return elevation, azimuth


def _sun_events(shot, location):
    if not _coord(location):
        return None
    value = shot.get("date")
    if not isinstance(value, str) or not value:
        return None
    try:
        date = datetime.strptime(value, "%Y-%m-%d").date()
        tz, tz_label = _solar_timezone(location)
        midnight = datetime(date.year, date.month, date.day, tzinfo=tz)
    except (ValueError, OverflowError):
        return None
    # Resolve all crossings with 5 minute sampling and linear interpolation.
    # Use timedelta arithmetic so hh:60 is never an invalid time string.
    crossings = {"sunrise":None, "sunset":None, "golden_am":None, "golden_pm":None}
    previous = _sun_at(location, midnight)[0]
    for minute in range(5, 1441, 5):
        at = midnight + timedelta(minutes=minute)
        current = _sun_at(location, at)[0]
        for key, level, upward in (
            ("sunrise", -0.833, True),
            ("sunset", -0.833, False),
            ("golden_am", 6, True),
            ("golden_pm", 6, False),
        ):
            a,b = previous-level,current-level
            if (upward and a < 0 <= b) or (not upward and a > 0 >= b):
                fraction = -a/(b-a) if b != a else 0
                transition = midnight + timedelta(minutes=minute-5+5*fraction)
                crossings[key] = {
                    "time":transition.strftime("%H:%M"),
                    "azimuth":_sun_at(location, transition)[1],
                }
        previous=current
    return {"timezone":tz_label, **crossings}


def _draw_sun_rays(c, solar, anchor, project, x, top, w, h):
    if not solar or not _coord(anchor):
        return
    origin_x,origin_y = project(anchor)
    # Vector projection to the edge of the map: direction only, not sun
    # elevation, obstacle clearance, or actual shadow length.
    c.saveState()
    clipping=c.beginPath()
    clipping.roundRect(x,top-h,w,h,7)
    c.clipPath(clipping,stroke=0,fill=0)
    rays=(("sunrise",colors.HexColor("#d59b30"),"RISE"),
          ("sunset",colors.HexColor("#bc6060"),"SET"))
    for key,color,label in rays:
        event=solar.get(key)
        if event is None:continue
        a=math.radians(event["azimuth"])
        dx,dy=math.sin(a),math.cos(a)
        length=max(w,h)*1.7
        end_x,end_y=origin_x+dx*length,origin_y+dy*length
        c.setStrokeColor(color);c.setLineWidth(2)
        c.setDash(7,4 if key=="sunrise" else 7)
        c.line(origin_x,origin_y,end_x,end_y)
        c.setDash()
        # Label is inset so it remains visible at the map edge.
        # Find first edge intersection in the ray direction.
        factors=[]
        if dx>0:factors.append((x+w-origin_x)/dx)
        elif dx<0:factors.append((x-origin_x)/dx)
        if dy>0:factors.append((top-origin_y)/dy)
        elif dy<0:factors.append((top-h-origin_y)/dy)
        positive=[v for v in factors if v>0]
        if positive:
            t=max(0,min(positive)-18)
            px,py=origin_x+dx*t,origin_y+dy*t
            c.setFont("Helvetica-Bold",8)
            c.setFillColor(color)
            c.drawCentredString(px,py+4,label)
    c.restoreState()


def _sun_summary(c, events, x, y, w):
    c.setFillColor(colors.HexColor("#f2f5f0"))
    c.roundRect(x,y-49,w,48,5,stroke=0,fill=1)
    c.setFillColor(INK);c.setFont("Helvetica-Bold",9)
    c.drawString(x+10,y-12,"SUN POSITION")
    c.setFont("Helvetica",7);c.setFillColor(MUTED)
    c.drawRightString(x+w-10,y-12,events["timezone"]+" · approximate")
    def item(key):
        event=events.get(key)
        return (event["time"]+" / "+str(round(event["azimuth"]))+" deg") if event else "Not occurring"
    c.setFont("Helvetica",8);c.setFillColor(INK)
    c.drawString(x+10,y-25,"Sunrise: "+item("sunrise"))
    c.drawString(x+w/2+8,y-25,"Sunset: "+item("sunset"))
    def time(key):
        return events[key]["time"] if events.get(key) else "--:--"
    morning=time("sunrise")+" - "+time("golden_am") if events.get("sunrise") else "Not occurring"
    evening=time("golden_pm")+" - "+time("sunset") if events.get("sunset") else "Not occurring"
    c.setFont("Helvetica",8);c.setFillColor(MUTED)
    c.drawString(x+10,y-39,"Golden AM: "+morning)
    c.drawString(x+w/2+8,y-39,"Golden PM: "+evening)


def _closure_path(route, cones, mode='cinematic'):
    """Sample the same Hermite centerline/AGL interpolation as the 3D preview."""
    indexed=[(i,p) for i,p in enumerate(route) if _coord(p)]
    if not indexed:return []
    points=[p for _,p in indexed]
    heights=[]
    for i,_ in indexed:
        cone=cones[i] if i<len(cones) and isinstance(cones[i],dict) else {}
        try:h=float(cone.get('altitudeAGL',30))
        except (TypeError,ValueError):h=30
        heights.append(min(500,max(2,h)) if math.isfinite(h) else 30)
    if len(points)==1:return [(points[0],heights[0])]
    def distance(a,b):
        lat1,lat2=map(math.radians,(a[0],b[0]));dlat=lat2-lat1;dlon=math.radians(b[1]-a[1])
        v=math.sin(dlat/2)**2+math.cos(lat1)*math.cos(lat2)*math.sin(dlon/2)**2
        return 6371008.8*2*math.asin(math.sqrt(min(1,v)))
    legs=[max(.001,distance(a,b)) for a,b in zip(points,points[1:])]
    amount=0 if mode=='direct' else .7 if mode=='smooth' else 1
    def interpolate(values,i,t):
        a,b=values[i:i+2]
        if amount==0:return a+(b-a)*t
        length=legs[i];previous=legs[i-1] if i else length
        following=legs[i+1] if i+1<len(legs) else length
        m0=((b-values[i-1])/(previous+length) if i else (b-a)/length)*length*amount
        m1=((values[i+2]-a)/(length+following) if i+2<len(values) else (b-a)/length)*length*amount
        return (2*t**3-3*t*t+1)*a+(t**3-2*t*t+t)*m0+(-2*t**3+3*t*t)*b+(t**3-t*t)*m1
    lats=[p[0] for p in points];lons=[p[1] for p in points];result=[]
    for i in range(len(legs)):
        for k in range(32 if amount else 1):
            t=k/(32 if amount else 1)
            result.append(([interpolate(lats,i,t),interpolate(lons,i,t)],max(2,interpolate(heights,i,t))))
    result.append((points[-1],heights[-1]));return result


def _closure_bounds(path):
    bounds=[]
    for (lat,lon),radius in path:
        dy=radius/111195;dx=dy/max(.01,math.cos(math.radians(lat)))
        bounds.extend([[lat-dy,lon-dx],[lat+dy,lon+dx]])
    return bounds


def _closure_overlay(path,zoom,cx,cy,width,height):
    """Raster union prevents dark overlaps; tangent joins leave no gaps."""
    from PIL import ImageDraw, ImageFilter, ImageChops
    mask=Image.new('L',(width,height),0);draw=ImageDraw.Draw(mask)
    circles=[]
    for point,radius in path:
        xp,yp=_mercator(point,zoom)
        r=radius/(40075016.686*max(.01,math.cos(math.radians(point[0])))/(256*2**zoom))
        circles.append((xp-cx+width/2,yp-cy+height/2,r))
    for a,b in zip(circles,circles[1:]):
        ax,ay,ar=a;bx,by,br=b;dx,dy=bx-ax,by-ay;d=math.hypot(dx,dy)
        if d<.0001 or abs(br-ar)>=d:continue
        ux,uy=dx/d,dy/d;q=(ar-br)/d;s=math.sqrt(max(0,1-q*q))
        nx1,ny1=q*ux-s*uy,q*uy+s*ux;nx2,ny2=q*ux+s*uy,q*uy-s*ux
        draw.polygon([(ax+ar*nx1,ay+ar*ny1),(bx+br*nx1,by+br*ny1),
                      (bx+br*nx2,by+br*ny2),(ax+ar*nx2,ay+ar*ny2)],fill=255)
    for xp,yp,r in circles:draw.ellipse((xp-r,yp-r,xp+r,yp+r),fill=255)
    edge=ImageChops.subtract(mask.filter(ImageFilter.MaxFilter(3)),mask.filter(ImageFilter.MinFilter(3)))
    fill=Image.new('RGBA',(width,height),(237,146,61,0));fill.putalpha(mask.point(lambda v:round(v*.20)))
    outline=Image.new('RGBA',(width,height),(218,113,22,0));outline.putalpha(edge.point(lambda v:round(v*.85)))
    return Image.alpha_composite(fill,outline)

def _map(c, route, target, cones, marshals, x, top, w, h, solar=None, sketches=None, text_boxes=None, closure_path=None):
    """Render a real street basemap, then precisely aligned vector map annotations."""
    drawings=[[p for p in line if _coord(p)] for line in (sketches or []) if isinstance(line,list)]
    boxes=[box for box in (text_boxes or []) if isinstance(box,dict) and _coord(box.get('position'))]
    valid=[p for p in route if _coord(p)]
    pts=valid+([target] if _coord(target) else [])+[
        m["position"] for m in marshals
        if isinstance(m,dict) and _coord(m.get("position"))
    ]+[p for line in drawings for p in line]+[box['position'] for box in boxes]+_closure_bounds(closure_path or [])
    c.setFillColor(colors.HexColor("#f5f7f5"))
    c.setStrokeColor(colors.HexColor("#d6e2d9"))
    c.roundRect(x,top-h,w,h,7,stroke=1,fill=1)
    if not pts:
        _line(c,"No mapped positions in this shot",x+12,top-24,9,MUTED)
        return
    # Render 1.6 pixels per PDF point for legible map labels.
    width=round(w*1.6);height=round(h*1.6)
    chosen=_fit_map(pts,width,height)
    zoom,cx,cy=chosen
    scale=w/width
    def project(point):
        xp,yp=_mercator(point,zoom)
        return x+w/2+(xp-cx)*scale,top-h/2-(yp-cy)*scale

    backdrop=_basemap(zoom,cx,cy,width,height)
    if backdrop is not None:
        c.saveState()
        clip=c.beginPath()
        clip.roundRect(x,top-h,w,h,7)
        c.clipPath(clip,stroke=0,fill=0)
        c.drawImage(ImageReader(backdrop),x,top-h,w,h,mask="auto")
        c.restoreState()
    else:
        # The actual route remains usable even when map tile service is unavailable.
        c.setStrokeColor(colors.HexColor("#e2e8e2"))
        c.setLineWidth(.45)
        for i in range(1,5):
            c.line(x+i*w/5,top-h,x+i*w/5,top)
            c.line(x,top-i*h/5,x+w,top-i*h/5)

    if closure_path:
        c.drawImage(ImageReader(_closure_overlay(closure_path,zoom,cx,cy,width,height)),x,top-h,w,h,mask='auto')

    if solar:
        _draw_sun_rays(c,solar,valid[0] if valid else pts[0],project,x,top,w,h)

    c.setStrokeColor(RED);c.setLineWidth(2)
    for line in drawings:
        if len(line)<2:continue
        path=c.beginPath();path.moveTo(*project(line[0]))
        for point in line[1:]:path.lineTo(*project(point))
        c.drawPath(path)
    for box in boxes:
        lines=simpleSplit(_safe(box.get('text'),300),'Helvetica',8,140)[:8]
        if not lines:continue
        xp,yp=project(box['position']);bw=150;bh=10+10*len(lines)
        xp=max(x+3,min(x+w-bw-3,xp));yp=max(top-h+bh+20,min(top-3,yp))
        c.setFillColor(colors.white);c.setStrokeColor(RED);c.setLineWidth(.7)
        c.roundRect(xp,yp-bh,bw,bh,3,fill=1,stroke=1)
        c.setFillColor(RED);c.setFont('Helvetica',8)
        for i,line in enumerate(lines):c.drawString(xp+5,yp-12-i*10,line)

    if len(valid)>1:
        c.setStrokeColor(GREEN);c.setLineWidth(2.5)
        points=[project(p) for p in valid]
        path=c.beginPath();path.moveTo(*points[0])
        for p in points[1:]:path.lineTo(*p)
        c.drawPath(path)
    for i,p in enumerate(valid):
        xp,yp=project(p)
        if i<len(cones):
            cone=cones[i] if isinstance(cones[i],dict) else {}
            if cone.get("visible",True):
                try:
                    bearing=float(cone.get("bearing",0) or 0)
                    if cone.get("mode")=="target" and _coord(target):
                        latD=target[0]-p[0]
                        lonD=(target[1]-p[1])*math.cos(math.radians(p[0]))
                        bearing=math.degrees(math.atan2(lonD,latD))
                    fov=math.radians(min(170,max(5,float(cone.get("fov",50) or 50))))
                    rng=max(1,min(10000,float(cone.get("range",30) or 30)))
                    angle=math.radians(bearing)
                    def endpoint(theta):
                        lat=p[0]+rng*math.cos(theta)/111_320
                        lon=p[1]+rng*math.sin(theta)/(111_320*max(.05,math.cos(math.radians(p[0]))))
                        return project([lat,lon])
                    q1=endpoint(angle-fov/2);q2=endpoint(angle+fov/2)
                    # True PDF transparency: street tiles remain visible beneath camera coverage.
                    # Apply alpha only to this cone so waypoints and labels stay opaque.
                    path=c.beginPath();path.moveTo(xp,yp);path.lineTo(*q1);path.lineTo(*q2);path.close()
                    c.saveState()
                    c.setFillColor(colors.HexColor("#44bd70"))
                    c.setFillAlpha(.20)
                    c.drawPath(path,fill=1,stroke=0)
                    c.restoreState()
                    c.saveState()
                    c.setStrokeColor(colors.HexColor("#2aab58"))
                    c.setLineWidth(.75)
                    c.setStrokeAlpha(.9)
                    c.drawPath(path,fill=0,stroke=1)
                    c.restoreState()
                except (ValueError,TypeError,OverflowError):
                    pass
        c.setFillColor(colors.white);c.setStrokeColor(GREEN)
        c.setLineWidth(1.5);c.circle(xp,yp,7,stroke=1,fill=1)
        c.setFillColor(INK);c.setFont("Helvetica-Bold",8);c.drawCentredString(xp,yp-2.5,str(i+1))
    if _coord(target):
        xp,yp=project(target);c.setStrokeColor(colors.HexColor("#ce9d4d"))
        c.setLineWidth(1);c.line(xp-6,yp,xp+6,yp);c.line(xp,yp-6,xp,yp+6)
    for m in marshals:
        if not isinstance(m,dict) or not _coord(m.get("position")):continue
        xp,yp=project(m["position"]);c.setFillColor(RED)
        c.circle(xp,yp+4,2,fill=1,stroke=0)
        c.setStrokeColor(RED);c.setLineWidth(1.5)
        c.line(xp,yp+1,xp,yp-6);c.line(xp-3,yp-2,xp+3,yp-2)
        c.line(xp,yp-6,xp-3,yp-10);c.line(xp,yp-6,xp+3,yp-10)
    c.setFillColor(colors.HexColor("#33443b"))
    c.setFont("Helvetica",7)
    note="Map: © OpenStreetMap contributors · Not for navigation" if backdrop is not None else "Basemap unavailable · schematic only"
    c.setFillColor(colors.white)
    c.roundRect(x+7,top-h+5,max(193,stringWidth(note,"Helvetica",7)+12),14,3,stroke=0,fill=1)
    c.setFillColor(MUTED)
    c.drawString(x+13,top-h+9,note)

def make_planner_pdf(plan):
    """One complete A4 page per shot (plus no cover or separate guard page)."""
    buffer=io.BytesIO()
    c=canvas.Canvas(buffer,pagesize=A4,pageCompression=1)
    c.setTitle("OFFSET Drone Planner - "+_safe(plan.get("name"),100))
    c.setAuthor("OFFSET")
    name=_safe(plan.get("name"),90) or "Untitled plan"
    shots=[shot for shot in (plan.get("shots") or []) if isinstance(shot,dict)]
    if not shots:
        y=_page(c,name,"DRONE SHOT PLAN")
        _line(c,"No shots in this plan yet.",M,y,11,MUTED)
        c.showPage()
    for index,shot in enumerate(shots):
        marshals=[g for g in (shot.get("marshals") or [])
                  if isinstance(g,dict) and _coord(g.get("position"))]
        y=_page(c,name,f"SHOT {index+1:02d} / {len(shots):02d}")
        y=_line(c,_safe(shot.get("name"),100) or f"Shot {index+1}",M,y,14,INK,True)
        details=[_safe(shot.get(k),60) for k in ("drone","lens","date","time")]
        if isinstance(shot.get("sunHour"),(int,float)):
            hours=max(0,min(23.75,float(shot["sunHour"])))
            details.append(f"Sun preview {int(hours):02d}:{int(round((hours%1)*60)):02d}")
        details=[p for p in details if p]
        if details:
            y-=3
            y=_line(c,"  |  ".join(details),M,y,9,MUTED,width=PW-2*M)
        description=_safe(shot.get("description"),1500)
        if description:
            y-=5
            desc_lines=simpleSplit(description,"Helvetica",9,PW-2*M)
            # Keep the map and all essential shot details together.
            for part in desc_lines[:5]:
                y=_line(c,part,M,y,9,INK)
            if len(desc_lines)>5:
                y=_line(c,"Description continues in the Planner.",M,y,8,MUTED)
        y-=8
        route=shot.get("route") if isinstance(shot.get("route"),list) else []
        cones=shot.get("waypointCones") if isinstance(shot.get("waypointCones"),list) else []
        # Solar information is shot-specific. Never silently invent a date.
        point=next((p for p in route if _coord(p)), None)
        if point is None and _coord(shot.get("target")):
            point=shot["target"]
        solar=_sun_events(shot,point)
        closure=_closure_path(route,cones,shot.get('simSmoothing','cinematic')) if shot.get('pdfClosureArea') is True else []
        map_height=(245 if y>=580 else 205) if solar else (290 if y>=580 else 245)
        if closure:map_height-=24
        _map(c,route,shot.get("target"),cones,marshals,M,y,PW-2*M,map_height,solar,shot.get("sketches"),shot.get("textBoxes"),closure)
        y-=map_height+8
        if closure:
            y=_line(c,'Orange: planned 1:1 exclusion area along the flight path (radius = height AGL).',M,y,8,INK)
            y=_line(c,'Approximate planning overlay. Verify route, heights and site-specific restrictions before use.',M,y,7,MUTED)
            y-=5
        if solar:
            _sun_summary(c,solar,M,y,PW-2*M)
            y-=54
        else:
            y-=8

        col_width=(PW-2*M-16)/2
        left_x=M
        right_x=M+col_width+16
        bottom=56
        y_left=_line(c,"WAYPOINTS",left_x,y,10,GREEN,True)
        for i,p in enumerate(route):
            if not _coord(p):continue
            if y_left<bottom+15:
                y_left=_line(c,f"+ {len(route)-i} more waypoints in Planner",left_x,y_left,8,MUTED)
                break
            data=cones[i] if i<len(cones) and isinstance(cones[i],dict) else {}
            y_left=_line(c,f"{i+1:02d}  {p[0]:.5f}, {p[1]:.5f}",left_x,y_left,8,INK)
            if data.get("visible",True):
                y_left=_line(c,f"     {data.get('mode','manual')} camera · FOV {data.get('fov',50)} deg",left_x,y_left,7,MUTED)

        # Embed the selected shot reference alongside the plan on the SAME page.
        ref=shot.get("referenceImage","")
        y_right=y
        if isinstance(ref,str) and ref.startswith("data:image/jpeg;base64,") and len(ref)<250000:
            try:
                import base64
                img=Image.open(io.BytesIO(base64.b64decode(ref.split(",",1)[1],validate=True)))
                img.load()
                if img.width>2000 or img.height>2000:
                    raise ValueError("Image dimensions exceed export limit")
                y_right=_line(c,"SHOT REFERENCE",right_x,y_right,10,GREEN,True)
                thumb_h=75
                c.setFillColor(colors.HexColor("#edf1ed"))
                c.roundRect(right_x,y_right-thumb_h,col_width,thumb_h,5,stroke=0,fill=1)
                img.thumbnail((round(col_width*2),round(thumb_h*2)))
                aw=min(col_width,img.width*thumb_h/max(1,img.height))
                ah=min(thumb_h,img.height*aw/max(1,img.width))
                c.drawImage(ImageReader(img),right_x+(col_width-aw)/2,
                            y_right-thumb_h+(thumb_h-ah)/2,aw,ah,mask="auto")
                y_right-=thumb_h+11
            except Exception:
                _LOG.warning("Could not embed shot reference image",exc_info=True)
                y_right=y
        y_right=_line(c,f"ROAD MARSHALS ({len(marshals)})",right_x,y_right,10,RED,True)
        if not marshals:
            _line(c,"None placed",right_x,y_right,8,MUTED)
        for i,g in enumerate(marshals):
            if y_right<bottom+17:
                y_right=_line(c,f"+ {len(marshals)-i} more in Planner",right_x,y_right,8,MUTED)
                break
            label=_safe(g.get("label"),38) or f"Road marshal {i+1}"
            y_right=_line(c,f"{i+1:02d}  {label}",right_x,y_right,8,INK)
            p=g["position"]
            y_right=_line(c,f"     {p[0]:.5f}, {p[1]:.5f}",right_x,y_right,7,MUTED)

        c.showPage()
    c.save()
    buffer.seek(0)
    return buffer.getvalue()
