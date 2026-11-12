(()=>{
'use strict';
/* Persistent, independent shot-editor panels. DOM wrapping keeps all existing
   control IDs, bindings and state intact, including 2D/3D map instances. */
const panels=[
 ['marshals','.shot-editor > .marshal-panel','Road marshals'],
 ['waypoints','.shot-editor > .cone-settings','Waypoint camera cone'],
 ['preview','.shot-editor > .shot-preview-panel','Shot preview'],
 ['terrain','.shot-editor > .terrain-panel','Terrain & elevation'],
 ['reference','.shot-editor > .reference-panel','Shot reference']
];
const key='offset-planner-collapsed-sections-v1';
let state={};
try{const saved=JSON.parse(localStorage.getItem(key)||'{}');if(saved&&typeof saved==='object'&&!Array.isArray(saved))state=saved}catch{}
const sections=[];
function save(){try{localStorage.setItem(key,JSON.stringify(state))}catch{}}
function rerenderMaps(){
 // Deferred until layout opens so Leaflet and MapLibre can read real dimensions.
 requestAnimationFrame(()=>requestAnimationFrame(()=>document.dispatchEvent(new Event('offset:map-rendered'))));
}
for(const [id,selector,label] of panels){
 const content=document.querySelector(selector);
 if(!content)continue;
 const section=document.createElement('details');
 section.className='planner-fold';
 section.dataset.panel=id;
 section.open=state[id]!==false;
 const summary=document.createElement('summary');
 summary.className='planner-fold-label';
 const title=document.createElement('span');
 title.className='planner-fold-title';title.textContent=label;
 const chevron=document.createElement('span');
 chevron.className='planner-fold-chevron';chevron.setAttribute('aria-hidden','true');
 summary.append(title,chevron);
 content.parentElement.insertBefore(section,content);
 section.append(summary,content);
 section.addEventListener('toggle',()=>{
  state[id]=section.open;save();
  if(section.open&&id==='preview')rerenderMaps();
 });
 sections.push(section);
}
if(sections.length){
 const menu=document.createElement('div');menu.className='planner-fold-actions';
 const collapse=document.createElement('button');collapse.type='button';collapse.className='secondary';collapse.textContent='Collapse all';
 const expand=document.createElement('button');expand.type='button';expand.className='secondary';expand.textContent='Expand all';
 collapse.addEventListener('click',()=>{sections.forEach(s=>s.open=false)});
 expand.addEventListener('click',()=>{sections.forEach(s=>s.open=true);rerenderMaps()});
 menu.append(collapse,expand);
 const editor=document.querySelector('.shot-editor');
 if(editor)editor.insertBefore(menu,editor.firstChild);
}
})();