(()=>{'use strict';
const $=id=>document.getElementById(id);
const clone=x=>JSON.parse(JSON.stringify(x));
let plans=[],current=null,activeShot=0,activeWaypoint=0,map,routeLine,aimLine,markers=[],marshalMarkers=[],sketchLayers=[],textMarkers=[],targetMarker,coneLayers=[],coneHeadingMarker,searchPin,locationPin,tool='move',saveTimer=null,saveEpoch=0,loading=false;
let droneHeightRing=null;
let measurementPoints=[],measurementLayers=null,measurementShot=null;
const measurementDraggables=new Set();
function clearMeasurement(){
 measurementPoints=[];measurementLayers?.clearLayers();
}
function measurementDistanceLabel(metres){
 return '≈ '+(metres>=1000?(metres/1000).toFixed(2)+' km':metres.toFixed(metres<10?1:0)+' m');
}
function syncMeasurementDragging(){
 if(tool==='measure')map?.eachLayer(layer=>{
  if(layer.dragging?.enabled()){measurementDraggables.add(layer);layer.dragging.disable()}
 });
 else{measurementDraggables.forEach(layer=>{if(map?.hasLayer(layer))layer.dragging?.enable()});measurementDraggables.clear()}
}
function addMeasurementPoint(point){
 if(measurementPoints.length===2)clearMeasurement();
 measurementPoints.push(point);
 if(!measurementLayers)measurementLayers=L.layerGroup().addTo(map);
 measurementLayers.clearLayers();
 measurementPoints.forEach(p=>L.circleMarker(p,{pane:'measurement',radius:5,color:'#ffffff',weight:2,fillColor:'#f4b45e',fillOpacity:1,interactive:false}).addTo(measurementLayers));
 if(measurementPoints.length===2){
  const [a,b]=measurementPoints;
  L.polyline([a,b],{pane:'measurement',color:'#f4b45e',weight:3,dashArray:'7,5',interactive:false}).addTo(measurementLayers);
  L.tooltip({permanent:true,direction:'center',className:'map-distance-label',pane:'measurement'})
   .setLatLng([(a.lat+b.lat)/2,(a.lng+b.lng)/2])
   .setContent(measurementDistanceLabel(map.distance(a,b))).addTo(measurementLayers);
 }
 updateStats();
}
function setupMeasurement(){
 map.createPane('measurement').style.pointerEvents='none';
 map.getPane('measurement').style.zIndex='650';
 map.getContainer().addEventListener('click',event=>{
  if(tool!=='measure'||event.target.closest('.leaflet-control'))return;
  // Capture before editable markers and drawings can select or open popups.
  event.stopImmediatePropagation();event.preventDefault();
  if(!map.dragging.moved())addMeasurementPoint(map.mouseEventToLatLng(event));
 },true);
 document.addEventListener('keydown',event=>{
  if(event.key==='Escape'&&tool==='measure'){event.preventDefault();setTool('move')}
 });
 document.addEventListener('offset:map-rendered',()=>{
  const shot=getShot();if(shot!==measurementShot){clearMeasurement();measurementShot=shot}
  syncMeasurementDragging();
 });
}

const radiusPreferenceKey='offset-planner-drone-radius-visible-v1';
function radiusEnabled(){
 try{return localStorage.getItem(radiusPreferenceKey)!=='off'}catch{return true}
}
function setRadiusEnabled(on){
 try{localStorage.setItem(radiusPreferenceKey,on?'on':'off')}catch{}
 $('drone-radius-map').checked=on;
 $('sim-radius-toggle').checked=on;
 if(droneHeightRing){droneHeightRing.remove();droneHeightRing=null}
 if(on)renderMap();
 document.dispatchEvent(new CustomEvent('offset:radius-toggle',{detail:{enabled:on}}));
}
$('drone-radius-map').checked=radiusEnabled();
$('sim-radius-toggle').checked=radiusEnabled();
$('drone-radius-map').addEventListener('change',e=>setRadiusEnabled(e.target.checked));
$('sim-radius-toggle').addEventListener('change',e=>setRadiusEnabled(e.target.checked));
const coordsOk=p=>Array.isArray(p)&&p.length===2&&p.every(v=>typeof v==='number'&&Number.isFinite(v)&&Math.abs(v)<=180)&&Math.abs(p[0])<=90;
function syncDateDisplay(){const input=$('shot-date'),face=$('shot-date-display');if(!input||!face)return;const value=input.value;if(!/^\d{4}-\d{2}-\d{2}$/.test(value)){face.textContent='Select date';face.classList.add('is-empty');return}const [year,month,day]=value.split('-');face.textContent=day+'.'+month+'.'+year;face.classList.remove('is-empty')}
const blankShot=(name='New shot')=>({id:crypto.randomUUID(),name,description:'',drone:'',lens:'',date:'',time:'',sunHour:12,referenceImage:'',route:[],sketches:[],textBoxes:[],marshals:[],target:null,waypointCones:[]});
const api=async(path,opt={})=>{const r=await fetch(path,{credentials:'same-origin',headers:{'Content-Type':'application/json'},...opt});if(!r.ok){let message='Request failed ('+r.status+')';try{const d=await r.json();message=typeof d.detail==='string'?d.detail:message}catch{}throw Error(message)}return r.json()};
function state(message){$('save-state').textContent=message}
function getShot(){return current?.shots?.[activeShot]}
function sketches(shot){if(!Array.isArray(shot.sketches))shot.sketches=[];return shot.sketches}
function normalized(data){
 const shots=Array.isArray(data.shots)?data.shots:[];
 const legacy=Array.isArray(data.marshals)?data.marshals:[];
 for(const shot of shots)if(!Array.isArray(shot.marshals))shot.marshals=[];
 // Older plans had no shot ownership. Keep those guards on the first shot only.
 if(shots.length&&legacy.length){
  const ids=new Set(shots[0].marshals.map(guard=>guard.id));
  shots[0].marshals.push(...legacy.filter(guard=>!ids.has(guard.id)));
 }
 return {...data,shots,marshals:shots.length?[]:legacy};
}
async function load(){try{const data=await api('/api/admin/planner');plans=data.plans.map(normalized);current=plans[0]||null;if(!current){await createPlan('Untitled plan');return}selectPlan(current.id)}catch(e){state('Load error');alert(e.message)}}
async function createPlan(name){try{const result=await api('/api/admin/planner',{method:'POST',body:JSON.stringify({name})});plans.unshift(normalized(result));selectPlan(result.id);state('Saved')}catch(e){alert(e.message)}}
function selectPlan(id){if(saveTimer){clearTimeout(saveTimer);saveTimer=null;const pending=current;saveNow(pending)}current=plans.find(p=>p.id===id);activeShot=0;activeWaypoint=0;selectedWaypoints.clear();selectionShot=null;historyForPlan();if(map&&current?.center&&Number.isFinite(current.center.lat)&&Number.isFinite(current.center.lng)){map.setView([current.center.lat,current.center.lng],current.zoom||12)}if(current&&!current.shots.length){const shot=blankShot('Opening shot');shot.marshals=current.marshals||[];current.marshals=[];current.shots.push(shot);scheduleSave()}render();renderMap()}
function render(){const select=$('plan-select');select.replaceChildren(...plans.map(p=>{const el=document.createElement('option');el.value=p.id;el.textContent=p.name;return el}));select.value=current?.id||'';const mobile=$('mobile-shot');mobile.replaceChildren(...(current?.shots||[]).map((shot,i)=>{const option=document.createElement('option');option.value=String(i);option.textContent=(i+1)+'. '+(shot.name||'Untitled shot');return option}));mobile.value=String(activeShot);const list=$('shot-list');list.replaceChildren();current?.shots.forEach((shot,i)=>{const item=document.createElement('div');item.className='shot-item'+(i===activeShot?' active':'');const count=document.createElement('span');count.className='shot-count';count.textContent=shot.route.length+' pts';const n=document.createElement('span');n.className='shot-number';n.textContent=String(i+1).padStart(2,'0');const b=document.createElement('button');b.textContent=shot.name||'Untitled shot';b.onclick=()=>{readFields();activeShot=i;activeWaypoint=0;render();renderMap()};item.append(n,b,count);addShotOrdering(item,i);list.append(item)});const shot=getShot();loading=true;for(const key of ['name','description','drone','lens','date','time'])$('shot-'+key).value=shot?.[key]??'';$('shot-time').value=shotTime(shot);$('pdf-closure-area').checked=shot?.pdfClosureArea===true;loading=false;syncDateDisplay();syncLensControls();updateConeControls();updateStats();document.dispatchEvent(new Event('offset:shot-rendered'))}
// Older plans used named periods; retain their existing preview clock values.
function shotTime(shot=getShot()){
 const value=String(shot?.time||'');
 if(/^([01]\d|2[0-3]):[0-5]\d$/.test(value))return value;
 return {'Sunrise':'06:00','Morning':'09:00','Midday':'12:00','Afternoon':'16:00','Golden hour':'18:00','Sunset':'19:00','Night':'22:00'}[value]||'';
}
function setShotTime(value){
 const shot=getShot();if(!shot||!/^([01]\d|2[0-3]):[0-5]\d$/.test(value))return;
 shot.time=value;shot.sunHour=Number(value.slice(0,2))+Number(value.slice(3))/60;
 $('shot-time').value=value;scheduleSave();
 document.dispatchEvent(new Event('offset:shot-time-changed'));
}
function readFields(){const shot=getShot();if(!shot||loading)return;syncDateDisplay();for(const key of ['name','description','drone','lens','date','time'])shot[key]=$('shot-'+key).value;const active=$('shot-list').querySelector('.active button:not(.shot-drag-handle)');if(active)active.textContent=shot.name||'Untitled shot';const selected=$('mobile-shot').options[activeShot];if(selected)selected.textContent=(activeShot+1)+'. '+(shot.name||'Untitled shot');if(shot.time)shot.sunHour=Number(shot.time.slice(0,2))+Number(shot.time.slice(3))/60;scheduleSave();document.dispatchEvent(new Event('offset:shot-time-changed'))}
// Session history is isolated per plan. Map pan/zoom never creates edit steps.
const histories=new Map();
let restoringHistory=false,selectedWaypoints=new Set(),selectionShot=null,selectingWaypoints=false;
function editSnapshot(){return {name:current.name,shots:clone(current.shots),marshals:clone(current.marshals||[])}}
function historyForPlan(){
 if(!current)return null;
 if(!histories.has(current.id))histories.set(current.id,{past:[],future:[],present:editSnapshot(),group:'',time:0});
 return histories.get(current.id);
}
function historyButtons(){const h=historyForPlan();$('undo-point').disabled=!h?.past.length;$('redo-edit').disabled=!h?.future.length}
function recordEdit(){
 const h=historyForPlan();if(!h||restoringHistory)return;
 const next=editSnapshot();if(JSON.stringify(next)===JSON.stringify(h.present))return;
 const field=document.activeElement;
 const group=field?.matches?.('input,textarea')?getShot()?.id+':'+field.id:'';
 const now=Date.now();
 if(!group||group!==h.group||now-h.time>700){h.past.push(h.present);if(h.past.length>50)h.past.shift()}
 h.present=next;h.future=[];h.group=group;h.time=now;historyButtons();
}
function travelHistory(direction){
 const h=historyForPlan(),from=direction==='undo'?h?.past:h?.future;
 if(!from?.length)return;
 const activeId=getShot()?.id;
 const to=direction==='undo'?h.future:h.past;to.push(h.present);
 h.present=from.pop();h.group='';
 restoringHistory=true;
 Object.assign(current,clone(h.present));
 activeShot=Math.max(0,current.shots.findIndex(s=>s.id===activeId));activeWaypoint=0;
 selectedWaypoints.clear();selectionShot=null;
 render();renderMap();h.present=editSnapshot();scheduleSave();restoringHistory=false;historyButtons();
}
function moveShot(from,to){
 if(!current||from===to||from<0||to<0||from>=current.shots.length||to>=current.shots.length)return;
 readFields();const id=getShot()?.id;
 const [shot]=current.shots.splice(from,1);current.shots.splice(to,0,shot);
 activeShot=current.shots.findIndex(s=>s.id===id);
 render();renderMap();scheduleSave();
}
function addShotOrdering(item,index){
 item.dataset.shotIndex=index;
 const handle=document.createElement('button');handle.type='button';handle.className='shot-drag-handle';handle.textContent='⠿';handle.title='Drag to reorder shot';handle.setAttribute('aria-label','Drag shot '+(index+1));
 handle.addEventListener('pointerdown',event=>{
  if(event.button!==0)return;
  event.preventDefault();handle.setPointerCapture(event.pointerId);let target=index;
  item.classList.add('shot-dragging');
  const move=e=>{
   const row=document.elementFromPoint(e.clientX,e.clientY)?.closest('.shot-item');
   document.querySelectorAll('.shot-drop-target').forEach(n=>n.classList.remove('shot-drop-target'));
   if(row){target=Number(row.dataset.shotIndex);row.classList.add('shot-drop-target')}
   const list=$('shot-list'),box=list.getBoundingClientRect();
   if(e.clientY<box.top+28)list.scrollTop-=12;else if(e.clientY>box.bottom-28)list.scrollTop+=12;
  };
  const finish=e=>{
   handle.removeEventListener('pointermove',move);handle.removeEventListener('pointerup',finish);handle.removeEventListener('pointercancel',finish);
   item.classList.remove('shot-dragging');document.querySelectorAll('.shot-drop-target').forEach(n=>n.classList.remove('shot-drop-target'));
   if(e.type!=='pointercancel')moveShot(index,target);
  };
  handle.addEventListener('pointermove',move);handle.addEventListener('pointerup',finish);handle.addEventListener('pointercancel',finish);
 });
 const actions=document.createElement('span');actions.className='shot-order-buttons';
 for(const [delta,label] of [[-1,'↑'],[1,'↓']]){
  const button=document.createElement('button');button.type='button';button.textContent=delta<0?'Move up':'Move down';button.disabled=index+delta<0||index+delta>=current.shots.length;
  button.setAttribute('aria-label','Move shot '+(index+1)+(delta<0?' up':' down'));button.onclick=()=>moveShot(index,index+delta);actions.append(button);
 }
 const menu=document.createElement('details');menu.className='shot-order-menu';
 const summary=document.createElement('summary');summary.textContent='⋯';summary.setAttribute('aria-label','Actions for shot '+(index+1));
 const remove=document.createElement('button');remove.type='button';remove.className='danger';remove.textContent='Delete shot';remove.onclick=()=>deleteShot(index);actions.append(remove);
 menu.append(summary,actions);item.prepend(handle);item.append(menu);
}
function deleteShot(index){
 if(!current||!current.shots[index]||!confirm('Delete this shot?'))return;
 readFields();const activeId=getShot()?.id;
 current.shots.splice(index,1);if(!current.shots.length)current.shots.push(blankShot('Opening shot'));
 const keep=current.shots.findIndex(s=>s.id===activeId);
 activeShot=keep>=0?keep:Math.min(index,current.shots.length-1);activeWaypoint=0;
 render();renderMap();scheduleSave();
}
function ensureWaypointSelection(){
 const id=getShot()?.id;if(id!==selectionShot){selectedWaypoints.clear();selectionShot=id;selectingWaypoints=false}
 const size=getShot()?.route.length||0;
 for(const i of selectedWaypoints)if(i>=size)selectedWaypoints.delete(i);
}
function deleteSelectedWaypoints(){
 const shot=getShot();ensureWaypointSelection();if(!shot||!selectedWaypoints.size)return;
 cones(shot);
 const keep=shot.route.map((_,i)=>i).filter(i=>!selectedWaypoints.has(i));
 const nextActive=keep.indexOf(activeWaypoint);
 shot.route=keep.map(i=>shot.route[i]);shot.waypointCones=keep.map(i=>shot.waypointCones[i]);
 activeWaypoint=nextActive>=0?nextActive:Math.min(activeWaypoint,Math.max(0,keep.length-1));
 selectedWaypoints.clear();render();renderMap();scheduleSave();
}
function renderWaypointEditor(){
 ensureWaypointSelection();const shot=getShot(),cone=shot&&coneData(shot);
 const picker=$('map-waypoint');picker.replaceChildren();
 (shot?.route||[]).forEach((_,i)=>{const option=document.createElement('option');option.value=i;option.textContent='WP '+(i+1);picker.append(option)});
 picker.value=activeWaypoint;picker.disabled=!cone;
 $('select-waypoints').setAttribute('aria-pressed',String(selectingWaypoints));
 $('select-waypoints').textContent=selectingWaypoints?'Done':'Select';
 $('delete-waypoints').textContent='Delete selected ('+selectedWaypoints.size+')';$('delete-waypoints').disabled=!selectedWaypoints.size;
 $('waypoint-editor').classList.toggle('is-selecting',selectingWaypoints);
 $('waypoint-editor').classList.toggle('has-waypoint',!!cone);
 $('waypoint-checks').hidden=!selectingWaypoints;
 $('waypoint-checks').replaceChildren(...(shot?.route||[]).map((_,i)=>{
  const label=document.createElement('label'),input=document.createElement('input');input.type='checkbox';input.checked=selectedWaypoints.has(i);
  input.onchange=()=>{if(input.checked)selectedWaypoints.add(i);else selectedWaypoints.delete(i);renderMap()};label.append(input,document.createTextNode('WP '+(i+1)));return label;
 }));
 for(const id of ['map-wp-height','map-wp-tilt','map-wp-bearing'])$(id).disabled=!cone;
 $('map-wp-height').value=cone?.altitudeAGL??30;
 $('map-wp-tilt').value=cone?.tilt??shot?.simPitch??-10;
 syncWaypointSliderValues();
 $('map-wp-bearing').value=cone?Math.round(effectiveBearing(shot)):0;
 $('map-wp-bearing').disabled=!cone||cone.mode==='target';
}
$('select-waypoints').onclick=()=>{if(tool==='measure')setTool('move');ensureWaypointSelection();selectingWaypoints=!selectingWaypoints;renderWaypointEditor()};
$('select-all-waypoints').onclick=()=>{ensureWaypointSelection();getShot()?.route.forEach((_,i)=>selectedWaypoints.add(i));renderMap()};
$('delete-waypoints').onclick=deleteSelectedWaypoints;
$('map-waypoint').onchange=e=>selectWaypoint(Number(e.target.value));
let waypointPreviewTimer=null;
function syncWaypointSliderValues(){
 $('map-wp-height-value').textContent=$('map-wp-height').value+' m';
 $('map-wp-tilt-value').textContent=$('map-wp-tilt').value+'°';
}
function flushWaypointPreview(){
 clearTimeout(waypointPreviewTimer);waypointPreviewTimer=null;renderMap();
}
function updateWaypointValue(id,key,min,max){
 const shot=getShot(),cone=shot&&coneData(shot),value=Number($(id).value);
 if(!cone||!Number.isFinite(value))return;
 cone[key]=Math.round(Math.min(max,Math.max(min,value)));
 syncWaypointSliderValues();scheduleSave();
 if(!waypointPreviewTimer)waypointPreviewTimer=setTimeout(flushWaypointPreview,60);
}
for(const [id,key,min,max] of [['map-wp-height','altitudeAGL',2,500],['map-wp-tilt','tilt',-85,35]]){
 $(id).oninput=()=>updateWaypointValue(id,key,min,max);
 $(id).onchange=flushWaypointPreview;
}
$('map-wp-bearing').onchange=()=>{updateWaypointValue('map-wp-bearing','bearing',0,359);flushWaypointPreview()};
$('redo-edit').onclick=()=>travelHistory('redo');
document.addEventListener('keydown',e=>{
 if(!(e.ctrlKey||e.metaKey)||e.altKey||e.target.matches?.('input,textarea,select,[contenteditable="true"]'))return;
 if(e.key.toLowerCase()==='z'){e.preventDefault();travelHistory(e.shiftKey?'redo':'undo')}
});
document.addEventListener('offset:map-rendered',renderWaypointEditor);
document.addEventListener('offset:shot-rendered',()=>{ensureWaypointSelection();historyButtons();renderWaypointEditor()});

function scheduleSave(){if(!current)return;recordEdit();state('Unsaved…');clearTimeout(saveTimer);saveTimer=setTimeout(()=>{saveTimer=null;saveNow(current)},650)}
async function saveNow(plan){if(!plan)return false;const epoch=++saveEpoch;state('Saving…');try{const res=await api('/api/admin/planner/'+encodeURIComponent(plan.id),{method:'PUT',body:JSON.stringify({name:plan.name,shots:clone(plan.shots),marshals:clone(plan.marshals||[]),center:map?map.getCenter():plan.center,zoom:map?map.getZoom():plan.zoom})});if(epoch===saveEpoch)state('Saved');plan.updated=res.updated;return true}catch(e){if(epoch===saveEpoch)state('Save failed');console.error(e);return false}}


function syncLensControls(){
 const lens=String(getShot()?.lens||'').trim();
 const matched=lens.match(/^(18|24|35|50)(?:\s*mm)?$/i);
 const value=matched?matched[1]+' mm':'';
 for(const id of ['preview-lens','sim-lens']){
  const field=$(id);if(field)field.value=value;
 }
}
function setShotLens(value){
 const shot=getShot();if(!shot)return;
 const next=String(value||'').trim();
 const matched=next.match(/^(18|24|35|50)(?:\s*mm)?$/i);
 shot.lens=matched?matched[1]+' mm':next;
 $('shot-lens').value=shot.lens;
 // Explicitly choosing a lens links every waypoint to the X9 full-frame FOV.
 // Manual custom FOV remains possible afterwards via the waypoint controls.
 if(matched){
  for(const cone of cones(shot))cone.lensAuto=true;
  cones(shot);
 }
 syncLensControls();
 if(map&&shot.route.length)renderCones(shot);
 updateConeControls();
 scheduleSave();
 document.dispatchEvent(new Event('offset:shot-lens-changed'));
}
function lensFov(shot){const match=String(shot?.lens||'').match(/(?:^|\D)(18|24|35|50)(?:\D|$)/);return match?Math.round(2*Math.atan(35.95/(2*Number(match[1])))*180/Math.PI*10)/10:null}
function defaultCone(bearing=0){return {visible:true,mode:'manual',bearing,fov:50,range:30,lensAuto:true}}
function cones(shot){
 if(!Array.isArray(shot.waypointCones))shot.waypointCones=[];
 if(shot.cone&&shot.route.length){
  const old=shot.cone;while(shot.waypointCones.length<shot.route.length)shot.waypointCones.push(defaultCone());
  let index=shot.route.length-1;
  if(coordsOk(old.origin)&&map){let distance=Infinity;shot.route.forEach((p,i)=>{const d=map.distance(p,old.origin);if(d<distance){distance=d;index=i}})}
  shot.waypointCones[index]={visible:!!old.visible,mode:old.mode,bearing:old.bearing,fov:old.fov,range:old.range};
 }
 delete shot.cone;
 while(shot.waypointCones.length<shot.route.length){
  const i=shot.waypointCones.length;
  shot.waypointCones.push(defaultCone(i&&coordsOk(shot.route[i-1])?headingBetween(shot.route[i-1],shot.route[i]):0));
 }
 shot.waypointCones.length=shot.route.length;
 for(let i=0;i<shot.waypointCones.length;i++){
  const c=shot.waypointCones[i]||defaultCone();
  c.visible=c.visible!==false;c.mode=c.mode==='target'?'target':'manual';
  c.bearing=Number.isFinite(Number(c.bearing))?((Number(c.bearing)%360)+360)%360:0;
  c.fov=c.lensAuto&&lensFov(shot)?lensFov(shot):Math.min(170,Math.max(5,Number(c.fov)||50));
  c.range=Math.min(10000,Math.max(1,Number(c.range)||30));
  shot.waypointCones[i]=c;
 }
 return shot.waypointCones;
}
function coneData(shot,index=activeWaypoint){return cones(shot)[index]||null}
function coneOrigin(shot,index=activeWaypoint){return coordsOk(shot.route[index])?shot.route[index]:null}
function headingBetween(from,to){
 const p1=from[0]*Math.PI/180,p2=to[0]*Math.PI/180,d=(to[1]-from[1])*Math.PI/180;
 return (Math.atan2(Math.sin(d)*Math.cos(p2),Math.cos(p1)*Math.sin(p2)-Math.sin(p1)*Math.cos(p2)*Math.cos(d))*180/Math.PI+360)%360;
}
function destination(start,bearing,range){
 const r=6371008.8,d=range/r,p=start[0]*Math.PI/180,l=start[1]*Math.PI/180,a=bearing*Math.PI/180;
 const lat=Math.asin(Math.sin(p)*Math.cos(d)+Math.cos(p)*Math.sin(d)*Math.cos(a));
 const lng=l+Math.atan2(Math.sin(a)*Math.sin(d)*Math.cos(p),Math.cos(d)-Math.sin(p)*Math.sin(lat));
 return [lat*180/Math.PI,((lng*180/Math.PI+540)%360)-180];
}
function effectiveBearing(shot,index=activeWaypoint){
 const c=coneData(shot,index),origin=coneOrigin(shot,index);
 return c?(c.mode==='target'&&origin&&coordsOk(shot.target)?headingBetween(origin,shot.target):c.bearing):0;
}
function selectWaypoint(index){
 const shot=getShot();if(!shot||!shot.route.length)return;
 activeWaypoint=Math.max(0,Math.min(shot.route.length-1,index));renderMap();
}
function updateConeControls(){
 const shot=getShot();if(!shot)return;
 const select=$('cone-waypoint');select.replaceChildren();
 shot.route.forEach((_,i)=>{const option=document.createElement('option');option.value=String(i);option.textContent='Waypoint '+(i+1);select.append(option)});
 activeWaypoint=Math.min(activeWaypoint,Math.max(shot.route.length-1,0));
 select.value=String(activeWaypoint);select.disabled=!shot.route.length;
 const c=coneData(shot);
 for(const id of ['cone-visible','cone-mode','cone-bearing','cone-fov','cone-range','cone-tilt','cone-lens-auto'])$(id).disabled=!c;
 if(!c){$('cone-tilt').value='-10';$('cone-lens-auto').checked=false;$('cone-visible').checked=false;$('cone-mode-tip').textContent='Add a waypoint to create its attached camera cone.';$('cone-reset-origin').disabled=true;return}
 const follow=c.mode==='target'&&coordsOk(shot.target);
 $('cone-tilt').value=Number.isFinite(Number(c.tilt))?c.tilt:(Number.isFinite(Number(shot.simPitch))?shot.simPitch:-10);
 $('cone-visible').checked=c.visible;$('cone-mode').value=c.mode;
 $('cone-bearing').value=Math.round(effectiveBearing(shot));$('cone-bearing').disabled=follow;
 $('cone-lens-auto').checked=!!c.lensAuto;$('cone-fov').disabled=!!c.lensAuto&&!!lensFov(shot);$('cone-fov').value=c.fov;$('cone-range').value=c.range;$('cone-lens-status').textContent=c.lensAuto?(lensFov(shot)?'Full-frame X9 · horizontal FOV '+c.fov.toFixed(1)+'°':'Enter 18, 24, 35 or 50 mm in Lens to calculate FOV'):'Manual FOV (°)';
 $('cone-mode-tip').textContent=c.mode==='target'?(follow?'Pointing toward the camera target from waypoint '+(activeWaypoint+1)+'.':'No target yet; using manual bearing.'):'Cone attached to waypoint '+(activeWaypoint+1)+'. Drag the orange direction handle to aim.';
 $('cone-reset-origin').disabled=activeWaypoint===shot.route.length-1;
}
function renderCones(shot){
 coneLayers.forEach(layer=>layer.remove());coneLayers=[];
 if(coneHeadingMarker)coneHeadingMarker.remove();coneHeadingMarker=null;
 cones(shot).forEach((c,i)=>{
  const origin=coneOrigin(shot,i);if(!c.visible||!origin)return;
  const bearing=effectiveBearing(shot,i),edge=[];
  for(let k=0;k<=24;k++)edge.push(destination(origin,bearing-c.fov/2+c.fov*k/24,c.range));
  const selected=i===activeWaypoint;
  const coneShape=[origin,...edge,origin];
  // Single crisp outline; restrained translucent coverage, no heavy dark border.
  coneLayers.push(L.polygon(coneShape,{
   color:selected?'#2aab58':'#4bbd75',
   weight:selected?1.5:1,
   opacity:selected?.95:.82,
   fillColor:'#44bd70',
   fillOpacity:selected?.29:.18,
   interactive:false,
   lineCap:'round',
   lineJoin:'round'
  }).addTo(map));
  if(!selected)return;
  const follow=c.mode==='target'&&coordsOk(shot.target);
  coneHeadingMarker=L.marker(destination(origin,bearing,c.range),{draggable:!follow,icon:L.divIcon({className:'',html:'<div class="cone-aim" aria-hidden="true"><svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"><circle cx="12" cy="12" r="4.5"/><path d="M12 2v4M12 18v4M2 12h4M18 12h4"/></svg></div>',iconSize:[26,26],iconAnchor:[13,13]})}).addTo(map);
  coneHeadingMarker.bindTooltip(follow?'Following camera target':'Drag to aim waypoint '+(i+1));
  if(!follow)coneHeadingMarker.on('dragend',()=>{
   const p=coneHeadingMarker.getLatLng(),aim=[p.lat,p.lng];
   c.bearing=headingBetween(origin,aim);c.range=Math.min(10000,Math.max(1,Math.round(map.distance(origin,aim))));
   renderMap();scheduleSave();
  });
 });
}

function roadMarshals(shot=getShot()){return Array.isArray(shot?.marshals)?shot.marshals:[]}
function marshalIcon(){
 return L.divIcon({
  className:'marshal-marker-wrap',
  iconSize:[30,36],iconAnchor:[15,32],
  html:'<span class="marshal-person" aria-hidden="true"><svg viewBox="0 0 24 30" width="23" height="29" xmlns="http://www.w3.org/2000/svg"><circle cx="12" cy="4.5" r="3.4" fill="#ff4148" stroke="#fff" stroke-width="1.2"/><path d="M12 9 L12 19 M12 11 L5 15 M12 11 L19 15 M12 19 L6.5 27 M12 19 L17.5 27" fill="none" stroke="#ff4148" stroke-width="4.4" stroke-linecap="round" stroke-linejoin="round"/><path d="M12 9 L12 19 M12 11 L5 15 M12 11 L19 15 M12 19 L6.5 27 M12 19 L17.5 27" fill="none" stroke="#fff" stroke-opacity=".72" stroke-width=".65" stroke-linecap="round" stroke-linejoin="round"/></svg></span>'
 });
}
function removeMarshal(id){
 if(!current)return;
 const index=roadMarshals().findIndex(item=>item.id===id);
 if(index<0)return;
 roadMarshals().splice(index,1);
 renderMarshals();scheduleSave();
}
function editMarshal(item){
 const next=prompt('Sperrevakt / road marshal label',item.label||'Road marshal');
 if(next===null)return;
 item.label=next.trim().slice(0,100);
 renderMarshals();scheduleSave();
}
function renderMarshals(){
 marshalMarkers.forEach(marker=>marker.remove());marshalMarkers=[];
 if(!current||!map)return;
 const guards=roadMarshals();
 const list=$('marshal-list');
 list.replaceChildren();
 $('marshal-count').textContent=guards.length+' placed';
 if(!guards.length){
  const empty=document.createElement('p');empty.className='marshal-empty';
  empty.textContent='Select Guard above the map and tap a street to place a road marshal.';
  list.append(empty);
 }
 guards.forEach((item,i)=>{
  if(!coordsOk(item.position))return;
  const marker=L.marker(item.position,{draggable:true,icon:marshalIcon(),zIndexOffset:1100}).addTo(map);
  marker.bindTooltip(item.label||'Road marshal '+(i+1),{direction:'top',offset:[0,-20]});
  marker.on('dragend',()=>{
   const p=marker.getLatLng();item.position=[p.lat,p.lng];
   renderMarshals();scheduleSave();
  });
  marker.on('click',()=>{
   const popup=document.createElement('div');popup.className='marshal-popup';
   const heading=document.createElement('strong');heading.textContent=item.label||'Road marshal '+(i+1);
   const note=document.createElement('p');note.textContent='Planned road-closure position';
   const buttons=document.createElement('div');buttons.className='marshal-popup-actions';
   const edit=document.createElement('button');edit.type='button';edit.textContent='Rename';edit.addEventListener('click',()=>{map.closePopup();editMarshal(item)});
   const remove=document.createElement('button');remove.type='button';remove.textContent='Remove';remove.addEventListener('click',()=>{map.closePopup();removeMarshal(item.id)});
   buttons.append(edit,remove);popup.append(heading,note,buttons);
   marker.bindPopup(popup,{maxWidth:250}).openPopup();
  });
  marshalMarkers.push(marker);
  const row=document.createElement('div');row.className='marshal-list-row';
  const button=document.createElement('button');button.type='button';button.className='marshal-focus';
  button.textContent=(i+1)+'. '+(item.label||'Road marshal');
  button.onclick=()=>{map.setView(item.position,Math.max(map.getZoom(),15));marker.fire('click')};
  const del=document.createElement('button');del.type='button';del.className='secondary marshal-remove';del.textContent='×';del.setAttribute('aria-label','Remove road marshal '+(i+1));del.onclick=()=>removeMarshal(item.id);
  row.append(button,del);list.append(row);
 });
}
function addMarshal(position){
 const shot=getShot();if(!shot||!coordsOk(position))return;
 if(!Array.isArray(shot.marshals))shot.marshals=[];
 if(shot.marshals.length>=200){alert('Maximum 200 road marshals per shot.');return}
 shot.marshals.push({id:crypto.randomUUID(),position,label:'Road marshal '+(shot.marshals.length+1)});
 renderMarshals();scheduleSave();
}

function textBoxes(shot){if(!Array.isArray(shot.textBoxes))shot.textBoxes=[];return shot.textBoxes}
function drawingLayer(shot,points){
 const line=L.polyline(points,{color:'#ed4148',weight:3,opacity:1,bubblingMouseEvents:false}).addTo(map);
 const popup=document.createElement('div'),title=document.createElement('strong'),remove=document.createElement('button');
 title.textContent='Map drawing';remove.type='button';remove.className='annotation-delete';remove.textContent='Delete drawing';
 popup.append(title,remove);
 remove.onclick=()=>{if(getShot()!==shot)return;const i=sketches(shot).indexOf(points);if(i<0)return;shot.sketches.splice(i,1);map.closePopup();renderMap();scheduleSave()};
 line.bindPopup(popup);line.on('click',()=>{if(tool==='sketch')return;line.setStyle({weight:6});line.openPopup()});
 line.on('popupclose',()=>line.setStyle({weight:3}));
 return line;
}
function renderTextBoxes(shot){
 textMarkers.forEach(marker=>marker.remove());textMarkers=[];
 textBoxes(shot).forEach(box=>{
  if(!coordsOk(box.position))return;
  const content=document.createElement('div');content.className='map-text-box';content.textContent=box.text;
  const marker=L.marker(box.position,{draggable:true,bubblingMouseEvents:false,icon:L.divIcon({className:'map-text-marker',html:content,iconSize:null,iconAnchor:[0,0]})}).addTo(map);
  marker.on('dragend',()=>{if(getShot()!==shot)return;const point=marker.getLatLng();box.position=[point.lat,point.lng];scheduleSave()});
  const popup=document.createElement('div');popup.className='map-text-editor';
  const input=document.createElement('textarea');input.value=box.text;input.maxLength=300;input.rows=3;input.setAttribute('aria-label','Map text');
  const save=document.createElement('button');save.type='button';save.textContent='Save text';
  save.onclick=()=>{if(getShot()!==shot)return;const value=input.value.trim();if(!value){input.focus();return}box.text=value;map.closePopup();renderMap();scheduleSave()};
  const remove=document.createElement('button');remove.type='button';remove.className='annotation-delete';remove.textContent='Delete text box';
  remove.onclick=()=>{if(getShot()!==shot)return;const i=textBoxes(shot).indexOf(box);if(i<0)return;shot.textBoxes.splice(i,1);map.closePopup();renderMap();scheduleSave()};
  popup.append(input,save,remove);marker.bindPopup(popup,{maxWidth:260});textMarkers.push(marker);
 });
}
function addTextBox(position){
 const shot=getShot();if(!shot)return;
 if(textBoxes(shot).length>=100){alert('Maximum 100 text boxes per shot.');return}
 const value=prompt('Text to place on the map','');if(!value?.trim())return;
 textBoxes(shot).push({id:crypto.randomUUID(),position,text:value.trim().slice(0,300)});
 setTool('move');renderMap();scheduleSave();
}

function pointIcon(i){ensureWaypointSelection();return L.divIcon({className:'',html:'<div class="waypoint-number" data-selected="'+selectedWaypoints.has(i)+'" data-active="'+(i===activeWaypoint)+'">'+(i+1)+'</div>',iconSize:[28,28],iconAnchor:[14,14]})}
function updateStats(){const shot=getShot();const n=shot?.route.length||0;let distance=0;if(map&&shot)for(let i=1;i<n;i++)distance+=map.distance(shot.route[i-1],shot.route[i]);$('route-stats').textContent=n+' waypoint'+(n===1?'':'s')+' · '+(distance>=1000?(distance/1000).toFixed(2)+' km':Math.round(distance)+' m');$('map-help').textContent=tool==='measure'?(measurementPoints.length===0?'Tap the first point to measure a straight-line map distance.':measurementPoints.length===1?'Tap the second point. Distance is approximate and horizontal.':'Approximate horizontal distance. Tap to start a new measurement; tap the ruler to close.'):tool==='route'?'Tap the map to add points. Drag a point to adjust it.':tool==='camera'?'Tap where the camera should point.':tool==='cone'?'Tap to add a waypoint with its attached camera cone.':tool==='marshal'?'Tap a street to place a red road marshal. Drag to move; tap to edit or remove.':tool==='text'?'Tap the map to place a text box. Tap existing text to edit or delete; drag to move.':tool==='sketch'?'Draw in red. Tap the finished drawing to select and delete it.':'Pan and zoom the map, or choose a drawing tool.'}
function renderMap(){if(!map||!current)return;if(droneHeightRing){droneHeightRing.remove();droneHeightRing=null}if(routeLine)routeLine.remove();if(aimLine)aimLine.remove();sketchLayers.forEach(layer=>layer.remove());sketchLayers=[];markers.forEach(x=>x.remove());markers=[];if(targetMarker)targetMarker.remove();targetMarker=null;const shot=getShot();if(!shot)return;ensureWaypointSelection();sketches(shot).forEach(points=>{if(Array.isArray(points)&&points.length>1&&points.every(coordsOk))sketchLayers.push(drawingLayer(shot,points))});routeLine=L.polyline(shot.route,{color:'#2fb474',weight:4,opacity:.95}).addTo(map);shot.route.forEach((p,i)=>{if(!coordsOk(p))return;const m=L.marker(p,{draggable:!selectingWaypoints,icon:pointIcon(i)}).addTo(map);m.bindTooltip('Waypoint '+(i+1));m.on('click',()=>{ensureWaypointSelection();if(selectingWaypoints){if(selectedWaypoints.has(i))selectedWaypoints.delete(i);else selectedWaypoints.add(i);renderMap()}else selectWaypoint(i)});m.on('dragend',()=>{shot.route[i]=[m.getLatLng().lat,m.getLatLng().lng];renderMap();scheduleSave()});markers.push(m)});if(coordsOk(shot.target)){targetMarker=L.marker(shot.target,{draggable:true,icon:L.divIcon({className:'',html:'<div class="camera-target">⌖</div>',iconSize:[24,24],iconAnchor:[12,12]})}).addTo(map);targetMarker.bindTooltip('Camera target');targetMarker.on('dragend',()=>{shot.target=[targetMarker.getLatLng().lat,targetMarker.getLatLng().lng];renderMap();scheduleSave()});if(shot.route.length)aimLine=L.polyline([shot.route[shot.route.length-1],shot.target],{color:'#df956d',weight:2,dashArray:'6,7',opacity:.85}).addTo(map)}renderCones(shot);
 const active=shot.route[activeWaypoint];
 if(radiusEnabled()&&coordsOk(active)){
  const raw=Number(shot.waypointCones?.[activeWaypoint]?.altitudeAGL);
  const radius=Number.isFinite(raw)&&raw>=2?Math.min(500,raw):30;
  droneHeightRing=L.circle(active,{
   radius,color:'#ff9b38',weight:2.5,opacity:1,
   fillColor:'#f58a29',fillOpacity:.14,interactive:false
  }).addTo(map);
 }
 renderTextBoxes(shot);renderMarshals();updateConeControls();updateStats();document.dispatchEvent(new Event('offset:map-rendered'))}
// Follow the simulated drone footprint on the main map while previewing a flight.
document.addEventListener('offset:flight-position',event=>{
 const p=event.detail;
 if(!radiusEnabled()||!map||!p||!Number.isFinite(p.lat)||!Number.isFinite(p.lng)||!Number.isFinite(p.radius))return;
 if(!droneHeightRing){
  droneHeightRing=L.circle([p.lat,p.lng],{
   radius:p.radius,color:'#ff9b38',weight:2.5,opacity:1,
   fillColor:'#f58a29',fillOpacity:.14,interactive:false
  }).addTo(map);
 }else{
  droneHeightRing.setLatLng([p.lat,p.lng]);
  droneHeightRing.setRadius(p.radius);
 }
});
function setTool(next){if(next!=='measure')clearMeasurement();tool=next;if(next==='measure'){const wasSelecting=selectingWaypoints;selectingWaypoints=false;if(wasSelecting)renderMap();else renderWaypointEditor();map?.closePopup()}syncMeasurementDragging();if(map){if(window.matchMedia('(pointer: coarse)').matches&&next!=='move'&&next!=='measure')map.dragging.disable();else map.dragging.enable();map.touchZoom[ next==='sketch'?'disable':'enable' ]();}document.querySelectorAll('[data-tool]').forEach(b=>{const active=b.dataset.tool===tool;b.classList.toggle('active',active);b.setAttribute('aria-pressed',String(active))});if(map){map.getContainer().style.cursor=tool==='move'?'grab':'crosshair';map.doubleClickZoom[tool==='move'?'enable':'disable']()};updateStats()}
function applyBasemapStyle(value){
 const minimal=value!=='standard';
 const node=$('planner-map');
 if(node)node.classList.toggle('basemap-minimal',minimal);
 const select=$('basemap-style');
 if(select)select.value=minimal?'minimal':'standard';
}
$('basemap-style').onchange=e=>applyBasemapStyle(e.target.value);
applyBasemapStyle('minimal');
function initMap(){if(!window.L){$('map-help').textContent='Map library unavailable. Check internet connection.';return}map=L.map('planner-map',{zoomControl:true}).setView([59.9139,10.7522],12);L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'© OpenStreetMap contributors'}).addTo(map);map.on('click',e=>{const shot=getShot();if(!shot||selectingWaypoints)return;const p=[e.latlng.lat,e.latlng.lng];if(tool==='route'){shot.route.push(p);activeWaypoint=shot.route.length-1;cones(shot);renderMap();scheduleSave()}else if(tool==='camera'){shot.target=p;renderMap();scheduleSave()}else if(tool==='cone'){shot.route.push(p);activeWaypoint=shot.route.length-1;cones(shot);renderMap();scheduleSave()}else if(tool==='marshal'){addMarshal(p)}else if(tool==='text'){addTextBox(p)}});setupSketchEvents();setupMeasurement();document.dispatchEvent(new CustomEvent('offset:planner-map-ready',{detail:{map}}));map.on('moveend',()=>{if(current&&!loading)scheduleSave()});window.addEventListener('orientationchange',()=>setTimeout(()=>map.invalidateSize(),350));window.addEventListener('resize',()=>map.invalidateSize());}

