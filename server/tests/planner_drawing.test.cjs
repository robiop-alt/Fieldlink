const test=require('node:test'),assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const src=fs.readFileSync(path.join(__dirname,'../static/planner.js'),'utf8');
function fixture(){
 const listeners={},doc={},strokes=[];let panLocks=0,saves=0,removed=0,shot={sketches:strokes};
 const container={addEventListener(n,fn,opt){listeners[n]=fn;assert.equal(opt.capture,true)},setPointerCapture(){},hasPointerCapture:()=>true,releasePointerCapture(){}};
 const ctx=vm.createContext({map:{getContainer:()=>container,mouseEventToLatLng:e=>({lat:e.clientY,lng:e.clientX}),dragging:{disable:()=>panLocks++},touchZoom:{disable(){}}},
  tool:'sketch',getShot:()=>shot,sketches:s=>s.sketches,
  L:{polyline:()=>({addTo(){return this},setLatLngs(){},remove:()=>removed++})},
  renderMap(){},scheduleSave:()=>saves++,document:{addEventListener:(n,fn)=>doc[n]=fn},Date,Math});
 const start=src.indexOf('function setupSketchEvents(){');
 vm.runInContext(src.slice(start,src.indexOf('\nfunction locationStatus',start)),ctx);ctx.setupSketchEvents();
 function send(type,x,y,options={}){let consumed=0;const e={pointerId:1,isPrimary:true,button:0,clientX:x,clientY:y,target:{closest:()=>false},preventDefault(){consumed++},stopImmediatePropagation(){consumed++},...options};listeners[type](e);return consumed}
 return {ctx,strokes,send,doc,stats:()=>({panLocks,saves,removed}),changeShot:()=>shot={sketches:[]}};
}
test('draw captures the gesture, locks panning and supports successive strokes',()=>{
 const f=fixture();assert.equal(f.send('pointerdown',1,1),2);f.send('pointermove',10,10);f.send('pointerup',20,20);
 assert.equal(f.strokes.length,1);assert.equal(f.ctx.tool,'sketch');
 f.send('pointerdown',30,30);f.send('pointerup',40,40);assert.equal(f.strokes.length,2);
 assert.equal(f.stats().panLocks,2);assert.equal(f.stats().saves,2);assert.equal(f.send('click',40,40),2);
});
test('cancel, tool change and shot change discard incomplete strokes',()=>{
 for(const mode of ['pointercancel','tool','shot']){
  const f=fixture();f.send('pointerdown',1,1);f.send('pointermove',10,10);
  if(mode==='tool')f.doc['offset:map-tool-changing']();
  else if(mode==='shot')f.changeShot();else f.send(mode,10,10);
  f.send('pointerup',20,20);assert.equal(f.strokes.length,0);assert.equal(f.stats().saves,0);
 }
});
test('map controls are excluded and long drawings retain their endpoint',()=>{
 const f=fixture();f.send('pointerdown',1,1,{target:{closest:()=>true}});assert.equal(f.stats().panLocks,0);
 f.send('pointerdown',0,0);for(let i=1;i<800;i++)f.send('pointermove',i*3,i*3);f.send('pointerup',2400,2400);
 assert.equal(f.strokes[0].length,500);assert.equal(f.strokes[0][499][0],2400);
});
test('sketch locks panning even with a fine pointer; Move restores it',()=>{
 let pan=true,zoom=true;const classes={};
 const ctx=vm.createContext({tool:'move',document:{dispatchEvent(){},querySelectorAll:()=>[]},Event:class{},
  clearMeasurement(){},syncMeasurementDragging(){},updateStats(){},
  window:{matchMedia:()=>({matches:false})},map:{getContainer:()=>({classList:{toggle:(k,v)=>classes[k]=v},style:{}}),
  dragging:{disable:()=>pan=false,enable:()=>pan=true},touchZoom:{disable:()=>zoom=false,enable:()=>zoom=true},doubleClickZoom:{disable(){},enable(){}}}});
 const start=src.indexOf('function setTool(next)');vm.runInContext(src.slice(start,src.indexOf('\nfunction applyBasemapStyle',start)),ctx);
 ctx.setTool('sketch');assert.equal(pan,false);assert.equal(zoom,false);assert.equal(classes['map-sketching'],true);
 ctx.setTool('move');assert.equal(pan,true);assert.equal(zoom,true);assert.equal(classes['map-sketching'],false);
});
