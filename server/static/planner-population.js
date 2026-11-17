/* SSB statistics: opt-in reference data, never an operational flight category. */
(()=>{
'use strict';
const toggle=document.getElementById('population-toggle'),status=document.getElementById('population-status');
if(!toggle||!status)return;
const retry=document.getElementById('population-retry');
const densityLegend=document.getElementById('population-density-scale');
// Fixed absolute bands: identical density has the same color at every location.
const densityBands=[
 {max:500,color:'#fff0a6',opacity:.24,label:'0–499'},
 {max:2000,color:'#ffc85b',opacity:.32,label:'500–1,999'},
 {max:5000,color:'#ff9b32',opacity:.42,label:'2,000–4,999'},
 {max:10000,color:'#f36925',opacity:.50,label:'5,000–9,999'},
 {max:20000,color:'#dc3d2a',opacity:.58,label:'10,000–19,999'},
 {max:Infinity,color:'#982340',opacity:.66,label:'20,000+'},
];
function populationStyle(feature){
 if(feature.properties.kind==='settlement')return{color:'#bd9bea',weight:1.8,fillColor:'#bd9bea',fillOpacity:.025};
 const density=feature.properties.density;
 if(!Number.isFinite(density)||density<0)return{color:'#849098',weight:.7,dashArray:'3 3',fillColor:'#849098',fillOpacity:.08};
 const band=densityBands.find(b=>density<b.max);
 return{color:band.color,weight:.65,opacity:.85,fillColor:band.color,fillOpacity:band.opacity};
}
if(densityLegend){
 for(const band of densityBands){
  const item=document.createElement('span'),swatch=document.createElement('i');
  swatch.style.backgroundColor=band.color;swatch.setAttribute('aria-hidden','true');
  item.append(swatch,document.createTextNode(band.label));densityLegend.append(item);
 }
 const missing=document.createElement('span'),swatch=document.createElement('i');swatch.style.backgroundColor='#849098';swatch.setAttribute('aria-hidden','true');missing.append(swatch,document.createTextNode('No data'));densityLegend.append(missing);
}

// Do not restore a browser checkbox or persist the switch. Default is always OFF.
toggle.checked=false;
let map=null,layer=null,abort=null,timer=null,sequence=0,data=null,lastBounds='';
const setStatus=(message,kind='info')=>{status.textContent=message;status.dataset.state=kind;if(densityLegend)densityLegend.hidden=!toggle.checked;if(retry)retry.hidden=kind!=='error'||!toggle.checked};
const validPoint=p=>Array.isArray(p)&&p.length>=2&&Number.isFinite(p[0])&&Number.isFinite(p[1]);
function orientation(a,b,c){return (b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0])}
function onSegment(p,a,b){return Math.abs(orientation(a,b,p))<1e-12&&p[0]>=Math.min(a[0],b[0])-1e-12&&p[0]<=Math.max(a[0],b[0])+1e-12&&p[1]>=Math.min(a[1],b[1])-1e-12&&p[1]<=Math.max(a[1],b[1])+1e-12}
function crosses(a,b,c,d){
 const x=orientation(a,b,c),y=orientation(a,b,d),z=orientation(c,d,a),w=orientation(c,d,b);
 return x*y<0&&z*w<0||onSegment(c,a,b)||onSegment(d,a,b)||onSegment(a,c,d)||onSegment(b,c,d);
}
function inRing(p,ring){
 let inside=false;
 for(let i=0,j=ring.length-1;i<ring.length;j=i++){
  const a=ring[j],b=ring[i];if(onSegment(p,a,b))return true;
  if((a[1]>p[1])!==(b[1]>p[1])&&p[0]<(b[0]-a[0])*(p[1]-a[1])/(b[1]-a[1])+a[0])inside=!inside;
 }
 return inside;
}
function polygons(feature){const g=feature.geometry;return g?.type==='Polygon'?[g.coordinates]:g?.type==='MultiPolygon'?g.coordinates:[]}
function contains(p,feature){return polygons(feature).some(poly=>inRing(p,poly[0])&&!poly.slice(1).some(h=>inRing(p,h)))}
function boundaryCrosses(a,b,feature){return polygons(feature).some(poly=>poly.some(ring=>ring.some((p,i)=>i>0&&crosses(a,b,ring[i-1],p))))}
function classify(route,collection){
 if(!collection?.complete||!Array.isArray(collection.coverage)||!route?.length||!route.every(validPoint))return 'unknown';
 const [south,west,north,east]=collection.coverage;
 // Coverage edges are clipping boundaries, not settlement boundaries.
 if(!route.every(([lat,lon])=>lat>south&&lat<north&&lon>west&&lon<east))return 'unknown';
 const features=collection.features.filter(f=>f.properties?.kind==='settlement');
 const points=route.map(([lat,lon])=>[lon,lat]);
 const inside=points.map(p=>features.some(f=>contains(p,f)));
 if(points.slice(1).some((p,i)=>features.some(f=>boundaryCrosses(points[i],p,f))))return 'mixed';
 return inside.every(Boolean)?'inside':inside.some(Boolean)?'mixed':'outside';
}
// Geometry helpers are shared with regression tests, without mutating the planner.
window.OffsetPopulation={classify,contains,populationStyle};
function summary(){
 if(!toggle.checked||!data)return;
 const shot=typeof getShot==='function'?getShot():null;
 const labels={inside:'Shot route: inside SSB settlement',outside:'Shot route: outside SSB settlements',mixed:'Shot route: crosses an SSB settlement boundary',unknown:'Shot route not fully assessed — keep the route in view'};
 const route=shot?.route||[];let message=route.length?labels[classify(route,data)]:'SSB layer loaded — draw a route to assess the shot';
 const index=typeof activeWaypoint==='number'?activeWaypoint:0;
 const selected=route[index];
 if(validPoint(selected)){
  const p=[selected[1],selected[0]],bounds=data.coverage;
  const covered=selected[0]>bounds[0]&&selected[0]<bounds[2]&&selected[1]>bounds[1]&&selected[1]<bounds[3];
  const cell=covered?data.features.find(f=>f.properties?.kind==='population'&&contains(p,f)):null;
  message+=' · selected WP: '+(cell?.properties?.density!=null?Number(cell.properties.density).toLocaleString()+' residents/km² (250 m cell)':'population value unavailable');
 }
 message+=' · SSB settlements '+data.years.settlements+', population '+data.years.population+'. Not a permit classification or live headcount.';
 setStatus(message,'reference');
}
function clear(){
 ++sequence;if(abort){abort.abort();abort=null}if(timer){clearTimeout(timer);timer=null}
 if(layer&&map)map.removeLayer(layer);layer=null;data=null;lastBounds='';
}
async function refresh(){
 if(!map||!toggle.checked)return;
 const b=map.getBounds(),south=b.getSouth(),west=b.getWest(),north=b.getNorth(),east=b.getEast();
 if(map.getZoom()<12||north-south>.25||east-west>.5){clear();setStatus('Zoom closer to load SSB settlement and 250 m population data.','notice');return}
 if(south<57||north>72||west<3||east>33){clear();setStatus('SSB layer currently covers mainland Norway.','notice');return}
 const params=new URLSearchParams({south:String(south),west:String(west),north:String(north),east:String(east)}),key=params.toString();
 if(key===lastBounds&&data){summary();return}
 clear();lastBounds=key;const request=++sequence;abort=new AbortController();const controller=abort;
 const timeout=setTimeout(()=>controller.abort(),60000);
 setStatus('Loading SSB settlement and population data…');
 try{
  const response=await fetch('/api/admin/planner/population?'+key,{credentials:'same-origin',signal:controller.signal});
  if(!response.ok){let detail;try{detail=(await response.json()).detail}catch(_){}throw Error(typeof detail==='string'?detail:'SSB data unavailable')}
  const next=await response.json();
  if(!toggle.checked||request!==sequence)return;
  if(next.type!=='FeatureCollection'||!next.complete||!Array.isArray(next.features)||!Array.isArray(next.coverage))throw Error('Incomplete SSB response');
  if(!map.getPane('population-ssb')){map.createPane('population-ssb');const pane=map.getPane('population-ssb');pane.style.zIndex='320';pane.style.pointerEvents='none'}
  layer=L.geoJSON(next,{pane:'population-ssb',interactive:false,style:populationStyle}).addTo(map);
  data=next;summary();
 }catch(error){
  if(request!==sequence||!toggle.checked)return;
  clear();setStatus((error.name==='AbortError'?'SSB request timed out':error.message)+'. Area assessment unavailable.','error');
 }finally{clearTimeout(timeout)}
}
function schedule(){if(!toggle.checked)return;if(timer)clearTimeout(timer);timer=setTimeout(refresh,400)}
if(retry)retry.addEventListener('click',()=>{lastBounds='';refresh()});
toggle.addEventListener('change',()=>{if(!toggle.checked){clear();setStatus('SSB population layer off.','notice')}else refresh()});
document.addEventListener('offset:planner-map-ready',event=>{map=event.detail?.map;if(map){map.on('moveend',()=>{if(toggle.checked){clear();setStatus('Map changed — updating SSB data…');schedule()}});if(toggle.checked)refresh()}});
document.addEventListener('offset:map-rendered',summary);
window.addEventListener('pageshow',event=>{if(event.persisted){toggle.checked=false;clear();setStatus('SSB population layer off.','notice')}});
})();