function setupSketchEvents(){
  if(!map)return;
  const container=map.getContainer();
  let drawing=null, draft=null, pointerId=null;
  function fromEvent(e){const rect=container.getBoundingClientRect();return map.containerPointToLatLng([e.clientX-rect.left,e.clientY-rect.top])}
  container.addEventListener('pointerdown',e=>{
    if(tool!=='sketch'||!getShot()||!e.isPrimary||e.button>0||e.target.closest('.leaflet-control,.leaflet-popup,.map-text-box,button,input,textarea'))return;
    pointerId=e.pointerId;
    drawing=[];const p=fromEvent(e);drawing.push([p.lat,p.lng]);
    draft=L.polyline(drawing,{color:'#ed4148',weight:3}).addTo(map);
    try{container.setPointerCapture(e.pointerId)}catch{}
    e.preventDefault();
  });
  container.addEventListener('pointermove',e=>{
    if(!drawing||pointerId!==e.pointerId||tool!=='sketch')return;
    const p=fromEvent(e),previous=drawing[drawing.length-1],next=[p.lat,p.lng];
    if(map.distance(previous,next)>1){drawing.push(next);draft.setLatLngs(drawing)}
    e.preventDefault();
  });
  const finish=e=>{
    if(!drawing||pointerId!==e.pointerId)return;
    const points=drawing;drawing=null;pointerId=null;
    if(draft){draft.remove();draft=null}
    if(points.length>1){sketches(getShot()).push(points.slice(0,500));setTool('move');renderMap();scheduleSave()}
  };
  container.addEventListener('pointerup',finish);
  container.addEventListener('pointercancel',finish);
  container.addEventListener('lostpointercapture',finish);
}
function locationStatus(message,error=false){
  const node=$('map-location-status');node.textContent=message;node.hidden=!message;node.classList.toggle('error',error);
}
function goToLocation(lat,lng,zoom=15,name='Search result'){
  if(!map)return;const point=[lat,lng];
  map.setView(point,zoom,{animate:true});
  if(searchPin)searchPin.remove();
  searchPin=L.marker(point).addTo(map).bindPopup(name);
  if(name)searchPin.openPopup();
}
function setupLocationTools(){
  const input=$('map-search'),results=$('map-search-results'),form=$('map-search-form');
  let searchAbort=null;
  const clearResults=()=>{results.hidden=true;results.replaceChildren()};
  form.addEventListener('submit',async event=>{
    event.preventDefault();const query=input.value.trim();clearResults();
    if(!query)return;
    if(searchAbort)searchAbort.abort();
    searchAbort=new AbortController();
    const raw=query.match(/^\s*(-?\d{1,2}(?:\.\d+)?)\s*[,; ]\s*(-?\d{1,3}(?:\.\d+)?)\s*$/);
    if(raw&&Math.abs(Number(raw[1]))<=90&&Math.abs(Number(raw[2]))<=180){
      goToLocation(Number(raw[1]),Number(raw[2]),15,'Coordinates');locationStatus('Showing coordinates');return;
    }
    locationStatus('Searching…');
    const button=$('map-search-submit');button.disabled=true;
    try{
      const url='https://nominatim.openstreetmap.org/search?format=jsonv2&limit=5&addressdetails=1&q='+encodeURIComponent(query);
      const response=await fetch(url,{signal:searchAbort.signal,headers:{Accept:'application/json'}});
      if(!response.ok)throw Error('Search service unavailable');
      const data=await response.json();
      if(!Array.isArray(data)||!data.length){locationStatus('No locations found. Try a more specific address.',true);return}
      results.replaceChildren();
      for(const place of data){
        const lat=Number(place.lat),lng=Number(place.lon);
        if(!Number.isFinite(lat)||!Number.isFinite(lng))continue;
        const b=document.createElement('button');b.type='button';b.className='map-search-result';b.setAttribute('role','option');
        b.textContent=place.display_name||query;
        b.addEventListener('click',()=>{
          const box=Array.isArray(place.boundingbox)?place.boundingbox.map(Number):null;
          if(box&&box.every(Number.isFinite)){map.fitBounds([[box[0],box[2]],[box[1],box[3]]],{maxZoom:16,padding:[22,22]})}
          else map.setView([lat,lng],15);
          if(searchPin)searchPin.remove();
          searchPin=L.marker([lat,lng]).addTo(map).bindPopup(place.display_name||query);
          input.value=place.display_name||query;clearResults();locationStatus('Location found');
        });results.append(b)
      }
      results.hidden=false;locationStatus('Select a result');
    }catch(err){if(err.name!=='AbortError')locationStatus('Location search failed. Check your connection.',true)}
    finally{button.disabled=false}
  });
  document.addEventListener('pointerdown',e=>{if(!form.contains(e.target))clearResults()});
  $('locate-me').addEventListener('click',()=>{
    if(!navigator.geolocation){locationStatus('Location is unavailable in this browser.',true);return}
    const button=$('locate-me');button.disabled=true;locationStatus('Requesting your location…');
    navigator.geolocation.getCurrentPosition(position=>{
      button.disabled=false;const lat=position.coords.latitude,lng=position.coords.longitude;
      if(!map)return;
      map.setView([lat,lng],16);
      if(locationPin)locationPin.remove();
      locationPin=L.circleMarker([lat,lng],{radius:8,color:'#fff',weight:3,fillColor:'#238af0',fillOpacity:1}).addTo(map);
      locationPin.bindPopup('Current location (approximate)').openPopup();
      locationStatus('Current position found · ±'+Math.round(position.coords.accuracy)+' m');
    },error=>{
      button.disabled=false;
      const messages={1:'Location permission denied. Enable Location for this website in iPhone settings.',2:'Could not determine your location. Try again outdoors.',3:'Location timed out. Try again.'};
      locationStatus(messages[error.code]||'Could not get current location.',true);
    },{enableHighAccuracy:true,timeout:15000,maximumAge:30000});
  });
}

