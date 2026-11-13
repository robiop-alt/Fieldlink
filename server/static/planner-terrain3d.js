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
let currentImageryLayer=null,flightMarker=null,flightRadiusCircle=null;
let lastQualityChange=0,frameInterval=16;
const imageryKeyStore='offset-planner-maptiler-key-v1';
let maptilerKey='';
const ionKeyStore='offset-planner-cesium-ion-read-token-v1';
const GOOGLE_ASSET_ID=2275207;
let ionReadToken='';
try{ionReadToken=localStorage.getItem(ionKeyStore)||''}catch{}
try{maptilerKey=localStorage.getItem(imageryKeyStore)||''}catch{}
let sharedIonTokenPromise=null;
async function loadSharedIonToken(){
 if(sharedIonTokenPromise)return sharedIonTokenPromise;
 sharedIonTokenPromise=(async()=>{
  try{
   const res=await fetch('/api/admin/planner/cesium-token',{
    credentials:'same-origin',cache:'no-store'
   });
   if(!res.ok)throw Error('Shared token not available');
   const data=await res.json();
   if(data.token){
    ionReadToken=data.token;
    $('sim-cesium-hint').textContent='Cesium token available on all your signed-in devices.';
   }else if(ionReadToken){
    // First private device already has a token: securely migrate it once.
    await saveSharedIonToken(ionReadToken);
   }
   $('sim-cesium-key').value=ionReadToken;
  }catch(error){
   console.warn('OFFSET private Cesium key sync unavailable:',error?.message);
   $('sim-cesium-hint').textContent=ionReadToken
    ?'Using local token · shared sync unavailable.'
    :'Enter an ion assets:read token and press Apply to share across devices.';
  }
  return ionReadToken;
 })();
 return sharedIonTokenPromise;
}
async function saveSharedIonToken(token){
 const res=await fetch('/api/admin/planner/cesium-token',{
  method:'PUT',credentials:'same-origin',cache:'no-store',
  headers:{'Content-Type':'application/json'},
  body:JSON.stringify({token})
 });
 if(!res.ok)throw Error('Could not save token on OFFSET server ('+res.status+')');
 try{localStorage.removeItem(ionKeyStore)}catch{}
 $('sim-cesium-hint').textContent='Saved for all your signed-in devices · private preview only.';
 return true;
}

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
function pitch(s,i=core()?.getActiveWaypoint()){
 const wp=s?.waypointCones?.[i]?.tilt;
 const raw=wp===undefined||wp===null||wp===''?s?.simPitch:wp;
 const n=Number(raw);
 return Number.isFinite(n)?Math.max(-85,Math.min(35,n)):-10;
}
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
// Distance-parameterized cubic Hermite curves keep both the position and
// first derivative continuous at waypoints. Every saved waypoint remains fixed.
function smoothSegment(values,legs,index,t,amount){
 const a=Number(values[index]),b=Number(values[index+1]);
 if(amount<=0||!Number.isFinite(a)||!Number.isFinite(b))return a+(b-a)*t;
 const length=Math.max(.001,legs[index]||0);
 const previous=index>0?Math.max(.001,legs[index-1]):length;
 const next=index+1<legs.length?Math.max(.001,legs[index+1]):length;
 const incoming=index>0?(b-values[index-1])/(previous+length):(b-a)/length;
 const outgoing=index+2<values.length?(values[index+2]-a)/(length+next):(b-a)/length;
 const m0=incoming*length*amount,m1=outgoing*length*amount;
 const t2=t*t,t3=t2*t;
 return (2*t3-3*t2+1)*a+(t3-2*t2+t)*m0+
  (-2*t3+3*t2)*b+(t3-t2)*m1;
}
function smoothHeadings(s,points){
 const headings=points.map((p,i)=>coneBearing(s,i,p));
 for(let i=1;i<headings.length;i++){
  const delta=((headings[i]-headings[i-1]+540)%360)-180;
  headings[i]=headings[i-1]+delta;
 }
 return headings;
}
function flightSmoothing(){
 return $('sim-smoothing')?.value==='direct'?0:
  $('sim-smoothing')?.value==='smooth'?.7:1;
}
function motionDistance(timeDistance,total){
 if(total<=0)return 0;
 const u=Math.max(0,Math.min(1,timeDistance/total));
 return total*(u-flightSmoothing()*.11*Math.sin(2*Math.PI*u));
}
function timeForDistance(distance,total){
 if(total<=0)return 0;
 let lo=0,hi=1;
 for(let i=0;i<18;i++){
  const mid=(lo+hi)/2;
  if(motionDistance(mid*total,total)<distance)lo=mid;else hi=mid;
 }
 return (lo+hi)*.5*total;
}
function prepareFlight(plan){
 if(plan.prepared)return plan.prepared;
 const {shot:s,points}=plan;
 plan.prepared={
  headings:smoothHeadings(s,points),
  lats:points.map(p=>p[0]),lons:points.map(p=>p[1]),
  heights:points.map((p,i)=>altitude(s,i)),
  groundHeights:points.map((p,i)=>groundAt(p,i)),
  fovs:points.map((p,i)=>fov(s,i)),
  tilts:points.map((p,i)=>pitch(s,i))
 };
 return plan.prepared;
}
function flightPathPoint(plan,index,t){
 const {legs}=plan,smooth=flightSmoothing();
 const {lats,lons,heights,groundHeights}=prepareFlight(plan);
 return [
  smoothSegment(lats,legs,index,t,smooth),
  smoothSegment(lons,legs,index,t,smooth),
  smoothSegment(heights,legs,index,t,smooth)+
   smoothSegment(groundHeights,legs,index,t,smooth)
 ];
}
function setHud(info){
 const node=$('sim-camera-hud');
 if(!node)return;
 node.hidden=!enabled||!$('sim-hud-toggle').checked||overview;
 if(node.hidden||!info)return;
 $('sim-hud-waypoint').textContent=info.waypoint;
 $('sim-hud-lens').textContent=info.lens||'Lens —';
 $('sim-hud-fps').textContent=info.fps||'FPS —';
 $('sim-hud-altitude').textContent=Math.round(info.height)+' m AGL';
 $('sim-hud-heading').textContent='HDG '+String(Math.round((info.heading%360+360)%360)).padStart(3,'0')+'°';
 $('sim-hud-tilt').textContent='Tilt '+Math.round(info.tilt)+'°';
 $('sim-hud-fov').textContent='FOV '+Math.round(info.fov)+'°';
}
function lensLabel(s){return String(s?.lens||'Lens —').slice(0,28)}
function fpsLabel(s){const fps=Number(s?.fps);return Number.isFinite(fps)&&fps>0?'FPS '+fps:'FPS —'}
function updateFlightMarker(position,aglHeight,terrainHeight){
 if(!window.Cesium||!position)return;
 if(Number.isFinite(aglHeight)&&aglHeight>=0){
  document.dispatchEvent(new CustomEvent('offset:flight-position',{
   detail:{lat:position[0],lng:position[1],radius:aglHeight}
  }));
 }
 const C=window.Cesium;
 if(flightMarker){
  flightMarker.position=C.Cartesian3.fromDegrees(position[1],position[0],position[2]);
  flightMarker.show=overview;
 }
 // One real-world metre horizontally per metre of height above local ground.
 // The ellipse dimensions are radii in metres, never pixel sizes or diameters.
 if(flightRadiusCircle){
  const radius=Number(aglHeight);
  if(!Number.isFinite(radius)||radius<0){flightRadiusCircle.show=false;return}
  const ground=Number.isFinite(terrainHeight)?terrainHeight:position[2]-radius;
  flightRadiusCircle.position=C.Cartesian3.fromDegrees(position[1],position[0],ground);
  flightRadiusCircle.ellipse.semiMajorAxis=Math.max(.1,radius);
  flightRadiusCircle.ellipse.semiMinorAxis=Math.max(.1,radius);
  flightRadiusCircle.ellipse.height=ground+.35;
  flightRadiusCircle.show=overview;
 }
}
function renderWaypointTicks(plan=flightData()){
 const layer=$('sim-timeline-waypoints');
 if(!layer)return;
 layer.replaceChildren();
 if(!plan?.total||plan.points.length<2)return;
 let distance=0;
 for(let i=0;i<plan.points.length;i++){
  if(i>0)distance+=plan.legs[i-1]||0;
  const percent=100*timeForDistance(distance,plan.total)/plan.total;
  const mark=document.createElement('button');
  mark.type='button';mark.className='sim-waypoint-tick';
  mark.style.left=percent.toFixed(3)+'%';
  mark.title='Waypoint '+(i+1);
  mark.setAttribute('aria-label','Jump to waypoint '+(i+1));
  mark.addEventListener('click',event=>{
   event.preventDefault();event.stopPropagation();
   scrubFlight(String(10*percent));
  });
  layer.append(mark);
 }
}
function applyQuality(){
 if(!viewer||viewer.isDestroyed())return;
 const setting=$('sim-quality')?.value||'auto';
 const mobile=window.matchMedia('(pointer:coarse)').matches||
  window.matchMedia('(max-width:900px)').matches;
 viewer.resolutionScale=setting==='quality'?1.05:setting==='performance'?.68:mobile?.82:1;
 if(buildingTileset){
  buildingTileset.maximumScreenSpaceError=setting==='quality'?11:setting==='performance'?27:mobile?19:14;
 }
 lastQualityChange=performance.now();frameInterval=16;
}
function adaptiveQuality(now,dt){
 if($('sim-quality').value!=='auto'||!viewer||now-lastQualityChange<3200)return;
 frameInterval=frameInterval*.92+dt*.08;
 if(frameInterval>38&&viewer.resolutionScale>.64){
  viewer.resolutionScale=Math.max(.64,viewer.resolutionScale-.09);
  if(buildingTileset)buildingTileset.maximumScreenSpaceError=22;
  lastQualityChange=now;
 }
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
 }else cameraAt(point,heightAboveEllipsoid(s,point,i),cameraDirection(s,i),pitch(s,i),cameraFov(s,i));
 setHud({waypoint:'WP '+(i+1)+' / '+points.length,lens:lensLabel(s),fps:fpsLabel(s),
  height:altitude(s,i),heading:cameraDirection(s,i),tilt:pitch(s,i),fov:fov(s,i)});
 updateFlightMarker([point[0],point[1],heightAboveEllipsoid(s,point,i)],altitude(s,i),groundAt(point,i));
 $('preview-pitch-value').textContent='Tilt '+Math.round(pitch(s,i))+'°';
 if($('sim-waypoint-tilt')){
  $('sim-waypoint-tilt').value=String(Math.round(pitch(s,i)));
  $('sim-waypoint-tilt-label').textContent=Math.round(pitch(s,i))+'°';
 }
}
function resetCamera(){stop();showCamera();status(overview?'Orbit overview. Drag to inspect the buildings.':'Drone camera at selected waypoint.')}
function markerLabel(index){return 'WP '+(index+1)}
function renderRoute(){
 if(!viewer)return;
 const C=window.Cesium,s=core()?.getShot(),points=route(s);
 viewer.entities.removeAll();flightMarker=null;flightRadiusCircle=null;
 if(!points.length)return;
 if(points.length>1){
  const plan=flightData(),samples=[];
  const perLeg=Math.max(3,Math.min(14,Math.floor(400/plan.legs.length)));
  for(let i=0;i<plan.legs.length;i++){
   for(let k=0;k<perLeg;k++){
    const p=flightPathPoint(plan,i,k/perLeg);
    samples.push(C.Cartesian3.fromDegrees(p[1],p[0],p[2]));
   }
  }
  const last=points.length-1,p=points[last];
  samples.push(C.Cartesian3.fromDegrees(p[1],p[0],heightAboveEllipsoid(s,p,last)));
  viewer.entities.add({polyline:{positions:samples,width:3,
   material:C.Color.fromCssColorString('#58eb99').withAlpha(.78),clampToGround:false}});
  const first=points[0];
  // Footprint ring stays on the approximate ground plane beneath the drone.
  // Radius = current AGL altitude (10 m -> 10 m, 100 m -> 100 m).
  flightRadiusCircle=viewer.entities.add({
   name:'Drone height footprint · radius equals height AGL',
   position:C.Cartesian3.fromDegrees(first[1],first[0],groundAt(first,0)),
   ellipse:{
    semiMajorAxis:Math.max(.1,altitude(s,0)),
    semiMinorAxis:Math.max(.1,altitude(s,0)),
    height:groundAt(first,0)+.35,
    material:C.Color.fromCssColorString('#79e8aa').withAlpha(.07),
    outline:true,outlineColor:C.Color.fromCssColorString('#a9ffd1').withAlpha(.86),
    outlineWidth:2
   },
   show:overview
  });
  flightMarker=viewer.entities.add({
   name:'Drone position',
   position:C.Cartesian3.fromDegrees(first[1],first[0],heightAboveEllipsoid(s,first,0)),
   point:{pixelSize:13,color:C.Color.fromCssColorString('#f5e5a4'),
    outlineColor:C.Color.fromCssColorString('#183d30'),outlineWidth:3,
    disableDepthTestDistance:Number.POSITIVE_INFINITY},
   label:{text:'DRONE',font:'bold 10px sans-serif',
    pixelOffset:new C.Cartesian2(0,-24),fillColor:C.Color.WHITE,
    disableDepthTestDistance:Number.POSITIVE_INFINITY},
   show:overview
  });
 }
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
 renderWaypointTicks(flightData());
}

