(()=>{'use strict';
/* OFFSET Shot Simulator v1 — Cesium camera path previsualisation.
   Building volumes come from voluntary OSM tags, not clearance-grade survey.
   Ground is the WGS84 ellipsoid until a licensed terrain source is configured.
   No token or Google Earth scraping. */
const $=id=>document.getElementById(id),core=()=>window.OFFSETPlanner;
const VERSION='1.135.0',BASE='https://cdn.jsdelivr.net/npm/cesium@'+VERSION+'/Build/Cesium/';
let viewer=null,buildingTileset=null,enabled=false,loadingSdk=null,epoch=0;
let terrainProvider=null,terrainHeights=[],terrainSignature='',terrainEpoch=0,terrainReady=false;
let playing=false,raf=0,lastTime=0,playElapsed=0,activeDrag=null,dragHandler=null;
let overview=false,buildingEpoch=0,visibleBuildingTiles=0;
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
function fov(s,index=core()?.getActiveWaypoint()){
 const cone=s?.waypointCones?.[index];
 const lens=Number(core()?.lensFov(s));
 const selected=cone?.lensAuto&&Number.isFinite(lens)&&lens>0?lens:Number(cone?.fov);
 const n=Number.isFinite(selected)&&selected>0?selected:(lens||50);
 return Math.max(5,Math.min(150,n));
}
function route(s){return (s?.route||[]).filter(valid)}
function groundAt(point,index){
 if(Number.isInteger(index)&&Number.isFinite(terrainHeights[index]))return terrainHeights[index];
 if(terrainReady&&viewer&&point){
  const C=window.Cesium;
  const ground=viewer.scene.globe.getHeight(C.Cartographic.fromDegrees(point[1],point[0]));
  if(Number.isFinite(ground))return ground;
 }
 return 0;
}
function heightAboveEllipsoid(s,point,index){return groundAt(point,index)+altitude(s,index)}
async function sampleRouteTerrain(run){
 if(!terrainProvider||!viewer||!enabled)return;
 const s=core()?.getShot(),points=route(s),signature=JSON.stringify(points);
 if(!points.length||terrainSignature===signature)return;
 const request=++terrainEpoch;
 terrainSignature=signature;
 const C=window.Cesium;
 try{
  const positions=points.map(p=>C.Cartographic.fromDegrees(p[1],p[0]));
  await C.sampleTerrainMostDetailed(terrainProvider,positions);
  if(!enabled||!viewer||run!==epoch||request!==terrainEpoch)return;
  terrainHeights=positions.map(p=>Number.isFinite(p.height)?p.height:0);
  renderRoute();
  if(!playing)showCamera();
 }catch(error){
  if(run!==epoch||request!==terrainEpoch||!enabled)return;
  console.warn('OFFSET terrain height sample:',error);
  status('Terrain heights unavailable. Drone altitude is approximate.');
 }
}
function coneBearing(s,index,position=null){
 const points=route(s),p=position||points[index],cone=s?.waypointCones?.[index];
 if(cone?.mode==='target'&&valid(s?.target)&&valid(p))return bearing(p,s.target);
 const manual=Number(cone?.bearing);
 if(Number.isFinite(manual))return ((manual%360)+360)%360;
 return valid(p)&&valid(points[index+1])?bearing(p,points[index+1]):0;
}
function interpolateBearing(a,b,t){
 const delta=((b-a+540)%360)-180;
 return (a+delta*t+360)%360;
}
function cameraDirection(s,index,t=0,position=null){
 const points=route(s),a=coneBearing(s,index,position||points[index]);
 if(index>=points.length-1)return a;
 // A target-following cone should keep looking at the target as the drone moves.
 const next=s?.waypointCones?.[index+1];
 const current=s?.waypointCones?.[index];
 if(current?.mode==='target'&&next?.mode==='target'&&valid(s.target)&&valid(position))
  return bearing(position,s.target);
 return interpolateBearing(a,coneBearing(s,index+1),t);
}
function cameraFov(s,index,t=0){
 if(index>=route(s).length-1)return fov(s,index);
 return fov(s,index)+(fov(s,index+1)-fov(s,index))*t;
}
function cameraAt(position,height,heading,tilt,horizontalFov){
 if(!viewer||!position)return;
 const C=window.Cesium,canvas=viewer.scene.canvas;
 const width=Math.max(1,canvas.clientWidth),high=Math.max(1,canvas.clientHeight);
 const aspect=width/high;
 const horizontal=Number.isFinite(horizontalFov)?horizontalFov:fov(core()?.getShot());
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
   destination:C.Cartesian3.fromDegrees(point[1],point[0],heightAboveEllipsoid(s,point,i)+115),
   orientation:{heading:core().getBearing()*rad,pitch:-58*rad,roll:0}
  });
 }else cameraAt(point,heightAboveEllipsoid(s,point,i),cameraDirection(s,i),pitch(s),cameraFov(s,i));
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
   positions:points.map((p,i)=>C.Cartesian3.fromDegrees(p[1],p[0],heightAboveEllipsoid(s,p,i))),
   width:3,material:C.Color.fromCssColorString('#58eb99'),
   clampToGround:false
  }
 });
 points.forEach((p,i)=>{
  const entry=viewer.entities.add({
   name:markerLabel(i),
   position:C.Cartesian3.fromDegrees(p[1],p[0],heightAboveEllipsoid(s,p,i)),
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
  position:C.Cartesian3.fromDegrees(s.target[1],s.target[0],groundAt(s.target,null)+3),
  point:{pixelSize:13,color:C.Color.fromCssColorString('#f3a978'),
    outlineColor:C.Color.BLACK,outlineWidth:2,disableDepthTestDistance:Number.POSITIVE_INFINITY},
  label:{text:'TARGET',font:'11px sans-serif',pixelOffset:new C.Cartesian2(0,-22),
    fillColor:C.Color.WHITE,disableDepthTestDistance:Number.POSITIVE_INFINITY}
 })._offsetTarget=true;
}
/* Re:Earth Buildings streams open Overture/OSM 3D Tiles directly into
   Cesium. The former Overpass proxy failed because Render lacked outbound
   network access and browsers encountered CORS failures. */
const BUILDING_TILESET_URL='https://buildings.reearth.land/tileset.json';
function buildingStatus(message){
 const node=$('sim-building-status');if(node)node.textContent=message;
}
function discardBuildingTileset(){
 buildingEpoch++;
 visibleBuildingTiles=0;
 if(buildingTileset){
  try{
   if(viewer&&!viewer.isDestroyed())viewer.scene.primitives.remove(buildingTileset);
   else if(!buildingTileset.isDestroyed())buildingTileset.destroy();
  }catch(error){console.warn('OFFSET building cleanup:',error)}
  buildingTileset=null;
 }
}
async function loadBuildings(point,run,force=false){
 if(!point||!viewer||!enabled)return;
 if(buildingTileset&&!force)return;
 if(force)discardBuildingTileset();
 const request=++buildingEpoch;
 buildingStatus('Connecting 3D building tiles…');
 try{
  const C=window.Cesium;
  const tileset=await C.Cesium3DTileset.fromUrl(BUILDING_TILESET_URL,{
   maximumScreenSpaceError:12,
   cacheBytes:96*1024*1024,
   maximumCacheOverflowBytes:32*1024*1024
  });
  if(!enabled||!viewer||run!==epoch||request!==buildingEpoch){
   if(!tileset.isDestroyed())tileset.destroy();return;
  }
  buildingTileset=tileset;
  viewer.scene.primitives.add(tileset);
  buildingStatus('3D source connected · loading nearby tiles…');
  tileset.tileVisible.addEventListener(()=>{
   if(request!==buildingEpoch||!enabled)return;
   if(visibleBuildingTiles++===0)
    buildingStatus('3D building tiles visible · Re:Earth / Overture');
  });
  tileset.tileFailed.addEventListener(error=>{
   if(!enabled||request!==buildingEpoch)return;
   console.warn('OFFSET building tile:',error);
   if(visibleBuildingTiles===0)
    buildingStatus('3D tile download failed · tap Reload buildings');
  });
 }catch(error){
  if(!enabled||run!==epoch||request!==buildingEpoch)return;
  console.warn('OFFSET 3D Tiles loading:',error);
  buildingStatus('Could not connect to 3D buildings · Reload buildings');
 }
}

function stop(reset=true){
 playing=false;cancelAnimationFrame(raf);raf=0;$play.textContent='▶ Play flight';lastTime=0;
 if(reset)playElapsed=0;
}
function play(){
 if(!viewer)return;
 if(playing){stop(false);status('Playback paused. Press Play to resume.');return}
 const s=core()?.getShot(),points=route(s);
 if(points.length<2){status('Add at least two waypoints to play a camera flight.');return}
 if(overview){overview=false;$('sim-view').value='camera'}
 const legs=points.slice(1).map((p,i)=>distance(points[i],p));
 const total=legs.reduce((sum,l)=>sum+l,0);
 if(total<.01){status('Waypoints must be in different positions.');return}
 const speed=Number($('sim-speed').value)||3;
 let elapsed=playElapsed;
 playing=true;$play.textContent='Ⅱ Pause';
 function frame(now){
  if(!playing||!enabled)return;
  if(lastTime)elapsed+=Math.min((now-lastTime)/1000,.1)*speed;
  lastTime=now;playElapsed=elapsed;
  let remaining=Math.min(elapsed,total),index=0;
  while(index<legs.length-1&&remaining>legs[index])remaining-=legs[index++];
  const t=legs[index]>0?Math.max(0,Math.min(1,remaining/legs[index])):1;
  const a=points[index],b=points[index+1];
  const here=[a[0]+(b[0]-a[0])*t,a[1]+(b[1]-a[1])*t];
  const h=altitude(s,index)+(altitude(s,index+1)-altitude(s,index))*t;
  const ground=groundAt(a,index)+(groundAt(b,index+1)-groundAt(a,index))*t;
  cameraAt(here,ground+h,cameraDirection(s,index,t,here),pitch(s),cameraFov(s,index,t));
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
 overlay('Loading matching terrain and buildings…');
 terrainProvider=await C.CesiumTerrainProvider.fromUrl(
  'https://terrain.reearth.land/cesium-mesh/ellipsoid',
  {requestVertexNormals:true}
 );
 if(!enabled||run!==epoch)return;
 terrainReady=true;
 const layer=new C.ImageryLayer(new C.OpenStreetMapImageryProvider({url:'https://tile.openstreetmap.org/'}));
 viewer=new C.Viewer('shot-preview-terrain',{
  baseLayerPicker:false,baseLayer:layer,terrainProvider:terrainProvider,
  animation:false,timeline:false,homeButton:false,fullscreenButton:false,
  sceneModePicker:false,geocoder:false,infoBox:false,selectionIndicator:false,
  navigationHelpButton:false,vrButton:false,shouldAnimate:false,
  scene3DOnly:true,requestRenderMode:false
 });
 viewer.scene.globe.depthTestAgainstTerrain=false;
 if(!enabled||run!==epoch)return;
 installEditing();
 overlay('');
 renderRoute();showCamera();
 sampleRouteTerrain(run);
 status('3D camera follows each waypoint camera cone · sampling terrain heights.');
 loadBuildings(route(core()?.getShot())[core()?.getActiveWaypoint()]||route(core()?.getShot())[0],run);
}
function destroy(){
 stop();
 discardBuildingTileset();
 terrainEpoch++;terrainHeights=[];terrainSignature='';terrainProvider=null;terrainReady=false;
 if(dragHandler){dragHandler.destroy();dragHandler=null}
 if(viewer){viewer.destroy();viewer=null}
 activeDrag=null;
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
 stop();
 const points=route(core()?.getShot()),run=epoch;
 const signature=JSON.stringify(points);
 if(signature!==terrainSignature){terrainHeights=[];terrainSignature='';}
 renderRoute();showCamera();
 sampleRouteTerrain(run);
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
$('sim-reload-buildings').addEventListener('click',()=>{
 const points=route(core()?.getShot()),at=points[core()?.getActiveWaypoint()]||points[0];
 if(!at){buildingStatus('Add a waypoint first');return}
 loadBuildings(at,epoch,true);
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