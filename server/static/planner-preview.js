(()=>{'use strict';
const $=id=>document.getElementById(id),core=()=>window.OFFSETPlanner;
function display(){
 const api=core(),shot=api?.getShot();if(!shot)return;
 const active=api.getActiveWaypoint(),cone=shot.waypointCones?.[active];
 const hasWaypoint=api.coordsOk(shot.route?.[active]);
 const open=$('preview-3d-toggle').getAttribute('aria-pressed')==='true';
 const lens=String(shot.lens||'').trim(),autoFov=api.lensFov(shot);
 const fov=cone?.lensAuto&&autoFov?autoFov:Number(cone?.fov)||autoFov||50;
 const values=[hasWaypoint?'WP '+(active+1):'WP —',lens||'Lens not set','HFOV '+Math.round(fov*10)/10+'°','Heading '+Math.round(Number(api.getBearing())||0)+'°'];
 $('preview-data').replaceChildren(...values.map(text=>{const item=document.createElement('span');item.textContent=text;return item}));
 $('preview-3d-toggle').disabled=!open&&!hasWaypoint;
 $('preview-3d-toggle').title=hasWaypoint?'':'Add a waypoint on the main map first';
 $('preview-data').hidden=!open;
 document.querySelector('.shot-preview-panel .preview-altitude').hidden=!open;
 $('preview-note').textContent=!hasWaypoint?'Add a waypoint on the main map to preview this shot in 3D.':open?'Use Map + Camera to edit waypoints while viewing the shot.':'Plan the shot on the main map, then open the 3D preview.';
}
for(const event of ['offset:shot-rendered','offset:map-rendered','offset:shot-lens-changed','offset:preview-mode-changed'])document.addEventListener(event,display);
})();
