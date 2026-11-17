const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const source=fs.readFileSync(path.join(__dirname,'../static/planner.js'),'utf8');
const ctx=vm.createContext({});
for(const name of ['nearestRouteSegment','interpolatedWaypoint']){const i=source.indexOf('function '+name+'(');vm.runInContext(source.slice(i,source.indexOf('\n}',i)+2),ctx)}
test('insertion selects the nearest segment and its proportional position',()=>{
 const p=([x,y])=>({x,y});const hit=ctx.nearestRouteSegment([[0,0],[10,0],[10,10]],[10.2,7],p);
 assert.equal(hit.index,1);assert.equal(hit.t,.7);assert.equal(hit.x,10);assert.equal(hit.y,7);
 assert.equal(ctx.nearestRouteSegment([[0,0],[0,0]],[1,1],p),null);
});
test('inserted height and tilt interpolate, including zero and circular heading',()=>{
 const c=ctx.interpolatedWaypoint({altitudeAGL:0,tilt:-80,bearing:350},{altitudeAGL:200,tilt:0,bearing:10},.5);
 assert.equal(c.altitudeAGL,100);assert.equal(c.tilt,-40);assert.equal(c.bearing,0);
 assert.equal(ctx.interpolatedWaypoint({altitudeAGL:0},{altitudeAGL:0},.5).altitudeAGL,0);
 assert.equal(ctx.interpolatedWaypoint({},{},.5,-25).tilt,-25);
});
