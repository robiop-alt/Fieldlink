const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const terrain=fs.readFileSync(path.join(__dirname,'../static/planner-terrain3d.js'),'utf8');
const planner=fs.readFileSync(path.join(__dirname,'../static/planner.js'),'utf8');
function extract(source,name){
 const start=source.indexOf('function '+name+'(');assert.ok(start>=0,name);
 return source.slice(start,source.indexOf('\n}',start)+2);
}
function context(names,values={},source=terrain){
 const ctx=vm.createContext(values);
 vm.runInContext(names.map(name=>extract(source,name)).join('\n'),ctx);return ctx;
}
function nodes(){const all={};return id=>all[id]??=( {value:'',hidden:false,textContent:'',attrs:{},classList:{toggle(){}},setAttribute(k,v){this.attrs[k]=v}} )}

test('waypoint selection uses the exact camera pose and matching eased timeline position',()=>{
 const $=nodes(),points=[[59,10],[60,11],[61,12]],plan={points,legs:[100,300],total:400};
 const poses=[];
 const ctx=context(['motionDistance','timeForDistance','showCamera'],{
  $,core:()=>({getShot:()=>({}),getActiveWaypoint:()=>1}),route:()=>points,viewer:{},playing:false,overview:false,
  flightData:()=>plan,flightSmoothing:()=>1,playElapsed:0,syncTimeline:()=>{},
  heightAboveEllipsoid:()=>120,cameraDirection:()=>75,pitch:()=>-20,cameraFov:()=>60,
  cameraAt:(...args)=>poses.push(args),setHud:()=>{},lensLabel:()=>'',fpsLabel:()=>'',altitude:()=>30,
  fov:()=>60,updateFlightMarker:()=>{},groundAt:()=>90
 });
 ctx.showCamera();assert.equal(poses[0][0],points[1]);assert.equal(poses[0][2],75);
 assert.ok(Math.abs(ctx.motionDistance(ctx.playElapsed,400)-100)<.002);
 assert.equal(ctx.timeForDistance(0,400),0);assert.equal(ctx.timeForDistance(400,400),400);
});

test('camera focus keeps the same map, divider and playback position',()=>{
 const $=nodes();let resized=0,invalidated=0,drawn;
 const map={invalidateSize:()=>invalidated++};
 const ctx=context(['syncCameraFocus','toggleCameraFocus','restorePreviewFrame'],{
  $,splitIsActive:()=>true,cameraFocus:false,requestAnimationFrame:fn=>fn(),splitMap:map,
  viewer:{isDestroyed:()=>false,resize:()=>resized++},updateCameraFrustum:()=>{},playing:false,
  flightData:()=>({total:100}),overview:false,playElapsed:37,motionDistance:n=>n,drawFlight:n=>drawn=n
 });
 ctx.toggleCameraFocus();assert.equal(ctx.cameraFocus,true);assert.equal($('sim-camera-focus').textContent,'Show map');
 ctx.toggleCameraFocus();assert.equal(ctx.cameraFocus,false);assert.equal(ctx.splitMap,map);
 assert.equal(ctx.playElapsed,37);assert.equal(drawn,37);assert.equal(invalidated,1);assert.equal(resized,2);
});

test('moving field of view follows camera heading and widens with the lens angle',()=>{
 let fan;const ctx=context(['syncSplitMap','mapDestination'],{
  splitMap:{},splitIsActive:()=>true,splitDrone:{setLatLng(){}},splitArrow:{setLatLngs(){}},
  splitFov:{setLatLngs:p=>fan=p},rad:Math.PI/180,deg:180/Math.PI
 });
 ctx.syncSplitMap(59,10,30,0,60);
 assert.equal(fan.length,27);assert.equal(fan[0][0],59);assert.ok(fan[1][1]<10);assert.ok(fan[25][1]>10);
 const narrowWidth=fan[25][1]-fan[1][1];
 ctx.syncSplitMap(59,10,30,0,100);assert.ok(fan[25][1]-fan[1][1]>narrowWidth);
 ctx.syncSplitMap(59,10,30,90,60);assert.ok(fan[13][1]>10);assert.ok(Math.abs(fan[13][0]-59)<.00001);
});

test('dragging target-following aim switches to manual and updates preview before release',()=>{
 const handlers={},cone={visible:true,mode:'target',bearing:0,fov:60,range:100};
 let updated=0,saved=0,rendered=0;const events=[];
 const marker={addTo(){return this},bindTooltip(){},on(n,fn){handlers[n]=fn;return this},getLatLng:()=>({lat:60,lng:11})};
 const shape={addTo(){return this},setLatLngs(){updated++}};
 const ctx=context(['renderCones'],{
  coneLayers:[],coneHeadingMarker:null,cones:()=>[cone],coneOrigin:()=>[59,10],activeWaypoint:0,
  effectiveBearing:()=>45,destination:(o,b,r)=>[b,r],coordsOk:()=>true,selectingWaypoints:false,
  L:{polygon:()=>shape,marker:()=>marker,divIcon:x=>x},map:{distance:()=>150},
  document:{querySelector:()=>null,dispatchEvent:e=>events.push(e.type)},Event:class{constructor(type){this.type=type}},
  headingBetween:()=>90,updateConeControls:()=>{},renderWaypointEditor:()=>{},renderMap:()=>rendered++,scheduleSave:()=>saved++
 },planner);
 ctx.renderCones({target:[60,11]});handlers.dragstart();handlers.drag();
 assert.equal(cone.mode,'manual');assert.equal(cone.bearing,90);assert.equal(cone.range,150);
 assert.equal(updated,1);assert.equal(rendered,0);assert.ok(events.includes('offset:waypoint-aimed'));
 handlers.dragend();assert.equal(saved,1);assert.equal(rendered,1);
});