/* Re:Earth Buildings streams open Overture/OSM 3D Tiles directly into
   Cesium. The former Overpass proxy failed because Render lacked outbound
   network access and browsers encountered CORS failures. */
const BUILDING_TILESET_URL='https://buildings.reearth.land/tileset.json';
function configureBuildings(){
 if(!buildingTileset||!window.Cesium||$('sim-3d-source').value==='google')return;
 const C=window.Cesium,choice=$('sim-building-style').value;
 // Add ambient sky illumination to dark façades. This is diffuse fill,
 // separate from the main directional source; do not flatten all shading.
 if(buildingTileset.imageBasedLighting){
  buildingTileset.imageBasedLighting.imageBasedLightingFactor=new C.Cartesian2(2.5,.8);
  buildingTileset.imageBasedLighting.luminanceAtZenith=.65;
 }
 // Many Re:Earth / Overture tiles do not expose per-feature `height`.
 // Height-dependent style conditions previously crashed Cesium rendering.
 // Use safe, consistent colors that preserve surface normal shading.
 const colors={
  original:"color('white')",
  natural:"color('#d4c8b7')",
  contrast:"color('#a9b7ba')"
 };
 const color=colors[choice]||colors.original;
 buildingTileset.style=new C.Cesium3DTileStyle({color});

}
function satelliteLayer(C){
 return new C.ImageryLayer(new C.UrlTemplateImageryProvider({
  url:'https://api.maptiler.com/maps/satellite-v4/{z}/{x}/{y}.jpg?key='+encodeURIComponent(maptilerKey),
  minimumLevel:0,maximumLevel:20,tileWidth:512,tileHeight:512,
  credit:new C.Credit('Satellite imagery © MapTiler; imagery providers')
 }));
}
function streetLayer(C){
 return new C.ImageryLayer(new C.OpenStreetMapImageryProvider({
  url:'https://tile.openstreetmap.org/',
  credit:'© OpenStreetMap contributors'
 }));
}
function setImageryMode(){
 const keyrow=$('sim-imagery-key-row');
 const satellite=$('sim-imagery').value==='satellite';
 keyrow.hidden=!satellite||$('sim-3d-source').value==='google';
 if(!viewer||$('sim-3d-source').value==='google')return;
 if(satellite&&!maptilerKey.trim()){
  buildingStatus('Satellite requires your MapTiler API key');
  return;
 }
 const C=window.Cesium;
 try{
  const next=satellite?satelliteLayer(C):streetLayer(C);
  const layers=viewer.imageryLayers;
  layers.add(next,0);
  if(currentImageryLayer&&layers.contains(currentImageryLayer))layers.remove(currentImageryLayer,true);
  currentImageryLayer=next;
  $('sim-imagery-hint').textContent=satellite?'Satellite images provided by MapTiler.':'Street map selected.';
 }catch(error){
  console.warn('OFFSET imagery:',error);
  $('sim-imagery-hint').textContent='Could not switch imagery: '+(error?.message||'error');
 }
}
function showGoogleControls(){
 const google=$('sim-3d-source').value==='google';
 $('sim-cesium-key-row').hidden=!google;
 $('sim-imagery').disabled=google;
 $('sim-building-style').disabled=google;
 $('sim-imagery-key-row').hidden=google||$('sim-imagery').value!=='satellite';
}
async function switch3dSource(){
 showGoogleControls();
 if($('sim-3d-source').value==='google')await loadSharedIonToken();
 if(!viewer||!enabled)return;
 const points=route(core()?.getShot()),at=points[core()?.getActiveWaypoint()]||points[0];
 if(!at)return;
 if($('sim-3d-source').value==='google'&&!ionReadToken.trim()){
  buildingStatus('Enter your Cesium ion token and press Apply');return;
 }
 loadBuildings(at,epoch,true);
}
function configureSky(google){
 if(!viewer||viewer.isDestroyed())return;
 const C=window.Cesium,scene=viewer.scene;
 // Photogrammetry replaces the globe, so Cesium's normal Earth atmosphere
 // may not render. A non-black sky background remains visible above the mesh.
 scene.backgroundColor=C.Color.fromCssColorString('#a8cfe5');
 if(scene.skyBox)scene.skyBox.show=false;
 if(scene.skyAtmosphere)scene.skyAtmosphere.show=!google;
 if(scene.sun)scene.sun.show=false;
 if(scene.moon)scene.moon.show=false;
}
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
 const google=$('sim-3d-source').value==='google';
 if(google&&!ionReadToken.trim()){
  buildingStatus('Paste a Cesium ion assets:read token, then Apply');return;
 }
 if(buildingTileset&&!force)return;
 if(force)discardBuildingTileset();
 const request=++buildingEpoch;
 viewer.scene.globe.show=true;
 buildingStatus(google?'Connecting Google Photorealistic 3D Tiles…':'Connecting Re:Earth buildings…');
 try{
  const C=window.Cesium;
  const tileset=google
   ?await C.Cesium3DTileset.fromUrl(
     await C.IonResource.fromAssetId(GOOGLE_ASSET_ID,{accessToken:ionReadToken}),
     {maximumScreenSpaceError:16,cacheBytes:96*1024*1024,
      maximumCacheOverflowBytes:32*1024*1024,showCreditsOnScreen:true}
    )
   :await C.Cesium3DTileset.fromUrl(BUILDING_TILESET_URL,{
     maximumScreenSpaceError:12,cacheBytes:96*1024*1024,
     maximumCacheOverflowBytes:32*1024*1024
    });
  if(!enabled||!viewer||run!==epoch||request!==buildingEpoch){
   if(!tileset.isDestroyed())tileset.destroy();return;
  }
  buildingTileset=tileset;
  viewer.scene.primitives.add(tileset);
  applyQuality();
  if(google){
   // The Google mesh contains its own terrain and textures.
   viewer.scene.globe.show=false;
  }else{
   viewer.scene.globe.show=true;
   configureBuildings();
  }
  configureSky(google);
  buildingStatus(google?'Google 3D connected · streaming photo tiles…':'Re:Earth connected · loading buildings…');
  tileset.tileVisible.addEventListener(()=>{
   if(request!==buildingEpoch||!enabled)return;
   if(visibleBuildingTiles++===0)
    buildingStatus(google?'Google photorealistic tiles visible':'Re:Earth building tiles visible');
  });
  tileset.tileFailed.addEventListener(error=>{
   if(!enabled||request!==buildingEpoch)return;
   console.warn('OFFSET 3D tile:',error);
   if(visibleBuildingTiles===0)
    buildingStatus(google?'Google 3D tile denied/unavailable · check ion access':'3D tile download failed · Reload buildings');
  });
 }catch(error){
  if(!enabled||run!==epoch||request!==buildingEpoch)return;
  const message=String(error?.message||'');
  console.warn('OFFSET 3D source failed',google?'Google':'Re:Earth',message.slice(0,100).replaceAll(ionReadToken||'__never__','[token]'));
  buildingStatus(google?'Google 3D unavailable · check token and asset':'Re:Earth buildings unavailable · Reload');
  if(google){
   viewer.scene.globe.show=true;
   configureSky(false);
   $('sim-3d-source').value='reearth';
   showGoogleControls();
   loadBuildings(point,run,true);
  }
 }
}

