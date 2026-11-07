(()=>{
const clips=document.getElementById('clips'),toolbar=document.getElementById('offset-tools');
if(!clips||!toolbar)return;
let busy=false;
function enhance(){
 if(busy)return;busy=true;
 try{
 const filter=document.getElementById('metadata-filters');
 if(filter&&filter.parentElement!==document.getElementById('review-toolbar'))document.getElementById('review-toolbar').prepend(filter);
 if(toolbar.parentElement!==document.getElementById('review-toolbar'))document.getElementById('review-toolbar').append(toolbar);
 toolbar.querySelectorAll('p').forEach(p=>p.remove());
 if(!document.getElementById('view-switch')){
  const switcher=document.createElement('span');switcher.className='view-switch';switcher.id='view-switch';
  const grid=document.createElement('button');grid.type='button';grid.textContent='▦';grid.title='Grid view';grid.className='active';
  const list=document.createElement('button');list.type='button';list.textContent='☷';list.title='List view';
  let savedView='grid';try{savedView=localStorage.getItem('offset-review-layout')||'grid'}catch(_){}
  const setView=view=>{
   const isList=view==='list' && !window.matchMedia('(max-width:767px)').matches;
   clips.classList.toggle('list-view',isList);
   grid.classList.toggle('active',!isList);list.classList.toggle('active',isList);
   grid.setAttribute('aria-pressed',String(!isList));list.setAttribute('aria-pressed',String(isList));
   try{localStorage.setItem('offset-review-layout',view)}catch(_){}
  };
  grid.setAttribute('aria-label','Grid view');list.setAttribute('aria-label','List view');
  grid.onclick=()=>setView('grid');
  setView(savedView);
  list.onclick=()=>setView('list');
  switcher.append(grid,list);toolbar.append(switcher);
 }
 clips.querySelectorAll('.clip').forEach(card=>{
  if(card.dataset.enhanced)return;card.dataset.enhanced='true';
  const meta=card.querySelector('.clipmeta'),small=meta?.querySelector('small');
  if(small){
   const details=document.createElement('details');const summary=document.createElement('summary');summary.textContent='Camera details';
   const content=document.createElement('div');content.className='offset-camera-fields';
   content.innerHTML=small.innerHTML;
   details.append(summary,content);small.replaceWith(details);
  }
  const video=card.querySelector('video');if(video){video.setAttribute('preload','none');}
 });
 }finally{busy=false}
}
const observer=new MutationObserver(enhance);observer.observe(document.getElementById('content'),{childList:true,subtree:true});
enhance();
})();