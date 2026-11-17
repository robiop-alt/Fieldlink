const {test}=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs'),path=require('node:path');
function setup(){
 const events={},node={checked:true,addEventListener(k,v){this[k]=v}},status={dataset:{}};let calls=0,finish,removed=0,added=0;
 const map={getZoom:()=>14,getBounds:()=>({getSouth:()=>59,getWest:()=>9,getNorth:()=>59.02,getEast:()=>9.02}),on(){},getPane:()=>({style:{}}),removeLayer(){removed++}};
 const context={window:{addEventListener(){}},document:{getElementById:id=>id==='population-toggle'?node:status,addEventListener(k,v){events[k]=v}},URLSearchParams,AbortController,setTimeout,clearTimeout,fetch(){calls++;return new Promise(r=>finish=r)},L:{geoJSON(){return{addTo(){added++;return this}}}},getShot:()=>null};
 vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../static/planner-population.js'),'utf8'),context);
 events['offset:planner-map-ready']({detail:{map}});
 return{api:context.window.OffsetPopulation,node,status,events,get calls(){return calls},get added(){return added},resolve:r=>finish(r)};
}
const square={type:'Feature',properties:{kind:'settlement'},geometry:{type:'Polygon',coordinates:[[[1,1],[3,1],[3,3],[1,3],[1,1]]]}};
const data={type:'FeatureCollection',complete:true,coverage:[-1,-1,5,5],features:[square]};
test('population layer defaults OFF and makes no request until enabled',()=>{const s=setup();assert.equal(s.node.checked,false);assert.equal(s.calls,0)});
test('SSB route classification tests segments, not just waypoints',()=>{
 const {classify}=setup().api;
 assert.equal(classify([[2,1.5],[2,2.5]],data),'inside');
 assert.equal(classify([[.2,.2],[.5,.5]],data),'outside');
 assert.equal(classify([[2,.2],[2,3.8]],data),'mixed');
 assert.equal(classify([[2,2],[2,6]],data),'unknown');
 assert.equal(classify([[2,2]],{...data,complete:false}),'unknown');
});
test('polygon holes remain outside settlement boundaries',()=>{
 const {classify}=setup().api;
 const hole={...square,geometry:{...square.geometry,coordinates:[...square.geometry.coordinates,[[1.5,1.5],[2.5,1.5],[2.5,2.5],[1.5,2.5],[1.5,1.5]]]}};
 assert.equal(classify([[2,2]],{...data,features:[hole]}),'outside');
 assert.equal(classify([[2,1.2],[2,2.8]],{...data,features:[hole]}),'mixed');
});
test('switching OFF ignores a late response and leaves no map layer',async()=>{
 const s=setup();s.node.checked=true;const pending=s.node.change();assert.equal(s.calls,1);
 s.node.checked=false;s.node.change();s.resolve({ok:true,json:async()=>({...data,years:{settlements:2026,population:2025}})});
 await new Promise(r=>setImmediate(r));assert.equal(s.added,0);assert.match(s.status.textContent,/off/i);
});
test('SSB switch and status live inside Drone zone information',()=>{
 function node(){return{dataset:{},children:[],setAttribute(){},append(...items){this.children.push(...items)},addEventListener(){}}}
 const parent={insertBefore(s){this.section=s}},zones=node(),zoneStatus=node(),controls=node(),populationStatus=node();zones.parentElement=parent;
 const doc={querySelector:s=>s==='.map-panel > .airspace-controls'?zones:s==='.population-controls'?controls:null,getElementById:id=>id==='airspace-status'?zoneStatus:id==='population-status'?populationStatus:null,createElement:node};
 vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../static/planner-folds.js'),'utf8'),{document:doc,localStorage:{getItem:()=>null},requestAnimationFrame(){}});
 assert.ok(parent.section.children.includes(controls));assert.ok(parent.section.children.includes(populationStatus));assert.ok(parent.section.children.includes(zones));
});
