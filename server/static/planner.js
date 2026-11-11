(()=>{'use strict';
const $=id=>document.getElementById(id);
const clone=x=>JSON.parse(JSON.stringify(x));
let plans=[],current=null,activeShot=0,activeWaypoint=0,map,routeLine,aimLine,markers=[],marshalMarkers=[],sketchLayers=[],targetMarker,coneLayers=[],coneHeadingMarker,searchPin,locationPin,tool='move',saveTimer=null,saveEpoch=0,loading=false;
const coordsOk=p=>Array.isArray(p)&&p.length===2&&p.every(v=>typeof v==='number'&&Number.isFinite(v)&&Math.abs(v)<=180)&&Math.abs(p[0])<=90;
const blankShot=(name='New shot')=>({id:crypto.randomUUID(),name,description:'',drone:'',lens:'',date:'',time:'',altitude:'',speed:'',route:[],sketches:[],target:null,waypointCones:[]});
const api=async(path,opt={})=>{const r=await fetch(path,{credentials:'same-origin',headers:{'Content-Type':'application/json'},...opt});if(!r.ok){let message='Request failed ('+r.status+')';try{const d=await r.json();message=typeof d.detail==='string'?d.detail:message}catch{}throw Error(message)}return r.json()};
function state(message){$('save-state').textContent=message}
function getShot(){return current?.shots?.[activeShot]}
function sketches(shot){if(!Array.isArray(shot.sketches))shot.sketches=[];return shot.sketches}
function normalized(data){return {...data,shots:Array.isArray(data.shots)?data.shots:[],marshals:Array.isArray(data.marshals)?data.marshals:[]}}
async function load(){try{const data=await api('/api/admin/planner');plans=data.plans.map(normalized);current=plans[0]||null;if(!current){await createPlan('Untitled plan');return}selectPlan(current.id)}catch(e){state('Load error');alert(e.message)}}
async function createPlan(name){try{const result=await api('/api/admin/planner',{method:'POST',body:JSON.stringify({name})});plans.unshift(normalized(result));selectPlan(result.id);state('Saved')}catch(e){alert(e.message)}}
function selectPlan(id){if(saveTimer){clearTimeout(saveTimer);saveTimer=null;const pending=current;saveNow(pending)}current=plans.find(p=>p.id===id);activeShot=0;activeWaypoint=0;if(map&&current?.center&&Number.isFinite(current.center.lat)&&Number.isFinite(current.center.lng)){map.setView([current.center.lat,current.center.lng],current.zoom||12)}if(current&&!current.shots.length){current.shots.push(blankShot('Opening shot'));scheduleSave()}render();renderMap()}
function render(){const select=$('plan-select');select.replaceChildren(...plans.map(p=>{const el=document.createElement('option');el.value=p.id;el.textContent=p.name;return el}));select.value=current?.id||'';const mobile=$('mobile-shot');mobile.replaceChildren(...(current?.shots||[]).map((shot,i)=>{const option=document.createElement('option');option.value=String(i);option.textContent=(i+1)+'. '+(shot.name||'Untitled shot');return option}));mobile.value=String(activeShot);const list=$('shot-list');list.replaceChildren();current?.shots.forEach((shot,i)=>{const item=document.createElement('div');item.className='shot-item'+(i===activeShot?' active':'');const count=document.createElement('span');count.className='shot-count';count.textContent=shot.route.length+' pts';const n=document.createElement('span');n.className='shot-number';n.textContent=String(i+1).padStart(2,'0');const b=document.createElement('button');b.textContent=shot.name||'Untitled shot';b.onclick=()=>{readFields();activeShot=i;activeWaypoint=0;render();renderMap()};item.append(n,b,count);list.append(item)});const shot=getShot();loading=true;for(const key of ['name','description','drone','lens','date','time','altitude','speed'])$('shot-'+key).value=shot?.[key]??'';loading=false;updateConeControls();updateStats()}
function readFields(){const shot=getShot();if(!shot||loading)return;for(const key of ['name','description','drone','lens','date','time','altitude','speed'])shot[key]=$('shot-'+key).value;const active=$('shot-list').querySelector('.active button');if(active)active.textContent=shot.name||'Untitled shot';const selected=$('mobile-shot').options[activeShot];if(selected)selected.textContent=(activeShot+1)+'. '+(shot.name||'Untitled shot');scheduleSave()}
function scheduleSave(){if(!current)return;state('Unsaved…');clearTimeout(saveTimer);saveTimer=setTimeout(()=>{saveTimer=null;saveNow(current)},650)}
async function saveNow(plan){if(!plan)return;const epoch=++saveEpoch;state('Saving…');try{const res=await api('/api/admin/planner/'+encodeURIComponent(plan.id),{method:'PUT',body:JSON.stringify({name:plan.name,shots:clone(plan.shots),marshals:clone(plan.marshals||[]),center:map?map.getCenter():plan.center,zoom:map?map.getZoom():plan.zoom})});if(epoch===saveEpoch)state('Saved');plan.updated=res.updated}catch(e){if(epoch===saveEpoch)state('Save failed');console.error(e)}}


function defaultCone(bearing=0){return {visible:true,mode:'manual',bearing,fov:50,range:30}}
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
  c.fov=Math.min(170,Math.max(5,Number(c.fov)||50));
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
 for(const id of ['cone-visible','cone-mode','cone-bearing','cone-fov','cone-range'])$(id).disabled=!c;
 if(!c){$('cone-visible').checked=false;$('cone-mode-tip').textContent='Add a waypoint to create its attached camera cone.';$('cone-reset-origin').disabled=true;return}
 const follow=c.mode==='target'&&coordsOk(shot.target);
 $('cone-visible').checked=c.visible;$('cone-mode').value=c.mode;
 $('cone-bearing').value=Math.round(effectiveBearing(shot));$('cone-bearing').disabled=follow;
 $('cone-fov').value=c.fov;$('cone-range').value=c.range;
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
   color:selected?'#e69c47':'#d9a467',
   weight:selected?1.5:1,
   opacity:selected?.95:.82,
   fillColor:'#e9a85d',
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

function roadMarshals(){return Array.isArray(current?.marshals)?current.marshals:[]}
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
 current.marshals.splice(index,1);
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
 if(!current||!coordsOk(position))return;
 if(!Array.isArray(current.marshals))current.marshals=[];
 if(current.marshals.length>=200){alert('Maximum 200 road marshals per plan.');return}
 current.marshals.push({id:crypto.randomUUID(),position,label:'Road marshal '+(current.marshals.length+1)});
 renderMarshals();scheduleSave();
}

function pointIcon(i){return L.divIcon({className:'',html:'<div class="waypoint-number" data-active="'+(i===activeWaypoint)+'">'+(i+1)+'</div>',iconSize:[28,28],iconAnchor:[14,14]})}
function updateStats(){const shot=getShot();const n=shot?.route.length||0;let distance=0;if(map&&shot)for(let i=1;i<n;i++)distance+=map.distance(shot.route[i-1],shot.route[i]);$('route-stats').textContent=n+' waypoint'+(n===1?'':'s')+' · '+(distance>=1000?(distance/1000).toFixed(2)+' km':Math.round(distance)+' m');$('map-help').textContent=tool==='route'?'Tap the map to add points. Drag a point to adjust it.':tool==='camera'?'Tap where the camera should point.':tool==='cone'?'Tap to add a waypoint with its attached camera cone.':tool==='marshal'?'Tap a street to place a red road marshal. Drag to move; tap to edit or remove.':tool==='sketch'?'Drag a finger or mouse to draw freely.':'Pan and zoom the map, or choose a drawing tool.'}
function renderMap(){if(!map||!current)return;if(routeLine)routeLine.remove();if(aimLine)aimLine.remove();sketchLayers.forEach(layer=>layer.remove());sketchLayers=[];markers.forEach(x=>x.remove());markers=[];if(targetMarker)targetMarker.remove();targetMarker=null;const shot=getShot();if(!shot)return;sketches(shot).forEach(points=>{if(Array.isArray(points)&&points.length>1&&points.every(coordsOk))sketchLayers.push(L.polyline(points,{color:'#efaf67',weight:3,opacity:.95}).addTo(map))});routeLine=L.polyline(shot.route,{color:'#2fb474',weight:4,opacity:.95}).addTo(map);shot.route.forEach((p,i)=>{if(!coordsOk(p))return;const m=L.marker(p,{draggable:true,icon:pointIcon(i)}).addTo(map);m.bindTooltip('Waypoint '+(i+1));m.on('click',()=>selectWaypoint(i));m.on('dragend',()=>{shot.route[i]=[m.getLatLng().lat,m.getLatLng().lng];renderMap();scheduleSave()});markers.push(m)});if(coordsOk(shot.target)){targetMarker=L.marker(shot.target,{draggable:true,icon:L.divIcon({className:'',html:'<div class="camera-target">⌖</div>',iconSize:[24,24],iconAnchor:[12,12]})}).addTo(map);targetMarker.bindTooltip('Camera target');targetMarker.on('dragend',()=>{shot.target=[targetMarker.getLatLng().lat,targetMarker.getLatLng().lng];renderMap();scheduleSave()});if(shot.route.length)aimLine=L.polyline([shot.route[shot.route.length-1],shot.target],{color:'#df956d',weight:2,dashArray:'6,7',opacity:.85}).addTo(map)}renderCones(shot);renderMarshals();updateConeControls();updateStats()}
function setTool(next){tool=next;if(map){if(window.matchMedia('(pointer: coarse)').matches&&next!=='move')map.dragging.disable();else map.dragging.enable();map.touchZoom[ next==='sketch'?'disable':'enable' ]();}document.querySelectorAll('[data-tool]').forEach(b=>{const active=b.dataset.tool===tool;b.classList.toggle('active',active);b.setAttribute('aria-pressed',String(active))});if(map){map.getContainer().style.cursor=tool==='move'?'grab':'crosshair';map.doubleClickZoom[tool==='move'?'enable':'disable']()};updateStats()}
function applyBasemapStyle(value){
 const minimal=value!=='standard';
 const node=$('planner-map');
 if(node)node.classList.toggle('basemap-minimal',minimal);
 const select=$('basemap-style');
 if(select)select.value=minimal?'minimal':'standard';
}
$('basemap-style').onchange=e=>applyBasemapStyle(e.target.value);
applyBasemapStyle('minimal');
function initMap(){if(!window.L){$('map-help').textContent='Map library unavailable. Check internet connection.';return}map=L.map('planner-map',{zoomControl:true}).setView([59.9139,10.7522],12);L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'© OpenStreetMap contributors'}).addTo(map);map.on('click',e=>{const shot=getShot();if(!shot)return;const p=[e.latlng.lat,e.latlng.lng];if(tool==='route'){shot.route.push(p);activeWaypoint=shot.route.length-1;cones(shot);renderMap();scheduleSave()}else if(tool==='camera'){shot.target=p;renderMap();scheduleSave()}else if(tool==='cone'){shot.route.push(p);activeWaypoint=shot.route.length-1;cones(shot);renderMap();scheduleSave()}else if(tool==='marshal'){addMarshal(p)}});setupSketchEvents();document.dispatchEvent(new CustomEvent('offset:planner-map-ready',{detail:{map}}));map.on('moveend',()=>{if(current&&!loading)scheduleSave()});window.addEventListener('orientationchange',()=>setTimeout(()=>map.invalidateSize(),350));window.addEventListener('resize',()=>map.invalidateSize());}

