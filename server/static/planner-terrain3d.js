(()=>{'use strict';
/* Optional 3D terrain scout preview. The camera is a tilted map overview,
   not a pixel-accurate cinematography image or obstacle clearance model. */
const $=id=>document.getElementById(id),core=()=>window.OFFSETPlanner;
let viewer=null,loading=null,enabled=false,initialized=false,viewerFailed=false,updatePending=false,modeEpoch=0,draggableMarkers=[],userPitch=null;
const CDN='https://cdn.jsdelivr.net/npm/maplibre-gl@5.6.1/dist/';
const empty={type:'FeatureCollection',features:[]};
function loadMapLibre(){
 if(window.maplibregl)return Promise.resolve(window.maplibregl);
 if(loading)return loading;
 loading=new Promise((resolve,reject)=>{
  if(!document.querySelector('link[data-offset-3d]')){
   const css=document.createElement('link');css.rel='stylesheet';css.href=CDN+'maplibre-gl.css';css.dataset.offset3d='true';document.head.append(css);
  }
  const script=document.createElement('script');script.src=CDN+'maplibre-gl.js';script.async=true;
  script.onload=()=>window.maplibregl?resolve(window.maplibregl):reject(Error('3D library unavailable'));
  script.onerror=()=>reject(Error('Could not load 3D terrain library'));
  document.head.append(script);
 });
 return loading;
}
function destination(start,bearing,metres){
 const rad=Math.PI/180,angle=bearing*rad,d=metres/6371008.8,p=start[0]*rad,l=start[1]*rad;
 const lat=Math.asin(Math.sin(p)*Math.cos(d)+Math.cos(p)*Math.sin(d)*Math.cos(angle));
 const lon=l+Math.atan2(Math.sin(angle)*Math.sin(d)*Math.cos(p),Math.cos(d)-Math.sin(p)*Math.sin(lat));
 return [lat*180/Math.PI,(lon*180/Math.PI+540)%360-180];
}
function feature(type,coords,properties){
 return {type:'Feature',geometry:{type,coordinates:coords},properties:properties||{}};
}
function heightFor(shot,index){const value=Number(shot?.waypointCones?.[index]?.altitudeAGL);return Number.isFinite(value)&&value>=2?Math.min(500,value):30}
function dataForShot(s){
 const api=core(),index=api.getActiveWaypoint(),origin=s?.route?.[index],valid=api.coordsOk;
 if(!valid(origin))return {center:[10.7522,59.9139],bearing:0,range:100,collection:empty,valid:false};
 const bearing=api.getBearing();
 const c=s.waypointCones?.[index],range=Math.max(35,Math.min(500,Number(c?.range)||120));
 const look=destination(origin,bearing,Math.max(35,range*.85));
 const target=valid(s.target)?s.target:look;
 const features=[
  feature('LineString',[[origin[1],origin[0]],[look[1],look[0]]],{kind:'aim'}),
  feature('Point',[origin[1],origin[0]],{kind:'waypoint'}),
  feature('Point',[target[1],target[0]],{kind:'target'})
 ];
 if(s.route.length>1){
  const points=s.route.filter(valid).map(p=>[p[1],p[0]]);
  if(points.length>1)features.push(feature('LineString',points,{kind:'route'}));
 }
 const center=destination(origin,bearing,Math.max(20,range*.35));
 return {center:[center[1],center[0]],bearing,range,height:heightFor(s,index),collection:{type:'FeatureCollection',features},valid:true};
}
async function init3D(epoch){
 const api=core(),s=api?.getShot(),btn=$('preview-3d-toggle');
 if(!s?.route?.length){setStatus('Add a waypoint before opening 3D terrain.');return false}
 const gl=await loadMapLibre();
 if(!enabled||epoch!==modeEpoch)return false;
 if(typeof gl.supported==='function'&&!gl.supported()){throw Error('3D WebGL is unavailable on this device')}
 const current=dataForShot(s),initialCoords=current.center;
 viewer=new gl.Map({
  container:'shot-preview-terrain',
  center:initialCoords,
  zoom:15.5,
  pitch:73,
  bearing:current.bearing,
  maxPitch:80,
  maxZoom:18,
  attributionControl:false,
  antialias:true,
  renderWorldCopies:false,
  style:{
   version:8,
   sources:{
    osm:{type:'raster',tiles:['https://tile.openstreetmap.org/{z}/{x}/{y}.png'],tileSize:256,
      maxzoom:19,attribution:'© OpenStreetMap contributors'},
    terrain:{type:'raster-dem',url:'https://tiles.mapterhorn.com/tilejson.json',encoding:'terrarium',
      attribution:'© Mapterhorn'},
    buildings:{type:'vector',url:'https://tiles.openfreemap.org/planet',
      attribution:'© OpenFreeMap / OpenMapTiles / OpenStreetMap contributors'},
    coverage:{type:'geojson',data:empty}
   },
   layers:[
    {id:'base',type:'raster',source:'osm'},
    // Extruded OpenStreetMap building footprints: use mapped height where
    // provided and otherwise a clearly approximate 10 m visual fallback.
    {id:'3d-buildings',type:'fill-extrusion',source:'buildings', 'source-layer':'building',
     minzoom:14,
     filter:['!=',['get','hide_3d'],true],
     paint:{
      'fill-extrusion-color':'#c2d1c7',
      'fill-extrusion-opacity':0.83,
      'fill-extrusion-height':['interpolate',['linear'],['zoom'],14,0,15,
        ['max',2,['to-number',['coalesce',['get','render_height'],['get','height']],10]]],
      'fill-extrusion-base':['max',0,['to-number',['coalesce',['get','render_min_height'],['get','min_height']],0]]
     }},
    {id:'route',type:'line',source:'coverage',filter:['==',['get','kind'],'route'],
     paint:{'line-color':'#3bd982','line-width':3,'line-opacity':.95}},
    {id:'aim',type:'line',source:'coverage',filter:['==',['get','kind'],'aim'],
     paint:{'line-color':'#f0b85f','line-width':2.5,'line-dasharray':[2,2]}},
    {id:'origin',type:'circle',source:'coverage',filter:['==',['get','kind'],'waypoint'],
     paint:{'circle-radius':7,'circle-color':'#52d28e','circle-stroke-width':2,'circle-stroke-color':'#0e2417'}},
    {id:'target',type:'circle',source:'coverage',filter:['==',['get','kind'],'target'],
     paint:{'circle-radius':6,'circle-color':'#f3bc80','circle-stroke-width':2,'circle-stroke-color':'#35251d'}}
   ],
   terrain:{source:'terrain',exaggeration:1}
  }
 });
 viewer.addControl(new gl.NavigationControl({showCompass:true,showZoom:true,visualizePitch:true}),'top-right');
 viewer.on('load',()=>{initialized=true;if(enabled&&epoch===modeEpoch){setStatus('');sync3D()}});
 viewer.on('error',e=>{
  const msg=e?.error?.message||'';
  if(msg.includes('terrain')||msg.includes('Failed to fetch')||msg.includes('tile'))setStatus('Some terrain tiles are unavailable; try a different area.');
 });
 viewer.on('idle',()=>{if(initialized&&enabled)setStatus('')});
 return true;
}
function updatePitchLabel(){
 const label=$('preview-pitch-value');
 if(label)label.textContent='Tilt '+Math.round(userPitch??viewer?.getPitch?.()??73)+'°';
}
function changePitch(delta){
 if(!enabled||!viewer||!initialized)return;
 const current=Number.isFinite(userPitch)?userPitch:viewer.getPitch();
 userPitch=Math.max(0,Math.min(80,Math.round(current+delta)));
 viewer.easeTo({pitch:userPitch,duration:220});
 updatePitchLabel();
}
$('preview-pitch-up').addEventListener('click',()=>changePitch(10));
$('preview-pitch-down').addEventListener('click',()=>changePitch(-10));
function setStatus(message){const node=$('shot-preview-terrain-loading');if(!node)return;node.hidden=!message;node.textContent=message}
function renderDraggableMarkers(s){
 if(!viewer||!window.maplibregl)return;
 draggableMarkers.forEach(m=>m.remove());draggableMarkers=[];
 const api=core(),valid=api.coordsOk;
 (s.route||[]).forEach((point,index)=>{
  if(!valid(point))return;
  const marker=new window.maplibregl.Marker({color:index===api.getActiveWaypoint()?'#a7f28a':'#41bd7a',draggable:true,scale:.75})
   .setLngLat([point[1],point[0]]).addTo(viewer);
  marker.getElement().title='Move waypoint '+(index+1);
  marker.on('dragend',()=>{
   const p=marker.getLngLat();api.moveWaypoint(index,[p.lat,p.lng]);
  });
  marker.on('click',()=>api.setActiveWaypoint(index));
  draggableMarkers.push(marker);
 });
 if(valid(s.target)){
  const marker=new window.maplibregl.Marker({color:'#f5a475',draggable:true,scale:.85})
   .setLngLat([s.target[1],s.target[0]]).addTo(viewer);
  marker.getElement().title='Move camera target';
  marker.on('dragend',()=>{const p=marker.getLngLat();api.moveTarget([p.lat,p.lng])});
  draggableMarkers.push(marker);
 }
}
function updateCameraView(data,s){
 const current=s.route[core().getActiveWaypoint()];
 const coords=[current[1],current[0]];
 const ground=typeof viewer.queryTerrainElevation==='function'?viewer.queryTerrainElevation(coords):null;
 const altitude=(Number.isFinite(ground)?ground:0)+data.height;
 const target=core().coordsOk(s.target)?s.target:destination(current,data.bearing,Math.max(50,data.range));
 const targetCoords=[target[1],target[0]];
 try{
  if(typeof viewer.calculateCameraOptionsFromTo==='function'){
   const targetHeight=typeof viewer.queryTerrainElevation==='function'?viewer.queryTerrainElevation(targetCoords):null;
   const camera=viewer.calculateCameraOptionsFromTo(
     new window.maplibregl.LngLat(coords[0],coords[1]),altitude,
     new window.maplibregl.LngLat(targetCoords[0],targetCoords[1]),
     Number.isFinite(targetHeight)?targetHeight:0
   );
   viewer.jumpTo(camera);if(userPitch!==null)viewer.setPitch(userPitch);updatePitchLabel();return;
  }
 }catch(error){console.warn('Camera-height view fallback',error)}
 viewer.jumpTo({center:data.center,bearing:data.bearing,pitch:userPitch??73,
  zoom:Math.max(12,Math.min(18,17-Math.log2((data.height+data.range)/45)))});
 updatePitchLabel();
}
function sync3D(){
 if(!enabled||!viewer||!initialized)return;
 const s=core()?.getShot();if(!s)return;
 const data=dataForShot(s);
 if(!data.valid){setStatus('Add a waypoint to use 3D Terrain.');return}
 const source=viewer.getSource('coverage');if(source)source.setData(data.collection);
 renderDraggableMarkers(s);
 updateCameraView(data,s);
 viewer.resize();
 const note=$('preview-note');
 note.textContent='3D terrain + buildings aligned with waypoint heading · drag to orbit. Building heights may be estimated or missing. Not an optical camera frame, obstacle survey or clearance check.';
}
function refresh(){if(!enabled||updatePending)return;updatePending=true;requestAnimationFrame(()=>{updatePending=false;sync3D()})}
function setMode(on){
 const epoch=++modeEpoch;
 enabled=!!on;
 const node=$('shot-preview-view'),target=$('shot-preview-terrain'),toggle=$('preview-3d-toggle');
 node.classList.toggle('terrain-enabled',enabled);
 node.setAttribute('role',enabled?'group':'img');
 target.hidden=!enabled;
 $('preview-pitch-controls').hidden=!enabled;
 if(enabled)updatePitchLabel();
 const overview=$('shot-preview-2dmap');if(overview)overview.hidden=enabled;
 toggle.setAttribute('aria-pressed',String(enabled));toggle.textContent=enabled?'2D Map':'3D View';
 $('preview-terrain-credit').hidden=!enabled;
 if(!enabled){
  setStatus('');
  // Release the WebGL context when returning to 2D on iPhone.
  draggableMarkers.forEach(m=>m.remove());draggableMarkers=[];
  if(viewer){viewer.remove();viewer=null;initialized=false}
  userPitch=null;
  // Explicitly restore the SVG viewfinder, regardless of 3D loading state.
  const svg=$('shot-preview-svg');if(svg){svg.hidden=false;svg.style.display='block'}
  const fallback=$('preview-empty');if(fallback)fallback.style.removeProperty('display');
  document.dispatchEvent(new Event('offset:map-rendered'));
  document.dispatchEvent(new Event('offset:preview-mode-changed'));
  return;
 }
 document.dispatchEvent(new Event('offset:preview-mode-changed'));
 setStatus('Loading 3D terrain…');
 if(viewer){if(initialized){setStatus('');requestAnimationFrame(sync3D)}return}
 init3D(epoch).catch(err=>{
  if(!enabled||epoch!==modeEpoch)return;
  console.warn('OFFSET 3D:',err);
  viewerFailed=true;
  setStatus(err.message||'3D terrain unavailable. Use 2D View to continue.');
 });
}
$('preview-3d-toggle').addEventListener('click',()=>{
 const next=!enabled;
 if(next&&!core()?.getShot()?.route?.length){setStatus('Place a waypoint first to see terrain in 3D.');return}
 setMode(next);
});
document.addEventListener('offset:map-rendered',refresh);
document.addEventListener('offset:preview-height-changed',refresh);
document.addEventListener('offset:shot-rendered',refresh);
window.addEventListener('resize',()=>{if(enabled&&viewer)viewer.resize()});
document.addEventListener('offset:planner-layout',()=>{if(enabled&&viewer)viewer.resize()});
$('preview-aspect').addEventListener('change',()=>{if(enabled&&viewer)requestAnimationFrame(()=>viewer.resize())});
})();