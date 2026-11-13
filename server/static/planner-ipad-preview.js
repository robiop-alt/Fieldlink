(()=>{
'use strict';
/* Move the entire existing preview fold—not its inner map/3D canvas—to a full-
   width grid row below the planning map on iPad landscape. Preserve state,
   controls, event listeners, and the original desktop/mobile DOM position. */
const workspace=document.querySelector('.planner-workspace');
const sidebarEditor=document.querySelector('.planner-sidebar .shot-editor');
const preview=document.querySelector('.planner-fold[data-panel="preview"]');
const mapPanel=workspace?.querySelector('.map-panel');
if(!workspace||!sidebarEditor||!preview||!mapPanel)return;
const originalPlace=document.createComment('OFFSET shot preview original position');
sidebarEditor.insertBefore(originalPlace,preview);
const tabletLandscape=window.matchMedia(
 '(orientation:landscape) and (min-width:801px) and (max-width:1600px) and (pointer:coarse)'
);
let moved=false,scheduled=0;
function sync(){
 const shouldMove=tabletLandscape.matches;
 if(shouldMove===moved)return;
 moved=shouldMove;
 if(shouldMove){
  mapPanel.insertAdjacentElement('afterend',preview);
 }else{
  sidebarEditor.insertBefore(preview,originalPlace);
 }
 document.body.classList.toggle('planner-preview-below',moved);
 // Let both Leaflet previews and Cesium recalculate their own canvas size.
 requestAnimationFrame(()=>requestAnimationFrame(()=>{
  document.dispatchEvent(new Event('offset:planner-layout'));
 }));
}
function schedule(){
 if(scheduled)cancelAnimationFrame(scheduled);
 scheduled=requestAnimationFrame(()=>{scheduled=0;sync()});
}
sync();
tabletLandscape.addEventListener?.('change',schedule);
window.addEventListener('resize',schedule,{passive:true});
window.addEventListener('orientationchange',schedule,{passive:true});
window.visualViewport?.addEventListener('resize',schedule,{passive:true});
})();