function buildPrint(){const root=$('print-summary');root.replaceChildren();const h=document.createElement('h1');h.textContent='OFFSET · '+current.name;root.append(h);const sub=document.createElement('p');sub.textContent='Drone shot plan · '+new Date().toLocaleDateString('en-GB');root.append(sub);current.shots.forEach((s,i)=>{const article=document.createElement('article');const heading=document.createElement('h2');heading.textContent=(i+1)+'. '+s.name;article.append(heading);const meta=document.createElement('p');meta.textContent=[s.drone,s.lens,s.date,s.time].filter(Boolean).join(' · ');article.append(meta);const desc=document.createElement('p');desc.textContent=s.description;article.append(desc);const pt=document.createElement('p');pt.textContent='Flight path (latitude, longitude):';article.append(pt);const ol=document.createElement('ol');s.route.forEach(c=>{const li=document.createElement('li');li.textContent=c.map(v=>v.toFixed(6)).join(', ');ol.append(li)});article.append(ol);if(s.target){const tar=document.createElement('p');tar.textContent='Camera target: '+s.target.map(v=>v.toFixed(6)).join(', ');article.append(tar)}cones(s).forEach((c,i)=>{if(!c.visible)return;const p=document.createElement('p');p.textContent='Waypoint '+(i+1)+' camera cone: '+c.mode+' · '+Math.round(effectiveBearing(s,i))+'° · FOV '+c.fov+'° · '+c.range+' m';article.append(p)});if(roadMarshals(s).length){const h=document.createElement('h3');h.textContent='Road marshals / Sperrevakter';article.append(h);const guards=document.createElement('ol');roadMarshals(s).forEach((guard,i)=>{const li=document.createElement('li');li.textContent=(guard.label||'Road marshal '+(i+1))+' — '+guard.position.map(v=>Number(v).toFixed(6)).join(', ');guards.append(li)});article.append(guards)}root.append(article)})}
async function start(){try{await api('/api/admin/me')}catch{$('auth-warning').hidden=false;return}$('planner-app').hidden=false;for(let tries=0;!window.L&&tries<40;tries++)await new Promise(resolve=>setTimeout(resolve,150));initMap();setupLocationTools();await load();setTool('move');setTimeout(()=>map?.invalidateSize(),150)}
$('planner-refresh').addEventListener('click',async event=>{
 event.preventDefault();
 const link=$('planner-refresh');
 if(link.dataset.refreshing)return;
 link.dataset.refreshing='true';
 link.setAttribute('aria-busy','true');
 try{
  if(saveTimer){
   clearTimeout(saveTimer);saveTimer=null;
   if(current)await saveNow(current);
   if($('save-state').textContent==='Save failed'){
    alert('Could not save the latest changes. Refresh cancelled to avoid losing edits.');
    return;
   }
  }
  window.location.reload();
 }finally{
  delete link.dataset.refreshing;
  link.removeAttribute('aria-busy');
 }
});
$('pdf-closure-area').addEventListener('change',e=>{const shot=getShot();if(!shot)return;shot.pdfClosureArea=e.target.checked;scheduleSave()});
$('shot-date').addEventListener('change',syncDateDisplay);
$('shot-date').addEventListener('input',syncDateDisplay);
$('plan-select').onchange=e=>selectPlan(e.target.value);
$('mobile-shot').onchange=e=>{readFields();activeShot=Number(e.target.value)||0;activeWaypoint=0;render();renderMap()};
$('mobile-add-shot').onclick=()=>$('add-shot').click();
$('new-plan').onclick=()=>{const name=prompt('Name the new drone plan','Untitled plan');if(name?.trim())createPlan(name.trim().slice(0,120))};
$('rename-plan').onclick=()=>{if(!current)return;const name=prompt('Plan name',current.name);if(name?.trim()){current.name=name.trim().slice(0,120);render();scheduleSave()}};
$('delete-plan').onclick=async()=>{if(!current||!confirm('Delete the plan "'+current.name+'" and all its shots?'))return;try{await api('/api/admin/planner/'+encodeURIComponent(current.id),{method:'DELETE'});plans=plans.filter(p=>p.id!==current.id);if(plans.length)selectPlan(plans[0].id);else await createPlan('Untitled plan')}catch(e){alert(e.message)}};
$('add-shot').onclick=()=>{if(!current)return;readFields();current.shots.push(blankShot('Shot '+(current.shots.length+1)));activeShot=current.shots.length-1;render();renderMap();scheduleSave()};
$('remove-shot').onclick=()=>deleteShot(activeShot);
$('clear-shot').onclick=()=>{$('erase-drawing').click()};
$('erase-drawing').onclick=()=>{const shot=getShot();if(!shot||!confirm('Clear flight path, sketches, text boxes and camera target?'))return;shot.route=[];shot.waypointCones=[];activeWaypoint=0;shot.target=null;shot.sketches=[];shot.textBoxes=[];renderMap();scheduleSave()};
$('undo-point').onclick=()=>travelHistory('undo');
$('fit-route').onclick=()=>{const shot=getShot();if(!map||!shot)return;const points=[...shot.route,...sketches(shot).flat(),...textBoxes(shot).map(box=>box.position).filter(coordsOk),...(shot.target?[shot.target]:[]),...roadMarshals().map(x=>x.position).filter(coordsOk)];if(points.length)map.fitBounds(L.latLngBounds(points).pad(.25),{maxZoom:16})};