const PLAY_ICON='<svg class="sim-play-icon" viewBox="0 0 24 24" width="16" height="16" aria-hidden="true"><path d="M8 5.5L18 12 8 18.5Z" fill="currentColor"/></svg>';
const PAUSE_ICON='<svg class="sim-play-icon" viewBox="0 0 24 24" width="16" height="16" aria-hidden="true"><rect x="6" y="5" width="4" height="14" rx="1" fill="currentColor"/><rect x="14" y="5" width="4" height="14" rx="1" fill="currentColor"/></svg>';
function updatePlayButton(){
 $play.innerHTML=(playing?PAUSE_ICON:PLAY_ICON)+'<span class="sim-play-label">'+(playing?'Pause':'Play')+'</span>';
 $play.setAttribute('aria-label',playing?'Pause flight':'Play flight');
 $play.title=playing?'Pause flight':'Play flight';
}
function flightData(){
 const shot=core()?.getShot(),points=route(shot);
 const legs=points.slice(1).map((p,i)=>distance(points[i],p));
 const total=legs.reduce((sum,l)=>sum+l,0);
 return {shot,points,legs,total};
}
function clock(seconds){
 const n=Math.max(0,Math.floor(Number(seconds)||0));
 return Math.floor(n/60)+':'+String(n%60).padStart(2,'0');
}
function syncTimeline(total){
 const speed=Math.max(1,Number($('sim-speed').value)||3);
 const distance=Math.min(Math.max(0,playElapsed),total);
 const current=total>0?distance/speed:0,full=total/speed;
 const seek=$('sim-seek');
 seek.value=total>0?String(Math.round(distance/total*1000)):'0';
 seek.disabled=total<=0;
 seek.style.setProperty('--seek-progress',seek.value/10+'%');
 $('sim-time-current').textContent=clock(current);
 $('sim-time-total').textContent=clock(full);
 seek.setAttribute('aria-valuetext',clock(current)+' of '+clock(full));
}
function drawFlight(distance,plan=flightData()){
 const {shot:s,points,legs,total}=plan;
 if(!viewer||points.length<2||total<=0)return;
 let remaining=Math.min(Math.max(0,distance),total),index=0;
 while(index<legs.length-1&&remaining>legs[index])remaining-=legs[index++];
 const t=legs[index]>0?Math.max(0,Math.min(1,remaining/legs[index])):1;
 const smooth=flightSmoothing();
 const {headings,lats,lons,heights,groundHeights,fovs,tilts}=prepareFlight(plan);
 const here=[
  smoothSegment(lats,legs,index,t,smooth),
  smoothSegment(lons,legs,index,t,smooth)
 ];
 const h=smoothSegment(heights,legs,index,t,smooth);
 const ground=smoothSegment(groundHeights,legs,index,t,smooth);
 const current=s?.waypointCones?.[index],next=s?.waypointCones?.[index+1];
 const heading=current?.mode==='target'&&next?.mode==='target'&&valid(s.target)
  ?bearing(here,s.target):smoothSegment(headings,legs,index,t,smooth);
 const horizontalFov=Math.max(5,Math.min(150,smoothSegment(fovs,legs,index,t,smooth)));
 const tilt=smoothSegment(tilts,legs,index,t,smooth);
 cameraAt(here,ground+h,heading,tilt,horizontalFov);
 updateFlightMarker([here[0],here[1],ground+h],h,ground);
 setHud({waypoint:'WP '+(index+1)+' → '+(index+2),lens:lensLabel(s),fps:fpsLabel(s),
  height:h,heading,tilt,fov:horizontalFov});
 return Math.round(h);
}
function stop(reset=true){
 playing=false;cancelAnimationFrame(raf);raf=0;lastTime=0;
 if(reset)playElapsed=0;
 updatePlayButton();syncTimeline(flightData().total);
}
function play(){
 if(!viewer)return;
 if(playing){stop(false);status('Playback paused · drag the timeline to scrub');return}
 const plan=flightData();
 if(plan.points.length<2||plan.total<=0){status('Add two different waypoints to play a camera flight.');return}
 if(playElapsed>=plan.total)playElapsed=0;
 if(overview){overview=false;$('sim-view').value='camera'}
 playing=true;lastTime=0;updatePlayButton();
 function frame(now){
  if(!playing||!enabled)return;
  const speed=Math.max(1,Number($('sim-speed').value)||3);
  if(lastTime){
   const dt=Math.min((now-lastTime),100);
   playElapsed+=dt/1000*speed;
   adaptiveQuality(now,dt);
  }
  lastTime=now;
  playElapsed=Math.min(playElapsed,plan.total);
  const h=drawFlight(motionDistance(playElapsed,plan.total),plan);
  syncTimeline(plan.total);
  const pct=Math.min(100,Math.round(playElapsed/plan.total*100));
  status('Playing shot · '+pct+'% · '+Math.round(h||0)+' m AGL');
  if(playElapsed>=plan.total){
   stop(false);status('Shot playback complete · drag timeline or press Play to replay');return;
  }
  raf=requestAnimationFrame(frame);
 }
 raf=requestAnimationFrame(frame);
}
function scrubFlight(value){
 const plan=flightData();
 if(!viewer||plan.total<=0)return;
 if(overview){overview=false;$('sim-view').value='camera'}
 playElapsed=plan.total*Math.max(0,Math.min(1000,Number(value)||0))/1000;
 lastTime=0;
 drawFlight(motionDistance(playElapsed,plan.total),plan);
 syncTimeline(plan.total);
 if(!playing)status('Preview at '+clock(playElapsed/(Number($('sim-speed').value)||3))+' · press Play to continue');
}

