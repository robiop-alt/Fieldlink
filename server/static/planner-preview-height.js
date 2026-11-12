(()=>{'use strict';
const $=id=>document.getElementById(id),api=()=>window.OFFSETPlanner;
function height(){const s=api()?.getShot(),i=api()?.getActiveWaypoint(),cone=s?.waypointCones?.[i];const n=Number(cone?.altitudeAGL);return Number.isFinite(n)&&n>=2?Math.min(500,n):30}
function refresh(){
 const s=api()?.getShot(),i=api()?.getActiveWaypoint(),valid=!!s?.route?.[i];
 const n=height();
 $('preview-height').value=Math.min(200,n);
 $('preview-height-number').value=n;
 $('preview-height-label').textContent=n+' m AGL';
 $('preview-height').disabled=!valid;
 $('preview-height-number').disabled=!valid;
}
function setHeight(value){
 const s=api()?.getShot(),i=api()?.getActiveWaypoint();if(!s?.route?.[i])return;
 if(!s.waypointCones)s.waypointCones=[];
 if(!s.waypointCones[i])s.waypointCones[i]={visible:true,mode:'manual',bearing:0,fov:50,range:30};
 const v=Math.max(2,Math.min(500,Math.round(Number(value)||30)));
 s.waypointCones[i].altitudeAGL=v;
 refresh();api().save();
 document.dispatchEvent(new Event('offset:preview-height-changed'));
}
$('preview-height').addEventListener('input',e=>setHeight(e.target.value));
$('preview-height-number').addEventListener('change',e=>setHeight(e.target.value));
document.addEventListener('offset:shot-rendered',refresh);
document.addEventListener('offset:map-rendered',refresh);
if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',refresh,{once:true});else refresh();
})();