$('cone-visible').addEventListener('change',e=>{const s=getShot();if(!s)return;const c=coneData(s);if(!c)return;c.visible=e.target.checked;renderMap();scheduleSave()});
$('cone-mode').addEventListener('change',e=>{const s=getShot();if(!s)return;const c=coneData(s);if(!c)return;if(c.mode==='target'&&e.target.value==='manual')c.bearing=effectiveBearing(s);c.mode=e.target.value;renderMap();scheduleSave()});
$('cone-lens-auto').addEventListener('change',e=>{const shot=getShot(),c=shot&&coneData(shot);if(!c)return;c.lensAuto=e.target.checked;renderMap();scheduleSave()});
$('shot-lens').addEventListener('change',event=>setShotLens(event.target.value));
$('preview-lens').addEventListener('change',event=>setShotLens(event.target.value));
$('sim-lens').addEventListener('change',event=>setShotLens(event.target.value));
for(const key of ['bearing','fov','range'])$('cone-'+key).addEventListener('change',e=>{const s=getShot();if(!s)return;const c=coneData(s),value=Number(e.target.value);if(!c||!Number.isFinite(value))return;if(key==='fov')c.lensAuto=false;c[key]=key==='bearing'?((value%360)+360)%360:key==='fov'?Math.max(5,Math.min(170,value)):Math.max(1,Math.min(10000,value));renderMap();scheduleSave()});
$('cone-tilt').addEventListener('change',e=>{
 const shot=getShot(),c=shot&&coneData(shot);if(!c)return;
 const value=Number(e.target.value);if(!Number.isFinite(value))return;
 c.tilt=Math.max(-85,Math.min(35,Math.round(value)));
 renderMap();scheduleSave();
});
$('cone-reset-origin').onclick=()=>{const s=getShot();if(!s||!s.route.length)return;selectWaypoint(s.route.length-1)};
$('cone-waypoint').onchange=e=>selectWaypoint(Number(e.target.value)||0);

