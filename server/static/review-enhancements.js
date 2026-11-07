(()=>{
const clips=document.getElementById('clips'),toolbar=document.getElementById('offset-tools');
if(!clips||!toolbar)return;
let busy=false;
const seenKey='offset-seen-clips:'+location.pathname;
let seen=new Set();
try{seen=new Set(JSON.parse(localStorage.getItem(seenKey)||'[]'))}catch(_){}

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
  grid.onclick=()=>{clips.classList.remove('list-view');grid.classList.add('active');list.classList.remove('active')};
  list.onclick=()=>{clips.classList.add('list-view');list.classList.add('active');grid.classList.remove('active')};
  switcher.append(grid,list);toolbar.append(switcher);
 }
 clips.querySelectorAll('.clip').forEach(card=>{
  if(card.dataset.enhanced)return;card.dataset.enhanced='true';
  const meta=card.querySelector('.clipmeta'),small=meta?.querySelector('small');
  if(small){
   const details=document.createElement('details');const summary=document.createElement('summary');summary.textContent='Camera details';
   const content=document.createElement('p');content.textContent=small.textContent;
   details.append(summary,content);small.replaceWith(details);
  }
  const video=card.querySelector('video');if(video){
   video.setAttribute('preload','none');
   const id=video.getAttribute('src')?.match(/\/clips\/([^/]+)\/file/)?.[1];
   if(id&&!seen.has(id)){
    const badge=document.createElement('span');
    badge.className='offset-new-badge';badge.textContent='NEW';
    card.querySelector('.offset-video-stage')?.append(badge);
    video.addEventListener('play',()=>{
     seen.add(id);
     try{localStorage.setItem(seenKey,JSON.stringify([...seen].slice(-3000)))}catch(_){}
     badge.remove();
    },{once:true});
   }
  }
 });
 }finally{busy=false}
}
const observer=new MutationObserver(enhance);observer.observe(document.getElementById('content'),{childList:true,subtree:true});
enhance();
})();