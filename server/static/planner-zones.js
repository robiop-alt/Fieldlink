/* OFFSET Planner · advisory Norwegian drone-zone layer.
 * Source: Luftfartstilsynet / dronesoner.no, NLOD 2.0.
 * This is never a flight-authorization indicator.
 */
(()=>{
'use strict';
const toggle=document.getElementById('airspace-toggle');
const status=document.getElementById('airspace-status');
if(!toggle||!status)return;
let map=null,layer=null,abort=null,timer=null,requestNumber=0;
let lastBounds='';
const setStatus=(message,kind='info')=>{status.textContent=message;status.dataset.state=kind};
const category=properties=>{
  const code=String(properties.restriction||'').toUpperCase();
  const reasons=Array.isArray(properties.reason)?properties.reason.map(x=>String(x).toUpperCase()):[];
  if(code==='PROHIBITED'||code==='REQ_AUTHORISATION'||code==='NO_FLY'||code==='RESTRICTED')return 'red';
  if(code==='CONDITIONAL'||code==='REQ_NOTIFICATION'||code==='OTHER_RESTRICTION'||reasons.some(x=>/DANGER|ENVIRONMENT|NATURE|WARNING/.test(x)))return 'yellow';
  return 'blue';
};
const style=feature=>{
  const c=category(feature.properties||{});
  const palette={red:'#e45e63',yellow:'#e5bc53',blue:'#5a9dde'};
  return {color:palette[c],weight:1.6,fillColor:palette[c],fillOpacity:c==='red'?.18:.12,opacity:.88};
};
function bindPopup(feature,zone){
  const props=feature.properties||{};
  const div=document.createElement('div');
  div.className='airspace-popup';
  const title=document.createElement('strong');title.textContent=props.name||'Drone zone';div.append(title);
  for(const [label,value] of [
    ['Restriction',props.restriction],
    ['Reason',Array.isArray(props.reason)?props.reason.join(', '):''],
    ['Altitude',(props.lowerLimit??'?')+'–'+(props.upperLimit??'?')+' '+(props.upperVerticalReference||'')],
    ['Details',props.message],
  ]){
    if(!value)continue;
    const p=document.createElement('p');
    const key=document.createElement('b');key.textContent=label+': ';
    p.append(key,document.createTextNode(String(value)));div.append(p);
  }
  const note=document.createElement('small');note.textContent='Advisory map only · verify NOTAM, AIP and permissions';div.append(note);
  zone.bindPopup(div,{maxWidth:300});
}
function remove(){
  if(abort){abort.abort();abort=null}
  if(timer){clearTimeout(timer);timer=null}
  if(layer&&map){map.removeLayer(layer);layer=null}
}
async function refresh(force=false){
  if(!map||!toggle.checked)return;
  if(map.getZoom()<7){
    remove();
    setStatus('Zoom in to load drone zones. Verify official sources before flying.','notice');
    return;
  }
  const bounds=map.getBounds();
  const south=bounds.getSouth(),west=bounds.getWest(),north=bounds.getNorth(),east=bounds.getEast();
  if(north-south>8||east-west>12||west < -180||east > 180){
    remove();
    setStatus('Zoom closer to see detailed Norwegian drone zones.','notice');return;
  }
  const params=new URLSearchParams({
    south:south.toFixed(5),west:west.toFixed(5),
    north:north.toFixed(5),east:east.toFixed(5),
  });
  const id=params.toString();
  if(!force&&id===lastBounds)return;
  lastBounds=id;
  if(abort)abort.abort();
  abort=new AbortController();
  const thisRequest=++requestNumber;
  setStatus('Loading official drone zone data…');
  try{
    const result=await fetch('/api/admin/planner/zones?'+id,{credentials:'same-origin',signal:abort.signal,headers:{Accept:'application/json'}});
    if(!result.ok)throw new Error(result.status===422?'Zoom in closer to view zones.':'Official drone-zone data is unavailable.');
    const data=await result.json();
    if(!map||!toggle.checked||thisRequest!==requestNumber)return;
    if(!data||data.type!=='FeatureCollection'||!Array.isArray(data.features))throw Error('Unexpected zone data');
    const next=L.geoJSON(data,{style,onEachFeature:bindPopup,interactive:true});
    if(layer)map.removeLayer(layer);
    layer=next.addTo(map);
    if(layer.bringToBack)layer.bringToBack();
    const count=data.features.length;
    const stale=data.stale?' · cached (may be outdated)':'';
    const clipped=data.truncated?' · incomplete at this zoom — zoom in':'';
    setStatus(count+' zone'+(count===1?'':'s')+' in view · source '+(data.source_date||'date unknown')+stale+clipped+' · advisory only. Check active NOTAM.',data.stale||data.truncated?'notice':'ready');
    if(data.stale||data.truncated)status.dataset.state='notice';
  }catch(error){
    if(error.name==='AbortError')return;
    if(thisRequest!==requestNumber)return;
    if(layer){map.removeLayer(layer);layer=null}
    lastBounds='';
    setStatus((error.message||'Could not load drone zones')+' No clearance can be inferred from this map.','error');
  }
}
function schedule(){
  if(timer)clearTimeout(timer);
  timer=setTimeout(()=>refresh(),350);
}
toggle.addEventListener('change',()=>{
  if(!toggle.checked){remove();lastBounds='';setStatus('Drone zone layer off.','notice')}
  else{lastBounds='';refresh(true)}
});
document.addEventListener('offset:planner-map-ready',event=>{
  map=event.detail?.map;
  if(!map)return;
  map.on('moveend',schedule);
  if(toggle.checked)refresh(true);
});
})();