function setWaypointPitch(value){
 const s=core()?.getShot();if(!s)return;
 const i=core()?.getActiveWaypoint(),n=Number(value);
 if(!Number.isInteger(i)||!Number.isFinite(n))return;
 if(!Array.isArray(s.waypointCones))s.waypointCones=[];
 if(!s.waypointCones[i])s.waypointCones[i]={};
 s.waypointCones[i].tilt=Math.max(-85,Math.min(35,Math.round(n)));
 const v=s.waypointCones[i].tilt;
 $('preview-pitch-value').textContent='Tilt '+v+'°';
 $('sim-waypoint-tilt-label').textContent=v+'°';
 $('sim-waypoint-tilt').value=String(v);
 if($('cone-tilt'))$('cone-tilt').value=String(v);
 core().save();
 if(viewer&&!playing&&!overview)showCamera();
}
function movePitch(delta){
 setWaypointPitch(pitch(core()?.getShot())+delta);
}
function installEditing(){
 if(!viewer)return;
 const C=window.Cesium,scene=viewer.scene,handler=new C.ScreenSpaceEventHandler(scene.canvas);
 dragHandler=handler;
 const controller=scene.screenSpaceCameraController;
 const canvas=scene.canvas;
 function pick(position){
  if(!position)return null;
  const ray=viewer.camera.getPickRay(position);
  if(!ray)return null;
  const globe=scene.globe.pick(ray,scene);
  const cart=globe||viewer.camera.pickEllipsoid(position,scene.globe.ellipsoid);
  if(!cart)return null;
  const p=C.Cartographic.fromCartesian(cart);
  return [p.latitude*deg,p.longitude*deg];
 }
 function markerAt(position){
  const hits=scene.drillPick(position,12);
  for(const hit of hits){
   const entity=hit?.id;
   if(entity?._offsetTarget)return {target:true,entity};
   if(Number.isInteger(entity?._offsetWaypoint))
    return {index:entity._offsetWaypoint,entity};
  }
  // Finger taps need a wider target than the tiny rendered 12px waypoint dot.
  // Screen-space nearest-marker fallback also works when a 3D tile occludes a dot.
  const points=route(core()?.getShot());
  let best=null,bestDistance=28;
  for(let i=0;i<points.length;i++){
   const p=points[i],screen=C.SceneTransforms.worldToWindowCoordinates(
    scene,C.Cartesian3.fromDegrees(p[1],p[0],heightAboveEllipsoid(core()?.getShot(),p,i))
   );
   if(!screen)continue;
   const d=Math.hypot(screen.x-position.x,screen.y-position.y);
   if(d<bestDistance){bestDistance=d;best={index:i}}
  }
  const target=core()?.getShot()?.target;
  if(valid(target)){
   const screen=C.SceneTransforms.worldToWindowCoordinates(
    scene,C.Cartesian3.fromDegrees(target[1],target[0],groundAt(target,null)+3)
   );
   if(screen&&Math.hypot(screen.x-position.x,screen.y-position.y)<bestDistance)
    best={target:true};
  }
  return best;
 }
 function begin(event){
  const hit=markerAt(event.position);
  if(!hit)return;
  if(!$('sim-edit').checked){
   if(Number.isInteger(hit.index)){
    core().setActiveWaypoint(hit.index);
    renderRoute();showCamera();
   }
   return;
  }
  stop();
  const s=core()?.getShot(),points=route(s);
  activeDrag={
   ...hit,
   original:hit.target?s.target:points[hit.index],
   position:null
  };
  controller.enableInputs=false;
  canvas.style.touchAction='none';
  status('Move point · drag to a new location, then release to save');
 }
 function move(event){
  if(!activeDrag)return;
  const p=pick(event.endPosition);
  if(!valid(p))return;
  activeDrag.position=p;
  const s=core()?.getShot();
  if(activeDrag.target){
   const ent=viewer.entities.values.find(e=>e._offsetTarget);
   if(ent)ent.position=C.Cartesian3.fromDegrees(p[1],p[0],groundAt(p,null)+3);
  }else{
   const ent=viewer.entities.values.find(e=>e._offsetWaypoint===activeDrag.index);
   if(ent)ent.position=C.Cartesian3.fromDegrees(
    p[1],p[0],heightAboveEllipsoid(s,p,activeDrag.index));
  }
 }
 function finish(event){
  if(!activeDrag)return;
  const drag=activeDrag;
  // A quick touch may end without a MOVE event; release is not a mutation then.
  const finalPoint=drag.position||(event?.position&&pick(event.position));
  activeDrag=null;
  controller.enableInputs=true;
  if(!drag.position||!valid(finalPoint)){
   status('Point unchanged · drag a marker to reposition it');
   return;
  }
  if(drag.target)core().moveTarget(finalPoint);
  else core().moveWaypoint(drag.index,finalPoint);
  status('Point moved · saved to 2D map');
 }
 handler.setInputAction(begin,C.ScreenSpaceEventType.LEFT_DOWN);
 handler.setInputAction(move,C.ScreenSpaceEventType.MOUSE_MOVE);
 handler.setInputAction(finish,C.ScreenSpaceEventType.LEFT_UP);
 // When a touch is cancelled or the finger leaves the canvas, never leave
 // Cesium's normal navigation disabled.
 canvas.addEventListener('touchcancel',()=>{
  activeDrag=null;controller.enableInputs=true;
 },{passive:true});
}

