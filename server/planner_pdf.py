"""Reliable downloadable OFFSET Drone Planner PDF export.
All rendering runs server-side, so Safari and iPhone need no print support.
Includes vector shot overview (not a licensed street-map reproduction).
"""
import io
import math
import textwrap
from datetime import datetime, timezone
from xml.sax.saxutils import escape

from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.utils import simpleSplit
from reportlab.pdfbase.pdfmetrics import stringWidth
from reportlab.pdfgen import canvas

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


def _map(c, route, target, cones, marshals, x, top, w, h):
    """Draw a lightweight route diagram with waypoint cameras and marshals."""
    c.setFillColor(colors.HexColor("#f5f7f5"))
    c.setStrokeColor(colors.HexColor("#d6e2d9"))
    c.roundRect(x,top-h,w,h,7,stroke=1,fill=1)
    valid=[p for p in route if _coord(p)]
    pts=valid+([target] if _coord(target) else [])+[m["position"] for m in marshals if isinstance(m,dict) and _coord(m.get("position"))]
    if not pts:
        _line(c,"No mapped positions in this shot",x+12,top-24,9,MUTED)
        return
    lat=[p[0] for p in pts];lon=[p[1] for p in pts]
    midlat=(min(lat)+max(lat))/2
    mx=max(0.12,math.cos(math.radians(midlat)))
    scaled=[((p[1]-min(lon))*mx,p[0]-min(lat)) for p in pts]
    extx=max(0.00015,max(a for a,b in scaled))
    exty=max(0.00015,max(b for a,b in scaled))
    margin=26
    sf=min((w-2*margin)/extx,(h-2*margin)/exty)
    dx=(w-extx*sf)/2
    dy=(h-exty*sf)/2
    def project(p):
        return x+dx+(p[1]-min(lon))*mx*sf,top-h+dy+(p[0]-min(lat))*sf
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
                bearing=float(cone.get("bearing",0) or 0)
                if cone.get("mode")=="target" and _coord(target):
                    latD=(target[0]-p[0])
                    lonD=(target[1]-p[1])*mx
                    bearing=math.degrees(math.atan2(lonD,latD))
                angle=math.radians(bearing)
                fov=math.radians(min(170,max(5,float(cone.get("fov",50) or 50))))
                reach=19
                q1=(xp+reach*math.sin(angle-fov/2),yp+reach*math.cos(angle-fov/2))
                q2=(xp+reach*math.sin(angle+fov/2),yp+reach*math.cos(angle+fov/2))
                c.setFillColor(LIGHT_GREEN);c.setStrokeColor(GREEN);c.setLineWidth(.6)
                path=c.beginPath();path.moveTo(xp,yp);path.lineTo(*q1);path.lineTo(*q2);path.close()
                c.drawPath(path,fill=1,stroke=1)
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
    c.setFillColor(MUTED);c.setFont("Helvetica",7)
    c.drawString(x+10,top-h+8,"Schematic only · not a navigational basemap")


def make_planner_pdf(plan):
    buffer=io.BytesIO()
    c=canvas.Canvas(buffer,pagesize=A4,pageCompression=1)
    c.setTitle("OFFSET Drone Planner - "+_safe(plan.get("name"),100))
    c.setAuthor("OFFSET")
    name=_safe(plan.get("name"),90) or "Untitled plan"
    shots=plan.get("shots") or []
    marshals=plan.get("marshals") or []
    y=_page(c,name,"DRONE SHOT PLAN")
    y=_line(c,"Generated "+datetime.now(timezone.utc).strftime("%d %b %Y %H:%M UTC"),M,y,9,MUTED)
    y-=12
    y=_line(c,f"{len(shots)} shots  |  {len(marshals)} road marshals",M,y,11,GREEN,True)
    y-=8
    y=_line(c,"Map diagrams show relative positions, not streets or flight clearance.",M,y,9,MUTED,width=PW-2*M)
    c.showPage()
    for index,shot in enumerate(shots):
        if not isinstance(shot,dict):continue
        y=_page(c,name,f"SHOT {index+1:02d} / {len(shots):02d}")
        y=_line(c,_safe(shot.get("name"),100) or f"Shot {index+1}",M,y,14,INK,True)
        y-=5
        details=[_safe(shot.get(k),60) for k in ("drone","lens","date","time")]
        details=[p for p in details if p]
        if details:
            y=_line(c,"  |  ".join(details),M,y,9,MUTED,width=PW-2*M)
        description=_safe(shot.get("description"),1200)
        if description:
            y-=6
            y=_line(c,description,M,y,10,INK,width=PW-2*M)
        y-=12
        route=shot.get("route") if isinstance(shot.get("route"),list) else []
        cones=shot.get("waypointCones") if isinstance(shot.get("waypointCones"),list) else []
        _map(c,route,shot.get("target"),cones,marshals,M,y,PW-2*M,245)
        y-=265
        y=_line(c,"WAYPOINTS",M,y,10,GREEN,True)
        for i,p in enumerate(route[:14]):
            if not _coord(p):continue
            data=cones[i] if i<len(cones) and isinstance(cones[i],dict) else {}
            label=f"{i+1:02d}   {p[0]:.6f}, {p[1]:.6f}    "
            if data.get("visible",True):
                label+=f"Camera: {data.get('mode','manual')}, FOV {data.get('fov',50)} deg"
            y=_line(c,label,M,y,8,INK)
        if len(route)>14:y=_line(c,f"+ {len(route)-14} more waypoints",M,y,8,MUTED)
        c.showPage()
    if marshals:
        y=_page(c,name,"ROAD MARSHALS / SPERREVAKTER")
        y=_line(c,"Planned positions only; road closure permission must be arranged separately.",M,y,9,MUTED,width=PW-2*M)
        y-=12
        for i,g in enumerate(marshals):
            if not isinstance(g,dict):continue
            if y<70:c.showPage();y=_page(c,name,"ROAD MARSHALS (CONTINUED)")
            p=g.get("position")
            if not _coord(p):continue
            y=_line(c,f"{i+1:02d}   {_safe(g.get('label'),70) or 'Road marshal'}  -  {p[0]:.6f}, {p[1]:.6f}",M,y,9,INK)
        c.showPage()
    c.save()
    buffer.seek(0)
    return buffer.getvalue()
