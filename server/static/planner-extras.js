(()=>{'use strict';
/* OFFSET Drone Planner – opt-in planning overlays, terrain samples and references.
   All app state stays in the existing authenticated Planner shot document. */
const $=id=>document.getElementById(id);
const core=()=>window.OFFSETPlanner;
const rad=Math.PI/180, deg=180/Math.PI;
let sunLayer=null,terrainTimer=null,terrainAbort=null,terrainKey='',terrainEpoch=0,terrainCache=new Map();
const svgns='http://www.w3.org/2000/svg';

function shot(){return core()?.getShot()}
function map(){return core()?.getMap()}
function saved(){core()?.save()}
function valid(p){return core()?.coordsOk(p)}
function place(){const s=shot(),m=map();return s?.route?.find(valid)|| (m?[m.getCenter().lat,m.getCenter().lng]:null)}
function dateForShot(){if(shot()?.date)return shot().date;const d=new Date();return [d.getFullYear(),String(d.getMonth()+1).padStart(2,'0'),String(d.getDate()).padStart(2,'0')].join('-')}
function hours(){const h=Number(shot()?.sunHour);return Number.isFinite(h)?Math.min(23.75,Math.max(0,h)):12}
function solar(pos,day,hour){
 // NOAA GML approximation for solar coordinates. Civil time is the browser
 // timezone (explicitly stated in the UI), not a location-derived timezone.
 const time=new Date(day+'T'+String(Math.floor(hour)).padStart(2,'0')+':'+String(Math.round((hour%1)*60)).padStart(2,'0')+':00');
 if(!Number.isFinite(time.getTime()))return null;
 const n=Math.floor((Date.UTC(time.getFullYear(),time.getMonth(),time.getDate())-Date.UTC(time.getFullYear(),0,0))/86400000);
 const days=(time.getFullYear()%4===0&&(time.getFullYear()%100!==0||time.getFullYear()%400===0))?366:365;
 const gamma=2*Math.PI/days*(n-1+(hour-12)/24);
 const eq=229.18*(.000075+.001868*Math.cos(gamma)-.032077*Math.sin(gamma)-.014615*Math.cos(2*gamma)-.040849*Math.sin(2*gamma));
 const dec=.006918-.399912*Math.cos(gamma)+.070257*Math.sin(gamma)-.006758*Math.cos(2*gamma)+.000907*Math.sin(2*gamma)-.002697*Math.cos(3*gamma)+.00148*Math.sin(3*gamma);
 const latitude=pos[0]*rad,offset=-time.getTimezoneOffset()/60;
 const tst=hour*60+eq+4*pos[1]-60*offset;
 const ha=(tst/4-180)*rad;
 const sinElev=Math.sin(latitude)*Math.sin(dec)+Math.cos(latitude)*Math.cos(dec)*Math.cos(ha);
 const elevation=Math.asin(Math.min(1,Math.max(-1,sinElev)))*deg;
 const azimuth=(Math.atan2(Math.sin(ha),Math.cos(ha)*Math.sin(latitude)-Math.tan(dec)*Math.cos(latitude))*deg+180+360)%360;
 return {azimuth,elevation};
}
function endpoint(point,bearing,distance){
 const lat=point[0]*rad,lon=point[1]*rad,angle=bearing*rad,arc=distance/6371008.8;
 const lat2=Math.asin(Math.sin(lat)*Math.cos(arc)+Math.cos(lat)*Math.sin(arc)*Math.cos(angle));
 const lon2=lon+Math.atan2(Math.sin(angle)*Math.sin(arc)*Math.cos(lat),Math.cos(arc)-Math.sin(lat)*Math.sin(lat2));
 return [lat2*deg,lon2*deg];
}
function sunDisplay(){
 const s=shot(),m=map();
 if(sunLayer){sunLayer.remove();sunLayer=null}
 if(!s||!m)return;
 const h=hours(),pos=place();
 $('sun-hour').value=h;
 $('sun-hour-label').textContent=String(Math.floor(h)).padStart(2,'0')+':'+String(Math.round((h%1)*60)).padStart(2,'0');
 if(!pos){$('sun-status').textContent='Select a location';return}
 const sun=solar(pos,dateForShot(),h);
 if(!sun){$('sun-status').textContent='Select a valid date';return}
 $('sun-bearing').textContent='Sun '+Math.round(sun.azimuth)+'°';
 $('sun-elevation').textContent='Elevation '+sun.elevation.toFixed(1)+'°';
 const daylight=sun.elevation>-.833;
 $('sun-status').textContent=daylight?(sun.elevation<6?'Low sun':'Daylight'):'Below horizon';
 const sunPoint=endpoint(pos,sun.azimuth,Math.max(80,Math.min(500,m.getZoom()>14?150:350)));
 const shadowPoint=endpoint(pos,(sun.azimuth+180)%360,Math.max(55,Math.min(270,m.getZoom()>14?110:220)));
 // Lines indicate solar orientation and opposite shadow direction only. They
 // DO NOT represent real shadow lengths or terrain obstruction predictions.
 sunLayer=L.layerGroup([
  L.polyline([pos,sunPoint],{color:'#f0bd5e',weight:2,opacity:daylight?.95:.42,dashArray:'7 4',interactive:false,className:'sun-map-ray'}),
  L.polyline([pos,shadowPoint],{color:'#606b75',weight:2,opacity:daylight?.8:.3,dashArray:'3 5',interactive:false,className:'sun-map-ray'}),
  L.circleMarker(pos,{radius:4,weight:1,color:'#f5d38b',fillColor:'#f0bd5e',fillOpacity:1,interactive:false})
 ]).addTo(m);
}
function sunControls(){
 const current=shot();if(!current)return;
 $('sun-hour').value=hours();
 sunDisplay();
}
function resampleRoute(route){
 const m=map();if(!m||!route?.length)return [];
 const good=route.filter(valid);
 if(good.length<2)return good;
 let lengths=[],total=0;
 for(let i=1;i<good.length;i++){const d=m.distance(good[i-1],good[i]);lengths.push(d);total+=d}
 if(total<1)return [good[0]];
 const points=[];
 for(let j=0;j<25;j++){
  const goal=total*j/24;let traversed=0,idx=0;
  while(idx<lengths.length-1&&traversed+lengths[idx]<goal){traversed+=lengths[idx];idx++}
  const f=Math.max(0,Math.min(1,(goal-traversed)/Math.max(1e-6,lengths[idx])));
  points.push([good[idx][0]+(good[idx+1][0]-good[idx][0])*f,good[idx][1]+(good[idx+1][1]-good[idx][1])*f]);
 }
 return points;
}
function svg(name,attrs){
 const element=document.createElementNS(svgns,name);
 for(const [key,value] of Object.entries(attrs))element.setAttribute(key,String(value));
 return element;
}
function showProfile(values,metres){
 const chart=$('terrain-profile'),summary=$('terrain-summary');
 const lowest=Math.min(...values),highest=Math.max(...values);
 const span=Math.max(6,highest-lowest),bottom=highest+span*.08,top=lowest-span*.08;
 const coord=(i,v)=>[(12+i*(256/Math.max(1,values.length-1))).toFixed(1),(88-((v-top)/(bottom-top))*72).toFixed(1)];
 const points=values.map((v,i)=>coord(i,v).join(',')).join(' ');
 const polygon='12,88 '+points+' 268,88';
 chart.replaceChildren();
 for(let i=1;i<4;i++)chart.append(svg('line',{x1:10,y1:i*25,x2:270,y2:i*25,class:'terrain-grid'}));
 chart.append(svg('polygon',{points:polygon,class:'terrain-fill'}));
 chart.append(svg('polyline',{points,class:'terrain-line'}));
 const high=svg('text',{x:12,y:13});high.textContent=Math.round(highest)+' m';chart.append(high);
 const low=svg('text',{x:12,y:97});low.textContent=Math.round(lowest)+' m';chart.append(low);
 chart.hidden=false;
 summary.replaceChildren();
 const ascent=values.slice(1).reduce((n,v,i)=>n+Math.max(0,v-values[i]),0);
 for(const value of ['Route '+Math.round(metres)+' m','↑ '+Math.round(ascent)+' m','Terrain '+Math.round(lowest)+'–'+Math.round(highest)+' m']){
  const span=document.createElement('span');span.textContent=value;summary.append(span)
 }
 $('terrain-status').textContent='Ground elevation along route · 90 m model';
}
async function loadTerrain(force=false){
 const s=shot(),m=map();if(!s||!m)return;
 const points=resampleRoute(s.route),node=$('terrain-status'),chart=$('terrain-profile');
 if(!points.length){terrainKey='';chart.hidden=true;$('terrain-summary').replaceChildren();node.textContent='Draw a route to see terrain heights.';return}
 const key=points.map(p=>p.map(x=>x.toFixed(5)).join(',')).join(';');
 if(key===terrainKey&&!force)return;
 terrainKey=key;
 const epoch=++terrainEpoch;
 if(terrainAbort)terrainAbort.abort();
 if(terrainCache.has(key)){showProfile(terrainCache.get(key).values,terrainCache.get(key).metres);return}
 terrainAbort=new AbortController();
 node.textContent='Loading terrain…';chart.hidden=true;
 const metres=points.slice(1).reduce((n,p,i)=>n+m.distance(points[i],p),0);
 try{
  const response=await fetch('/api/admin/planner/elevation',{
   method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json'},
   body:JSON.stringify({points}),signal:terrainAbort.signal
  });
  if(!response.ok)throw Error('Elevation service unavailable ('+response.status+')');
  const data=await response.json();
  if(epoch!==terrainEpoch)return;
  const values=data.elevation;
  if(!Array.isArray(values)||values.length!==points.length||!values.every(Number.isFinite))throw Error('No valid terrain heights returned');
  terrainCache.set(key,{values,metres});
  if(terrainCache.size>28)terrainCache.delete(terrainCache.keys().next().value);
  showProfile(values,metres);
 }catch(err){
  if(err.name==='AbortError'||epoch!==terrainEpoch)return;
  chart.hidden=true;node.textContent='Terrain unavailable. Tap Refresh to retry.';
  $('terrain-summary').replaceChildren();
 }
}
function queueTerrain(){clearTimeout(terrainTimer);terrainTimer=setTimeout(()=>loadTerrain(),450)}
async function compressedReference(file){
 if(!file.type.startsWith('image/'))throw Error('Choose an image file.');
 if(file.size>25*1024*1024)throw Error('Image must be under 25 MB.');
 const image=await new Promise((resolve,reject)=>{
  const img=new Image();
  const blobUrl=URL.createObjectURL(file);
  img.onload=()=>{URL.revokeObjectURL(blobUrl);resolve(img)};
  img.onerror=()=>{URL.revokeObjectURL(blobUrl);reject(Error('Could not read image'))};
  img.src=blobUrl;
 });
 let scale=Math.min(1,1024/Math.max(image.naturalWidth,image.naturalHeight));
 const canvas=document.createElement('canvas'),context=canvas.getContext('2d');
 if(!context)throw Error('Image processing is unavailable');
 let data='';
 for(let attempt=0;attempt<7;attempt++){
  canvas.width=Math.max(1,Math.round(image.naturalWidth*scale));
  canvas.height=Math.max(1,Math.round(image.naturalHeight*scale));
  context.fillStyle='#fff';context.fillRect(0,0,canvas.width,canvas.height);
  context.drawImage(image,0,0,canvas.width,canvas.height);
  data=canvas.toDataURL('image/jpeg',Math.max(.48,.79-attempt*.055));
  if(data.length<230000)return data;
  scale*=.8;
 }
 throw Error('Reference could not be compressed enough.');
}
function referenceDisplay(){
 const s=shot(),has=!!s?.referenceImage&&/^data:image\/jpeg;base64,/.test(s.referenceImage);
 $('reference-preview').hidden=!has;
 $('reference-preview').src=has?s.referenceImage:'';
 $('reference-empty').hidden=has;
 $('reference-remove').hidden=!has;
}
function showPanels(){
 if(!shot())return;
 sunControls();referenceDisplay();queueTerrain();
}
$('sun-hour').addEventListener('input',()=>{
 const s=shot();if(!s)return;
 s.sunHour=Number($('sun-hour').value);
 sunDisplay();saved();
});
$('terrain-refresh').addEventListener('click',()=>{terrainKey='';loadTerrain(true)});
$('reference-upload').addEventListener('click',()=>$('reference-file').click());
$('reference-file').addEventListener('change',async e=>{
 const file=e.target.files?.[0];e.target.value='';if(!file||!shot())return;
 const ref=shot();
 const button=$('reference-upload');button.disabled=true;button.textContent='Adding…';
 try{
  const image=await compressedReference(file);
  ref.referenceImage=image;saved();referenceDisplay();
 }catch(err){alert(err.message||'Could not add reference image')}
 finally{button.disabled=false;button.textContent='+ Image'}
});
$('reference-remove').addEventListener('click',()=>{const s=shot();if(!s)return;s.referenceImage='';referenceDisplay();saved()});
$('shot-date').addEventListener('change',sunDisplay);
document.addEventListener('offset:shot-rendered',showPanels);
document.addEventListener('offset:map-rendered',()=>{sunDisplay();queueTerrain()});
document.addEventListener('offset:planner-map-ready',e=>{
 e.detail.map.on('moveend',()=>{if(!shot()?.route?.length)sunDisplay()});
});
})();