async function initialise(run){
 await loadSharedIonToken();
 if(!enabled||run!==epoch)return;
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
 const layer=$('sim-imagery').value==='satellite'&&maptilerKey?satelliteLayer(C):streetLayer(C);
 viewer=new C.Viewer('shot-preview-terrain',{
  baseLayerPicker:false,baseLayer:layer,terrainProvider:terrainProvider,
  animation:false,timeline:false,homeButton:false,fullscreenButton:false,
  sceneModePicker:false,geocoder:false,infoBox:false,selectionIndicator:false,
  navigationHelpButton:false,vrButton:false,shouldAnimate:false,
  scene3DOnly:true,requestRenderMode:false
 });
 currentImageryLayer=layer;
 applyQuality();
 showGoogleControls();
 configureSky(false);
 viewer.scene.globe.depthTestAgainstTerrain=false;
 // This is a shot-planning preview, not an astronomical lighting simulation.
 // Prevent night-time globe darkness and harsh sun-dependent building shadows.
 viewer.scene.globe.enableLighting=false;
 viewer.shadows=false;
 viewer.scene.shadowMap.enabled=false;
 // Place the key light relative to the selected location, not fixed Earth
 // coordinates (which can point underground in Norway).
 const lightAt=route(core()?.getShot())[0]||[59.91,10.75];
 const globeUp=C.Cartesian3.normalize(
  C.Cartesian3.fromDegrees(lightAt[1],lightAt[0],0),new C.Cartesian3()
 );
 const east=new C.Cartesian3(
  -Math.sin(lightAt[1]*rad),Math.cos(lightAt[1]*rad),0
 );
 const lightDirection=C.Cartesian3.normalize(
  C.Cartesian3.add(
   C.Cartesian3.multiplyByScalar(globeUp,-.9,new C.Cartesian3()),
   C.Cartesian3.multiplyByScalar(east,-.35,new C.Cartesian3()),
   new C.Cartesian3()
  ),new C.Cartesian3()
 );
 viewer.scene.light=new C.DirectionalLight({
  direction:lightDirection,intensity:1.25
 });
 viewer.scene.fog.enabled=false;
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
 currentImageryLayer=null;flightMarker=null;flightRadiusCircle=null;
 activeDrag=null;
}
function setExpanded(on){
 const panel=document.querySelector('.shot-preview-panel');
 if(!panel)return;
 panel.classList.toggle('sim-expanded',on);
 panel.classList.remove('sim-expanded-settings');
 document.body.classList.toggle('simulator-expanded',on);
 $('sim-expand').textContent=on?'Close':'Expand';
 $('sim-landscape-settings').hidden=!on;
 $('sim-landscape-settings').textContent='Settings';
 $('sim-landscape-settings').setAttribute('aria-expanded','false');
 requestAnimationFrame(()=>requestAnimationFrame(()=>{
  if(enabled&&viewer){viewer.resize();if(!playing&&!overview)showCamera()}
 }));
}
$('sim-landscape-settings').addEventListener('click',()=>{
 const panel=document.querySelector('.shot-preview-panel');
 if(!panel?.classList.contains('sim-expanded'))return;
 const open=panel.classList.toggle('sim-expanded-settings');
 $('sim-landscape-settings').textContent=open?'Hide settings':'Settings';
 $('sim-landscape-settings').setAttribute('aria-expanded',String(open));
 requestAnimationFrame(()=>{if(viewer&&!viewer.isDestroyed())viewer.resize()});
});
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
syncTimeline(flightData().total);
$('preview-3d-toggle').addEventListener('click',()=>setMode(!enabled));
$('preview-pitch-up').addEventListener('click',()=>movePitch(5));
$('preview-pitch-down').addEventListener('click',()=>movePitch(-5));
$('sim-waypoint-tilt').addEventListener('input',event=>setWaypointPitch(event.target.value));
$play.addEventListener('click',play);
$('sim-seek').addEventListener('input',event=>scrubFlight(event.target.value));

