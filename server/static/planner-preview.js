(()=>{'use strict';
/* Schematic camera-framing preview. It deliberately does not simulate
   3D geography, camera tilt, obstacles or an optical video feed. */
const $=id=>document.getElementById(id);
const core=()=>window.OFFSETPlanner;
const ratios={'16:9':16/9,'2.39:1':2.39,'9:16':9/16,'1:1':1};
const degrees=180/Math.PI;
function bearing(a,b){
 const p1=a[0]*Math.PI/180,p2=b[0]*Math.PI/180,d=(b[1]-a[1])*Math.PI/180;
 return (Math.atan2(Math.sin(d)*Math.cos(p2),
  Math.cos(p1)*Math.sin(p2)-Math.sin(p1)*Math.cos(p2)*Math.cos(d))*degrees+360)%360;
}
function distance(a,b){
 const r=Math.PI/180,dLat=(b[0]-a[0])*r,dLng=(b[1]-a[1])*r;
 const h=Math.sin(dLat/2)**2+Math.cos(a[0]*r)*Math.cos(b[0]*r)*Math.sin(dLng/2)**2;
 return 6371008.8*2*Math.atan2(Math.sqrt(h),Math.sqrt(Math.max(0,1-h)));
}
function signedAngle(to,from){return ((to-from+540)%360)-180}
function display(){
 const api=core(),shot=api?.getShot();
 if(!shot)return;
 const chosen=Object.hasOwn(ratios,shot.previewAspect)?shot.previewAspect:'16:9';
 $('preview-aspect').value=chosen;
 const view=$('shot-preview-view');
 view.style.aspectRatio=String(ratios[chosen]);
 view.classList.toggle('is-portrait',chosen==='9:16');
 const targetNode=$('preview-target'),empty=$('preview-empty'),data=$('preview-data'),note=$('preview-note');
 const active=api.getActiveWaypoint(),waypoint=shot.route?.[active],cone=shot.waypointCones?.[active];
 const valid=api.coordsOk;
 const lens=String(shot.lens||'').trim();
 const autoFov=api.lensFov(shot);
 const fov=cone?.lensAuto&&autoFov?autoFov:(Number(cone?.fov)||autoFov||50);
 const angle=Number(api.getBearing())||0;
 const label=$('preview-frame-label');
 label.textContent=chosen+' · CONCEPT';
 const pts=[Number.isInteger(active)?'WP '+(active+1):'WP —',lens||'Lens not set',
  'HFOV '+Math.round(fov*10)/10+'°','Heading '+Math.round(angle)+'°'];
 data.replaceChildren(...pts.map(v=>{const n=document.createElement('span');n.textContent=v;return n}));
 const hasWp=valid(waypoint);
 empty.hidden=hasWp;
 targetNode.hidden=true;
 if(!hasWp){
  note.textContent='Add a waypoint to preview its horizontal camera field of view.';
  return;
 }
 if(!valid(shot.target)){
  note.textContent='Aim follows the selected waypoint. Add a camera target to check whether it falls inside the horizontal frame.';
  return;
 }
 const delta=signedAngle(bearing(waypoint,shot.target),angle);
 const dist=distance(waypoint,shot.target);
 const behind=Math.abs(delta)>89.9;
 const inside=!behind&&Math.abs(delta)<=fov/2;
 const position=behind?null:320+Math.tan(delta*Math.PI/180)/Math.tan(fov*Math.PI/360)*320;
 const relative=delta>0?'right':'left';
 const amount=Math.abs(delta).toFixed(1);
 const distText=dist>=1000?(dist/1000).toFixed(2)+' km':Math.round(dist)+' m';
 if(inside&&Number.isFinite(position)){
  const x=Math.max(19,Math.min(621,position));
  $('preview-target-ring').setAttribute('cx',String(x));
  $('preview-target-dot').setAttribute('cx',String(x));
  targetNode.hidden=false;
  note.textContent='Target '+distText+' away · '+amount+'° '+relative+' of center. Horizontal placement only; height and tilt are not modeled.';
 }else{
  note.textContent='Target '+distText+' away · '+(behind?'behind camera':amount+'° '+relative+' (outside horizontal frame)')+'. Aim the cone or use a wider lens to include it.';
 }
}
$('preview-aspect').addEventListener('change',event=>{
 const shot=core()?.getShot();if(!shot)return;
 const aspect=event.target.value;
 if(!Object.hasOwn(ratios,aspect))return;
 shot.previewAspect=aspect;core().save();display();
});
document.addEventListener('offset:shot-rendered',display);
document.addEventListener('offset:map-rendered',display);
$('shot-lens').addEventListener('input',display);
$('cone-bearing').addEventListener('input',display);
$('cone-fov').addEventListener('input',display);
})();