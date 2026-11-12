(()=>{'use strict';
const $=id=>document.getElementById(id);
const api=()=>window.OFFSETPlanner;
let mini=null,flight=null,cones=[],pins=[],targetPin=null,dragging=false;
const valid=p=>api()?.coordsOk(p);
function usable(){return $('shot-preview-2dmap')&&!$('shot-preview-2dmap').hidden}
function init(){
 if(mini||!window.L||!usable())return;
 mini=L.map('shot-preview-2dmap',{zoomControl:false,attributionControl:false,scrollWheelZoom:false,dragging:true,boxZoom:false,keyboard:false});
 L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19}).addTo(mini);
 L.control.zoom({position:'bottomright'}).addTo(mini);
 mini.on('dragstart',()=>{dragging=true});
 mini.on('dragend',()=>{dragging=false});
}
function endpoint(start,bearing,range){
 const r=Math.PI/180,d=range/6371008.8,p=start[0]*r,l=start[1]*r,a=bearing*r;
 const lat=Math.asin(Math.sin(p)*Math.cos(d)+Math.cos(p)*Math.sin(d)*Math.cos(a));
 const lon=l+Math.atan2(Math.sin(a)*Math.sin(d)*Math.cos(p),Math.cos(d)-Math.sin(p)*Math.sin(lat));
 return [lat/r,(lon/r+540)%360-180];
}
function icon(text,isActive=false){
 return L.divIcon({className:'preview-pin',html:'<span class="'+(isActive?'selected':'')+'">'+text+'</span>',iconSize:[30,30],iconAnchor:[15,15]});
}
function render(){
 if(!usable())return;
 init();if(!mini)return;
 setTimeout(()=>mini.invalidateSize(),20);
 const shot=api()?.getShot();if(!shot)return;
 if(flight){flight.remove();flight=null}
 cones.forEach(l=>l.remove());cones=[];
 pins.forEach(l=>l.remove());pins=[];
 if(targetPin){targetPin.remove();targetPin=null}
 const route=(shot.route||[]).filter(valid),active=api().getActiveWaypoint();
 if(route.length>1)flight=L.polyline(route,{color:'#40d88a',weight:3,opacity:1}).addTo(mini);
 route.forEach((p,index)=>{
  const cone=shot.waypointCones?.[index];
  if(cone?.visible!==false){
   const fov=Math.min(170,Math.max(5,Number(cone?.fov)||50));
   const dist=Math.max(1,Number(cone?.range)||30);
   const angle=cone.mode==='target'&&valid(shot.target)?
    ((Math.atan2((shot.target[1]-p[1])*Math.cos(p[0]*Math.PI/180),shot.target[0]-p[0])*180/Math.PI)+360)%360:
    Number(cone?.bearing)||0;
   let bounds=[p];for(let j=0;j<=12;j++)bounds.push(endpoint(p,angle-fov/2+fov*j/12,dist));
   cones.push(L.polygon(bounds,{color:'#45c57c',weight:1,fillColor:'#5fe493',fillOpacity:.13,interactive:false}).addTo(mini));
  }
  const m=L.marker(p,{draggable:true,icon:icon(String(index+1),index===active)}).addTo(mini);
  m.on('click',()=>api().setActiveWaypoint(index));
  m.on('dragend',()=>{const ll=m.getLatLng();api().moveWaypoint(index,[ll.lat,ll.lng])});
  pins.push(m);
 });
 if(valid(shot.target)){
  targetPin=L.marker(shot.target,{draggable:true,icon:icon('◎')}).addTo(mini);
  targetPin.on('dragend',()=>{const ll=targetPin.getLatLng();api().moveTarget([ll.lat,ll.lng])});
 }
 const current=route[active]||route[0];
 if(current&&!dragging){
  const target=valid(shot.target)?shot.target:null;
  if(target&&mini.distance(current,target)>300)mini.fitBounds([current,target],{padding:[28,28],maxZoom:17});
  else mini.setView(current,Math.max(15,mini.getZoom()||16),{animate:false});
 }else if(!current){
  const main=api().getMap();if(main)mini.setView(main.getCenter(),main.getZoom());
 }
}
document.addEventListener('offset:map-rendered',render);
document.addEventListener('offset:shot-rendered',render);
document.addEventListener('offset:preview-mode-changed',render);
document.addEventListener('offset:planner-map-ready',render);
if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',render,{once:true});else render();
})();