$('sim-reset').addEventListener('click',resetCamera);
$('sim-view').addEventListener('change',event=>{
 stop();overview=event.target.value==='overview';showCamera();
 status(overview?'Orbit overview: drag the city to inspect the route.':'Drone camera: view from the selected waypoint.');
});
$('sim-edit').addEventListener('change',event=>{
 if(!viewer)return;
 stop();
 if(event.target.checked){
  overview=true;
  $('sim-view').value='overview';
  showCamera();
  status('Move points in 3D · drag a numbered waypoint or target');
 }else{
  activeDrag=null;
  viewer.scene.screenSpaceCameraController.enableInputs=true;
  status('3D point editing off');
 }
});
$('sim-reload-buildings').addEventListener('click',()=>{
 const points=route(core()?.getShot()),at=points[core()?.getActiveWaypoint()]||points[0];
 if(!at){buildingStatus('Add a waypoint first');return}
 loadBuildings(at,epoch,true);
});
$('sim-3d-source').addEventListener('change',switch3dSource);
$('sim-cesium-key').value=ionReadToken;
loadSharedIonToken();
$('sim-cesium-apply').addEventListener('click',async()=>{
 const token=$('sim-cesium-key').value.trim();
 if(!token){
  $('sim-cesium-hint').textContent='Enter an assets:read token from ion.cesium.com';return;
 }
 const btn=$('sim-cesium-apply');btn.disabled=true;
 ionReadToken=token;
 try{
  await saveSharedIonToken(token);
  sharedIonTokenPromise=Promise.resolve(token);
 }catch(error){
  // Even if the server is unavailable, keep this device functional.
  try{localStorage.setItem(ionKeyStore,token)}catch{}
  $('sim-cesium-hint').textContent='Token saved on this device only · server sync failed.';
 }finally{btn.disabled=false}
 switch3dSource();
});
$('sim-imagery').addEventListener('change',setImageryMode);
$('sim-building-style').addEventListener('change',configureBuildings);
$('sim-maptiler-key').value=maptilerKey;
$('sim-imagery-apply').addEventListener('click',()=>{
 maptilerKey=$('sim-maptiler-key').value.trim();
 try{if(maptilerKey)localStorage.setItem(imageryKeyStore,maptilerKey);else localStorage.removeItem(imageryKeyStore)}catch{}
 if(!maptilerKey){$('sim-imagery-hint').textContent='Enter a MapTiler key to use satellite imagery.';return}
 setImageryMode();
});
$('sim-hud-toggle').addEventListener('change',()=>{
 if(viewer&&!playing)showCamera();
 else if(viewer)setHud(null);
});
$('sim-quality').addEventListener('change',applyQuality);
$('sim-smoothing').addEventListener('change',()=>{
 if(viewer){
  renderRoute();
  if(!playing&&!overview&&playElapsed>0)drawFlight(motionDistance(playElapsed,flightData().total));
  else if(!playing)showCamera();
 }
});
$('sim-speed').addEventListener('input',event=>{
 $('sim-speed-value').textContent=event.target.value+' m/s';
 syncTimeline(flightData().total);
});
document.addEventListener('offset:map-rendered',refresh);
document.addEventListener('offset:shot-rendered',refresh);
document.addEventListener('offset:preview-height-changed',refresh);
document.addEventListener('offset:planner-layout',()=>{if(enabled&&viewer)viewer.resize()});
window.addEventListener('resize',()=>{if(enabled&&viewer)viewer.resize()});
$('preview-aspect').addEventListener('change',()=>{if(enabled&&viewer)viewer.resize()});
})();