function setupSketchEvents(){
  if(!map)return;
  const container=map.getContainer();
  let drawing=null, draft=null, pointerId=null;
  function fromEvent(e){const rect=container.getBoundingClientRect();return map.containerPointToLatLng([e.clientX-rect.left,e.clientY-rect.top])}
  container.addEventListener('pointerdown',e=>{
    if(tool!=='sketch'||!getShot()||!e.isPrimary||e.button>0||e.target.closest('.leaflet-control,button,input'))return;
    pointerId=e.pointerId;
    drawing=[];const p=fromEvent(e);drawing.push([p.lat,p.lng]);
    draft=L.polyline(drawing,{color:'#efaf67',weight:3}).addTo(map);
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
    if(points.length>1){sketches(getShot()).push(points.slice(0,500));renderMap();scheduleSave()}
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

function buildPrint(){const root=$('print-summary');root.replaceChildren();const h=document.createElement('h1');h.textContent='OFFSET · '+current.name;root.append(h);const sub=document.createElement('p');sub.textContent='Drone shot plan · '+new Date().toLocaleDateString('en-GB');root.append(sub);current.shots.forEach((s,i)=>{const article=document.createElement('article');const heading=document.createElement('h2');heading.textContent=(i+1)+'. '+s.name;article.append(heading);const meta=document.createElement('p');meta.textContent=[s.drone,s.lens,s.date,s.time,s.altitude&&s.altitude+' m AGL',s.speed&&s.speed+' m/s'].filter(Boolean).join(' · ');article.append(meta);const desc=document.createElement('p');desc.textContent=s.description;article.append(desc);const pt=document.createElement('p');pt.textContent='Flight path (latitude, longitude):';article.append(pt);const ol=document.createElement('ol');s.route.forEach(c=>{const li=document.createElement('li');li.textContent=c.map(v=>v.toFixed(6)).join(', ');ol.append(li)});article.append(ol);if(s.target){const tar=document.createElement('p');tar.textContent='Camera target: '+s.target.map(v=>v.toFixed(6)).join(', ');article.append(tar)}cones(s).forEach((c,i)=>{if(!c.visible)return;const p=document.createElement('p');p.textContent='Waypoint '+(i+1)+' camera cone: '+c.mode+' · '+Math.round(effectiveBearing(s,i))+'° · FOV '+c.fov+'° · '+c.range+' m';article.append(p)});root.append(article)});if(roadMarshals().length){const h=document.createElement('h2');h.textContent='Road marshals / Sperrevakter';root.append(h);const ol=document.createElement('ol');roadMarshals().forEach((guard,i)=>{const li=document.createElement('li');li.textContent=(guard.label||'Road marshal '+(i+1))+' — '+guard.position.map(v=>Number(v).toFixed(6)).join(', ');ol.append(li)});root.append(ol)}}
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
$('plan-select').onchange=e=>selectPlan(e.target.value);
$('mobile-shot').onchange=e=>{readFields();activeShot=Number(e.target.value)||0;activeWaypoint=0;render();renderMap()};
$('mobile-add-shot').onclick=()=>$('add-shot').click();
$('new-plan').onclick=()=>{const name=prompt('Name the new drone plan','Untitled plan');if(name?.trim())createPlan(name.trim().slice(0,120))};
$('rename-plan').onclick=()=>{if(!current)return;const name=prompt('Plan name',current.name);if(name?.trim()){current.name=name.trim().slice(0,120);render();scheduleSave()}};
$('delete-plan').onclick=async()=>{if(!current||!confirm('Delete the plan "'+current.name+'" and all its shots?'))return;try{await api('/api/admin/planner/'+encodeURIComponent(current.id),{method:'DELETE'});plans=plans.filter(p=>p.id!==current.id);if(plans.length)selectPlan(plans[0].id);else await createPlan('Untitled plan')}catch(e){alert(e.message)}};
$('add-shot').onclick=()=>{if(!current)return;readFields();current.shots.push(blankShot('Shot '+(current.shots.length+1)));activeShot=current.shots.length-1;render();renderMap();scheduleSave()};
$('remove-shot').onclick=()=>{if(!current||!confirm('Delete this shot?'))return;current.shots.splice(activeShot,1);if(!current.shots.length)current.shots.push(blankShot('Opening shot'));activeShot=Math.min(activeShot,current.shots.length-1);render();renderMap();scheduleSave()};
$('clear-shot').onclick=()=>{$('erase-drawing').click()};
$('erase-drawing').onclick=()=>{const shot=getShot();if(!shot||!confirm('Clear flight path, sketches and camera target?'))return;shot.route=[];shot.waypointCones=[];activeWaypoint=0;shot.target=null;shot.sketches=[];renderMap();scheduleSave()};
$('undo-point').onclick=()=>{const shot=getShot();if(!shot)return;if(tool==='marshal'&&roadMarshals().length){current.marshals.pop();renderMarshals();scheduleSave();return;}if(tool==='sketch'&&sketches(shot).length)shot.sketches.pop();else if(tool==='camera'&&shot.target)shot.target=null;else if(shot.route.length){shot.route.pop();cones(shot);activeWaypoint=Math.min(activeWaypoint,Math.max(0,shot.route.length-1));}else if(sketches(shot).length)shot.sketches.pop();else if(shot.target)shot.target=null;else return;renderMap();scheduleSave()};
$('fit-route').onclick=()=>{const shot=getShot();if(!map||!shot)return;const points=[...shot.route,...sketches(shot).flat(),...(shot.target?[shot.target]:[]),...roadMarshals().map(x=>x.position).filter(coordsOk)];if(points.length)map.fitBounds(L.latLngBounds(points).pad(.25),{maxZoom:16})};

$('cone-visible').addEventListener('change',e=>{const s=getShot();if(!s)return;const c=coneData(s);if(!c)return;c.visible=e.target.checked;renderMap();scheduleSave()});
$('cone-mode').addEventListener('change',e=>{const s=getShot();if(!s)return;const c=coneData(s);if(!c)return;if(c.mode==='target'&&e.target.value==='manual')c.bearing=effectiveBearing(s);c.mode=e.target.value;renderMap();scheduleSave()});
for(const key of ['bearing','fov','range'])$('cone-'+key).addEventListener('change',e=>{const s=getShot();if(!s)return;const c=coneData(s),value=Number(e.target.value);if(!c||!Number.isFinite(value))return;c[key]=key==='bearing'?((value%360)+360)%360:key==='fov'?Math.max(5,Math.min(170,value)):Math.max(1,Math.min(10000,value));renderMap();scheduleSave()});
$('cone-reset-origin').onclick=()=>{const s=getShot();if(!s||!s.route.length)return;selectWaypoint(s.route.length-1)};
$('cone-waypoint').onchange=e=>selectWaypoint(Number(e.target.value)||0);

document.querySelectorAll('[data-tool]').forEach(b=>b.onclick=()=>setTool(b.dataset.tool));
document.querySelectorAll('.shot-editor input,.shot-editor textarea,.shot-editor select').forEach(el=>el.addEventListener('input',readFields));
$('export-plan').onclick=()=>{if(!current)return;const blob=new Blob([JSON.stringify({format:'OFFSET Planner v1',name:current.name,shots:current.shots},null,2)],{type:'application/json'});const a=document.createElement('a');a.href=URL.createObjectURL(blob);a.download=(current.name.replace(/[^a-z0-9_-]+/gi,'-')||'offset-plan')+'.json';a.click();setTimeout(()=>URL.revokeObjectURL(a.href),1000)};
$('print-plan').onclick=()=>{if(!current)return;readFields();buildPrint();window.print()};
window.addEventListener('visibilitychange',()=>{if(document.visibilityState==='hidden'&&saveTimer){clearTimeout(saveTimer);saveTimer=null;saveNow(current)}});start();
})();