(()=>{'use strict';
/* OFFSET Shot Simulator v1 — Cesium camera path previsualisation.
   Building volumes come from voluntary OSM tags, not clearance-grade survey.
   Ground is the WGS84 ellipsoid until a licensed terrain source is configured.
   No token or Google Earth scraping. */
const $=id=>document.getElementById(id),core=()=>window.OFFSETPlanner;
const VERSION='1.135.0',BASE='https://cdn.jsdelivr.net/npm/cesium@'+VERSION+'/Build/Cesium/';
let viewer=null,buildings=null,enabled=false,loadingSdk=null,epoch=0;
let playing=false,raf=0,lastTime=0,metres=0,routeLength=0,activeDrag=null,dragHandler=null;
let overview=false,buildingKey='',buildingEpoch=0,buildingAbort=null,buildingCache=new Map();
const $status=$('sim-status'),$overlay=$('shot-preview-terrain-loading'),$play=$('sim-play');
const rad=Math.PI/180,deg=180/Math.PI;
const valid=p=>core()?.coordsOk(p);
function status(message){$status.textContent=message}
function overlay(message){$overlay.hidden=!message;$overlay.textContent=message||''}
function loadSDK(){
 if(window.Cesium)return Promise.resolve(window.Cesium);
 if(loadingSdk)return loadingSdk;
 loadingSdk=new Promise((resolve,reject)=>{
  window.CESIUM_BASE_URL=BASE;
  if(!document.querySelector('link[data-cesium-offset]')){
   const css=document.createElement('link');css.rel='stylesheet';css.href=BASE+'Widgets/widgets.css';
   css.dataset.cesiumOffset='true';document.head.append(css);
  }
  const script=document.createElement('script');script.async=true;script.src=BASE+'Cesium.js';
  script.onload=()=>window.Cesium?resolve(window.Cesium):reject(Error('Cesium did not initialise'));
  script.onerror=()=>reject(Error('Unable to load the Cesium engine'));
  document.head.append(script);
 }).catch(e=>{loadingSdk=null;throw e});
 return loadingSdk;
}
function bearing(a,b){
 const p1=a[0]*rad,p2=b[0]*rad,d=(b[1]-a[1])*rad;
 return (Math.atan2(Math.sin(d)*Math.cos(p2),Math.cos(p1)*Math.sin(p2)-Math.sin(p1)*Math.cos(p2)*Math.cos(d))*deg+360)%360;
}
function distance(a,b){
 const dlat=(b[0]-a[0])*rad,dlon=(b[1]-a[1])*rad;
 const h=Math.sin(dlat/2)**2+Math.cos(a[0]*rad)*Math.cos(b[0]*rad)*Math.sin(dlon/2)**2;
 return 6371008.8*2*Math.atan2(Math.sqrt(h),Math.sqrt(Math.max(0,1-h)));
}
function altitude(s,i){
 const n=Number(s?.waypointCones?.[i]?.altitudeAGL);
 return Number.isFinite(n)&&n>=2?Math.max(2,Math.min(500,n)):30;
}
function pitch(s){const n=Number(s?.simPitch);return Number.isFinite(n)?Math.max(-85,Math.min(35,n)):-10}
function fov(s){
 const n=Number(core()?.lensFov(s))||Number(s?.waypointCones?.[core()?.getActiveWaypoint()]?.fov)||50;
 return Math.max(5,Math.min(150,n));
}
function route(s){return (s?.route||[]).filter(valid)}
function orient(s,position,next=null){
 if(valid(s.target)&&s?.waypointCones?.[core().getActiveWaypoint()]?.mode==='target')return bearing(position,s.target);
 return next&&valid(next)?bearing(position,next):core().getBearing();
}
function cameraAt(position,height,heading,tilt){
 if(!viewer||!position)return;
 const C=window.Cesium,canvas=viewer.scene.canvas;
 const width=Math.max(1,canvas.clientWidth),high=Math.max(1,canvas.clientHeight);
 const aspect=width/high;
 const horizontal=fov(core()?.getShot());
 const chosen=aspect>=1?horizontal:2*Math.atan(Math.tan(horizontal*rad/2)/aspect)*deg;
 viewer.camera.frustum=new C.PerspectiveFrustum({
  fov:Math.min(Math.PI-.02,chosen*rad),aspectRatio:aspect,near:.2,far:200000
 });
 viewer.camera.setView({
  destination:C.Cartesian3.fromDegrees(position[1],position[0],height),
  orientation:{heading:heading*rad,pitch:tilt*rad,roll:0}
 });
}
function showCamera(){
 const s=core()?.getShot(),points=route(s);if(!viewer||!points.length)return;
 const i=Math.min(core().getActiveWaypoint(),points.length-1),point=points[i];
 if(overview){
  const C=window.Cesium;
  viewer.camera.setView({
   destination:C.Cartesian3.fromDegrees(point[1],point[0],Math.max(120,altitude(s,i)+115)),
   orientation:{heading:core().getBearing()*rad,pitch:-58*rad,roll:0}
  });
 }else cameraAt(point,altitude(s,i),orient(s,point,points[i+1]),pitch(s));
 $('preview-pitch-value').textContent='Tilt '+Math.round(pitch(s))+'°';
}
function resetCamera(){stop();showCamera();status(overview?'Orbit overview. Drag to inspect the buildings.':'Drone camera at selected waypoint.')}
function markerLabel(index){return 'WP '+(index+1)}
function renderRoute(){
 if(!viewer)return;
 const C=window.Cesium,s=core()?.getShot(),points=route(s);
 viewer.entities.removeAll();
 if(!points.length)return;
 if(points.length>1)viewer.entities.add({
  polyline:{
   positions:points.map((p,i)=>C.Cartesian3.fromDegrees(p[1],p[0],altitude(s,i))),
   width:3,material:C.Color.fromCssColorString('#58eb99'),
   clampToGround:false
  }
 });
 points.forEach((p,i)=>{
  const entry=viewer.entities.add({
   name:markerLabel(i),
   position:C.Cartesian3.fromDegrees(p[1],p[0],altitude(s,i)),
   point:{pixelSize:i===core().getActiveWaypoint()?15:12,
    color:C.Color.fromCssColorString(i===core().getActiveWaypoint()?'#b2ff94':'#47d695'),
    outlineColor:C.Color.BLACK,outlineWidth:2,
    disableDepthTestDistance:Number.POSITIVE_INFINITY},
   label:{text:String(i+1),font:'bold 12px sans-serif',pixelOffset:new C.Cartesian2(0,-25),
    fillColor:C.Color.WHITE,style:C.LabelStyle.FILL_AND_OUTLINE,
    outlineWidth:3,disableDepthTestDistance:Number.POSITIVE_INFINITY}
  });
  entry._offsetWaypoint=i;
 });
 if(valid(s.target))viewer.entities.add({
  position:C.Cartesian3.fromDegrees(s.target[1],s.target[0],3),
  point:{pixelSize:13,color:C.Color.fromCssColorString('#f3a978'),
    outlineColor:C.Color.BLACK,outlineWidth:2,disableDepthTestDistance:Number.POSITIVE_INFINITY},
  label:{text:'TARGET',font:'11px sans-serif',pixelOffset:new C.Cartesian2(0,-22),
    fillColor:C.Color.WHITE,disableDepthTestDistance:Number.POSITIVE_INFINITY}
 })._offsetTarget=true;
}
function parseHeight(tags){
 const raw=Number.parseFloat(String(tags.height||'').replace(',','.'));
 if(Number.isFinite(raw)&&raw>0&&raw<450)return raw;
 const levels=Number.parseFloat(tags['building:levels']);
 return Number.isFinite(levels)&&levels>0?Math.min(450,levels*3.1):10;
}
function drawBuildings(elements){
 if(!viewer||!buildings)return 0;
 const C=window.Cesium;buildings.entities.removeAll();let count=0;
 for(const building of elements){
  const geom=building.geometry,tags=building.tags||{};
  if(!Array.isArray(geom)||geom.length<3)continue;
  const coords=[];
  for(const pt of geom){
   if(Number.isFinite(pt.lon)&&Number.isFinite(pt.lat))coords.push(pt.lon,pt.lat);
  }
  if(coords.length<6)continue;
  try{
   buildings.entities.add({polygon:{
    hierarchy:C.Cartesian3.fromDegreesArray(coords),
    height:0,extrudedHeight:parseHeight(tags),
    material:C.Color.fromCssColorString('#bfcac5').withAlpha(.91),
    outline:false
   }});
   count++;
  }catch(error){/* Skip malformed OSM footprints. */}
  if(count>=500)break;
 }
 return count;
}
async function loadBuildings(point,run){
 if(!point)return;
 const key=point.map(n=>(Math.round(n*250)/250).toFixed(3)).join(',');
 if(buildingKey===key)return;
 buildingKey=key;
 if(buildingAbort)buildingAbort.abort();
 const request=++buildingEpoch;
 if(buildingCache.has(key)){
  const count=drawBuildings(buildingCache.get(key));
  status(count+' approximate OSM buildings · not a collision model');
  return;
 }
 status('Loading OpenStreetMap building outlines…');
 const query='[out:json][timeout:20];way["building"](around:350,'+point[0].toFixed(6)+','+point[1].toFixed(6)+');out geom;';
 const controller=new AbortController();buildingAbort=controller;
 const timeout=setTimeout(()=>controller.abort(),18000);
 try{
  const url='https://overpass.private.coffee/api/interpreter?data='+encodeURIComponent(query);
  const res=await fetch(url,{signal:controller.signal});
  if(!res.ok)throw Error('OSM building service '+res.status);
  const body=await res.json();
  if(!enabled||request!==buildingEpoch||run!==epoch)return;
  const elements=Array.isArray(body.elements)?body.elements:[];
  buildingCache.set(key,elements);
  if(buildingCache.size>6)buildingCache.delete(buildingCache.keys().next().value);
  const count=drawBuildings(elements);
  status(count?count+' approximate OSM buildings loaded.':'No OSM building geometry found near this shot.');
 }catch(error){
  if(request!==buildingEpoch||run!==epoch||!enabled)return;
  status('Building data unavailable; shot playback still works. Try again later.');
 }finally{clearTimeout(timeout)}
}
function stop(){
 playing=false;cancelAnimationFrame(raf);raf=0;$play.textContent='▶ Play flight';lastTime=0;
}
function play(){
 if(!viewer)return;
 if(playing){stop();status('Playback paused.');return}
 const s=core()?.getShot(),points=route(s);
 if(points.length<2){status('Add at least two waypoints to play a camera flight.');return}
 if(overview){overview=false;$('sim-view').value='camera'}
 const legs=points.slice(1).map((p,i)=>distance(points[i],p));
 const total=legs.reduce((sum,l)=>sum+l,0);
 if(total<.01){status('Waypoints must be in different positions.');return}
 const speed=Number($('sim-speed').value)||3;
 let elapsed=0;
 playing=true;$play.textContent='Ⅱ Pause';
 function frame(now){
  if(!playing||!enabled)return;
  if(lastTime)elapsed+=Math.min((now-lastTime)/1000,.1)*speed;
  lastTime=now;
  let remaining=Math.min(elapsed,total),index=0;
  while(index<legs.length-1&&remaining>legs[index])remaining-=legs[index++];
  const t=legs[index]>0?Math.max(0,Math.min(1,remaining/legs[index])):1;
  const a=points[index],b=points[index+1];
  const here=[a[0]+(b[0]-a[0])*t,a[1]+(b[1]-a[1])*t];
  const h=altitude(s,index)+(altitude(s,index+1)-altitude(s,index))*t;
  cameraAt(here,h,orient(s,here,b),pitch(s));
  const pct=Math.min(100,Math.round(elapsed/total*100));
  status('Playing shot · '+pct+'% · '+Math.round(h)+' m planned height');
  if(elapsed>=total){stop();status('Shot playback complete.');return}
  raf=requestAnimationFrame(frame);
 }
 raf=requestAnimationFrame(frame);
}
function movePitch(delta){
 const s=core()?.getShot();if(!s)return;
 s.simPitch=Math.max(-85,Math.min(35,Math.round(pitch(s)+delta)));
 core().save();
 $('preview-pitch-value').textContent='Tilt '+s.simPitch+'°';
 if(viewer&&!playing&&!overview)showCamera();
}
function installEditing(){
 if(!viewer)return;
 const C=window.Cesium,scene=viewer.scene,handler=new C.ScreenSpaceEventHandler(scene.canvas);
 dragHandler=handler;
 const controller=scene.screenSpaceCameraController;
 function pick(position){
  const ray=viewer.camera.getPickRay(position);
  const intersect=scene.globe.pick(ray,scene);
  const cart=intersect||viewer.camera.pickEllipsoid(position,scene.globe.ellipsoid);
  if(!cart)return null;
  const p=C.Cartographic.fromCartesian(cart);
  return [p.latitude*deg,p.longitude*deg];
 }
 handler.setInputAction(event=>{
  const selected=scene.pick(event.position);
  const entity=selected?.id;
  if(entity&&Number.isInteger(entity._offsetWaypoint)){
   core().setActiveWaypoint(entity._offsetWaypoint);
   if($('sim-edit').checked){
    activeDrag={index:entity._offsetWaypoint};
    controller.enableInputs=false;
   }else{
    renderRoute();showCamera();
   }
  }else if(entity?._offsetTarget&&$('sim-edit').checked){
   activeDrag={target:true};controller.enableInputs=false;
  }
 },C.ScreenSpaceEventType.LEFT_DOWN);
 handler.setInputAction(event=>{
  if(!activeDrag)return;
  const p=pick(event.endPosition);if(!p)return;
  activeDrag.position=p;
 },C.ScreenSpaceEventType.MOUSE_MOVE);
 handler.setInputAction(event=>{
  const drag=activeDrag;activeDrag=null;controller.enableInputs=true;
  if(!drag?.position)return;
  if(drag.target)core().moveTarget(drag.position);
  else core().moveWaypoint(drag.index,drag.position);
 },C.ScreenSpaceEventType.LEFT_UP);
}
async function initialise(run){
 const C=await loadSDK();
 if(!enabled||run!==epoch)return;
 if(typeof C.FeatureDetection?.supportsWebgl==='function'&&!C.FeatureDetection.supportsWebgl())throw Error('3D WebGL is unavailable in this browser');
 C.Ion.defaultAccessToken='';
 const layer=new C.ImageryLayer(new C.OpenStreetMapImageryProvider({url:'https://tile.openstreetmap.org/'}));
 viewer=new C.Viewer('shot-preview-terrain',{
  baseLayerPicker:false,baseLayer:layer,terrainProvider:new C.EllipsoidTerrainProvider(),
  animation:false,timeline:false,homeButton:false,fullscreenButton:false,
  sceneModePicker:false,geocoder:false,infoBox:false,selectionIndicator:false,
  navigationHelpButton:false,vrButton:false,shouldAnimate:false,
  scene3DOnly:true,requestRenderMode:false
 });
 viewer.scene.globe.depthTestAgainstTerrain=false;
 buildings=new C.CustomDataSource('OSM approximated buildings');
 await viewer.dataSources.add(buildings);
 if(!enabled||run!==epoch)return;
 installEditing();
 overlay('');
 renderRoute();showCamera();
 loadBuildings(route(core()?.getShot())[core()?.getActiveWaypoint()]||route(core()?.getShot())[0],run);
 status('Drone camera ready · drag to orbit, or press Play flight.');
}
function destroy(){
 stop();
 buildingEpoch++;
 if(buildingAbort)buildingAbort.abort();
 if(dragHandler){dragHandler.destroy();dragHandler=null}
 if(viewer){viewer.destroy();viewer=null}
 buildings=null;buildingKey='';activeDrag=null;
}
function setExpanded(on){
 const panel=document.querySelector('.shot-preview-panel');
 if(!panel)return;
 panel.classList.toggle('sim-expanded',on);
 document.body.classList.toggle('simulator-expanded',on);
 $('sim-expand').textContent=on?'Close':'Expand';
 requestAnimationFrame(()=>requestAnimationFrame(()=>{
  if(enabled&&viewer){viewer.resize();if(!playing&&!overview)showCamera()}
 }));
}
$('sim-expand').addEventListener('click',()=>{
 const panel=document.querySelector('.shot-preview-panel');
 setExpanded(!panel?.classList.contains('sim-expanded'));
});
document.addEventListener('keydown',event=>{
 if(event.key==='Escape'&&document.querySelector('.shot-preview-panel.sim-expanded'))setExpanded(false);
});
function setMode(on){
 const s=core()?.getShot();
 if(on&&!route(s).length){status('Add a waypoint to start the 3D Simulator.');return}
 enabled=on;
 const run=++epoch;
 $('shot-preview-view').classList.toggle('terrain-enabled',on);
 $('shot-preview-terrain').hidden=!on;
 $('shot-preview-2dmap').hidden=on;
 $('preview-pitch-controls').hidden=!on;
 $('shot-simulator-controls').hidden=!on;
 $('preview-terrain-credit').hidden=!on;
 $('sim-expand').hidden=!on;
 if(!on)setExpanded(false);
 $('preview-3d-toggle').setAttribute('aria-pressed',String(on));
 $('preview-3d-toggle').textContent=on?'2D Map':'3D Simulator';
 document.dispatchEvent(new Event('offset:preview-mode-changed'));
 if(!on){
  overlay('');destroy();
  document.dispatchEvent(new Event('offset:map-rendered'));
  return;
 }
 overlay('Loading 3D Simulator…');
 initialise(run).catch(error=>{
  if(!enabled||run!==epoch)return;
  console.warn('OFFSET simulator:',error);
  overlay(error?.message||'3D Simulator unavailable. Switch back to 2D.');
  status('Could not initialise 3D. The 2D map is still available.');
 });
}
function refresh(){
 if(!enabled||!viewer)return;
 stop();renderRoute();showCamera();
 const points=route(core()?.getShot()),run=epoch;
 loadBuildings(points[core().getActiveWaypoint()]||points[0],run);
}
$('preview-3d-toggle').addEventListener('click',()=>setMode(!enabled));
$('preview-pitch-up').addEventListener('click',()=>movePitch(5));
$('preview-pitch-down').addEventListener('click',()=>movePitch(-5));
$play.addEventListener('click',play);
$('sim-reset').addEventListener('click',resetCamera);
$('sim-view').addEventListener('change',event=>{
 stop();overview=event.target.value==='overview';showCamera();
 status(overview?'Orbit overview: drag the city to inspect the route.':'Drone camera: view from the selected waypoint.');
});
$('sim-speed').addEventListener('input',event=>{
 $('sim-speed-value').textContent=event.target.value+' m/s';
});
document.addEventListener('offset:map-rendered',refresh);
document.addEventListener('offset:shot-rendered',refresh);
document.addEventListener('offset:preview-height-changed',refresh);
document.addEventListener('offset:planner-layout',()=>{if(enabled&&viewer)viewer.resize()});
window.addEventListener('resize',()=>{if(enabled&&viewer)viewer.resize()});
$('preview-aspect').addEventListener('change',()=>{if(enabled&&viewer)viewer.resize()});
})();