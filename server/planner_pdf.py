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
from datetime import datetime, timezone
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


def _map(c, route, target, cones, marshals, x, top, w, h):
    """Render a real street basemap, then precisely aligned vector map annotations."""
    valid=[p for p in route if _coord(p)]
    pts=valid+([target] if _coord(target) else [])+[
        m["position"] for m in marshals
        if isinstance(m,dict) and _coord(m.get("position"))
    ]
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
    marshals=[g for g in (plan.get("marshals") or [])
              if isinstance(g,dict) and _coord(g.get("position"))]
    if not shots:
        y=_page(c,name,"DRONE SHOT PLAN")
        _line(c,"No shots in this plan yet.",M,y,11,MUTED)
        c.showPage()
    for index,shot in enumerate(shots):
        y=_page(c,name,f"SHOT {index+1:02d} / {len(shots):02d}")
        y=_line(c,_safe(shot.get("name"),100) or f"Shot {index+1}",M,y,14,INK,True)
        details=[_safe(shot.get(k),60) for k in ("drone","lens","date","time")]
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
        # Tall street map is the primary part of the shot page.
        map_height=290 if y>=580 else 245
        _map(c,route,shot.get("target"),cones,marshals,M,y,PW-2*M,map_height)
        y-=map_height+16

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

        y_right=_line(c,f"ROAD MARSHALS ({len(marshals)})",right_x,y,10,RED,True)
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
