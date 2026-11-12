(()=>{
'use strict';
/* Safari's visualViewport and orientation dimensions settle after rotation.
   Resize all maps without rebuilding markers or resetting the 3D camera. */
let pending=0;
function update(){
 const map=window.OFFSETPlanner?.getMap?.();
 if(map)try{map.invalidateSize({pan:false,animate:false})}catch(error){console.warn('Planner map resize',error)}
 document.dispatchEvent(new Event('offset:planner-layout'));
}
function schedule(){
 if(pending)clearTimeout(pending);
 pending=setTimeout(()=>{pending=0;requestAnimationFrame(update)},120);
}
window.addEventListener('orientationchange',()=>{
 schedule();
 setTimeout(update,450);
},{passive:true});
window.addEventListener('resize',schedule,{passive:true});
window.visualViewport?.addEventListener('resize',schedule,{passive:true});
document.addEventListener('visibilitychange',()=>{if(!document.hidden)schedule()});
document.addEventListener('toggle',event=>{
 if(event.target?.matches?.('.planner-fold'))schedule();
},true);
document.addEventListener('offset:planner-map-ready',schedule);
document.addEventListener('offset:shot-rendered',schedule);
if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',schedule,{once:true});else schedule();
})();