document.querySelectorAll('[data-tool]').forEach(b=>b.onclick=()=>setTool(b.dataset.tool==='measure'&&tool==='measure'?'move':b.dataset.tool));
document.querySelectorAll('.shot-editor input,.shot-editor textarea,.shot-editor select').forEach(el=>el.addEventListener('input',readFields));
$('export-plan').onclick=()=>{if(!current)return;const blob=new Blob([JSON.stringify({format:'OFFSET Planner v1',name:current.name,shots:current.shots},null,2)],{type:'application/json'});const a=document.createElement('a');a.href=URL.createObjectURL(blob);a.download=(current.name.replace(/[^a-z0-9_-]+/gi,'-')||'offset-plan')+'.json';a.click();setTimeout(()=>URL.revokeObjectURL(a.href),1000)};
let pdfExportPlan=null,pdfExportBusy=false,preparedPDF=null;
function updatePDFSelection(){
 const count=$('pdf-shot-list').querySelectorAll('input:checked').length;
 $('pdf-shot-count').textContent=count+' selected';$('pdf-shot-open').disabled=pdfExportBusy||count===0;
 $('pdf-shot-download').disabled=pdfExportBusy||count===0;
}
$('print-plan').onclick=()=>{
 if(!current)return;
 readFields();pdfExportPlan=current;invalidatePreparedPDF();
 $('pdf-shot-list').replaceChildren(...current.shots.map((shot,i)=>{
  const label=document.createElement('label'),input=document.createElement('input'),name=document.createElement('span');
  input.type='checkbox';input.value=i;input.checked=true;input.onchange=()=>{invalidatePreparedPDF();updatePDFSelection()};
  name.textContent=String(i+1).padStart(2,'0')+' · '+(shot.name||'Untitled shot');label.append(input,name);return label;
 }));
 updatePDFSelection();$('pdf-shot-dialog').showModal();
};
for(const [id,mode] of [['pdf-select-all','all'],['pdf-select-none','none'],['pdf-select-current','current']]){
 $(id).onclick=()=>{$('pdf-shot-list').querySelectorAll('input').forEach(input=>input.checked=mode==='all'||mode==='current'&&Number(input.value)===activeShot);invalidatePreparedPDF();updatePDFSelection()};
}
function pdfFileName(name){
 let clean=String(name||'').normalize('NFC').replace(/[\x00-\x1f\x7f/\\:*?"<>|]/g,'-').replace(/\s+/g,' ').replace(/^[. ]+|[. ]+$/g,'');
 clean=Array.from(clean).slice(0,100).join('').replace(/[. ]+$/g,'').replace(/\.pdf$/i,'').replace(/[. ]+$/g,'');
 return (clean||'OFFSET-plan')+'.pdf';
}
function invalidatePreparedPDF(){
 preparedPDF=null;$('pdf-shot-download').textContent='Download PDF';$('pdf-download-status').textContent='';
}
function pdfSelectionURL(){
 const indices=Array.from($('pdf-shot-list').querySelectorAll('input:checked'),input=>input.value);
 return '/api/admin/planner/'+encodeURIComponent(pdfExportPlan.id)+'/pdf?shots='+encodeURIComponent(indices.join(','));
}
async function savePreparedPDF(){
 const file=preparedPDF;if(!file)return;
 const native=window.webkit?.messageHandlers?.offsetPDF;
 if(native){
  const bytes=new Uint8Array(await file.arrayBuffer());let binary='';
  for(let i=0;i<bytes.length;i+=32768)binary+=String.fromCharCode(...bytes.subarray(i,i+32768));
  await native.postMessage({name:file.name,data:btoa(binary)});
 }else if(navigator.canShare?.({files:[file]})&&navigator.share){
  await navigator.share({files:[file]});
 }else{
  const url=URL.createObjectURL(file),link=document.createElement('a');
  link.href=url;link.download=file.name;document.body.append(link);link.click();link.remove();
  setTimeout(()=>URL.revokeObjectURL(url),60000);
 }
}
$('pdf-shot-download').onclick=async()=>{
 if(pdfExportBusy||!pdfExportPlan||$('pdf-shot-download').disabled)return;
 const status=$('pdf-download-status');
 if(preparedPDF){
  pdfExportBusy=true;updatePDFSelection();
  try{await savePreparedPDF();status.textContent='Use Save to Files in the share sheet to keep a copy.'}
  catch(error){if(error.name!=='AbortError')status.textContent='Could not save PDF: '+error.message}
  finally{pdfExportBusy=false;updatePDFSelection()}
  return;
 }
 pdfExportBusy=true;$('pdf-shot-form').querySelectorAll('input,button').forEach(control=>control.disabled=true);
 status.textContent='Preparing PDF…';clearTimeout(saveTimer);saveTimer=null;
 try{
  if(!await saveNow(pdfExportPlan))throw Error('Could not save the plan. Please try again.');
  const response=await fetch(pdfSelectionURL(),{credentials:'same-origin'});
  if(!response.ok)throw Error('PDF generation failed ('+response.status+')');
  if(!response.headers.get('content-type')?.includes('application/pdf'))throw Error('Please sign in again and retry.');
  const blob=await response.blob();
  const name=pdfFileName(pdfExportPlan.name);
  preparedPDF=new File([blob],name,{type:'application/pdf'});
  $('pdf-shot-download').textContent='Save / Share PDF';
  status.textContent='PDF ready. Tap Save / Share PDF, then choose Save to Files.';
 }catch(error){status.textContent=error.message||'Could not prepare PDF. Please try again.'}
 finally{pdfExportBusy=false;$('pdf-shot-form').querySelectorAll('input,button').forEach(control=>control.disabled=false);updatePDFSelection()}
};
$('pdf-shot-cancel').onclick=()=>$('pdf-shot-dialog').close();
$('pdf-shot-dialog').addEventListener('cancel',event=>{if(pdfExportBusy)event.preventDefault()});
$('pdf-shot-form').onsubmit=async event=>{
 event.preventDefault();const button=$('pdf-shot-open');if(button.disabled||!pdfExportPlan)return;
 const indices=Array.from($('pdf-shot-list').querySelectorAll('input:checked'),input=>Number(input.value));
 if(!indices.length)return;
 const plan=pdfExportPlan;pdfExportBusy=true;
 $('pdf-shot-form').querySelectorAll('input,button').forEach(control=>control.disabled=true);
 button.disabled=true;button.textContent='Preparing PDF…';
 clearTimeout(saveTimer);saveTimer=null;
 try{
  if(!await saveNow(plan)){alert('Could not save the plan. PDF export cancelled to avoid missing edits.');return}
  window.location.assign('/api/admin/planner/'+encodeURIComponent(plan.id)+'/pdf?shots='+encodeURIComponent(indices.join(',')));
 }catch(error){alert('PDF export failed: '+(error.message||'Please try again'))}
 finally{pdfExportBusy=false;$('pdf-shot-form').querySelectorAll('input,button').forEach(control=>control.disabled=false);button.textContent='Open PDF';updatePDFSelection()}
};
function moveWaypointFromPreview(index,point){
 const shot=getShot();
 if(!shot||!coordsOk(point)||index<0||index>=shot.route.length)return;
 shot.route[index]=point;activeWaypoint=index;renderMap();scheduleSave();
}
function moveTargetFromPreview(point){
 const shot=getShot();if(!shot||!coordsOk(point))return;
 shot.target=point;renderMap();scheduleSave();
}
window.OFFSETPlanner={getShot,getMap:()=>map,getActiveWaypoint:()=>activeWaypoint,
 setActiveWaypoint:selectWaypoint,moveWaypoint:moveWaypointFromPreview,moveTarget:moveTargetFromPreview,
 getBearing:()=>{const shot=getShot();return shot?effectiveBearing(shot):0},save:scheduleSave,renderMap,render,coordsOk,lensFov,setShotLens,shotTime,setShotTime};
window.addEventListener('visibilitychange',()=>{if(document.visibilityState==='hidden'&&saveTimer){clearTimeout(saveTimer);saveTimer=null;saveNow(current)}});start();
})();