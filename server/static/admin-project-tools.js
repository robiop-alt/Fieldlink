/* OFFSET Admin: purposeful per-project Share and Manage drawers. */
(()=>{
const root=document.getElementById('projects');
const statusFilter=document.getElementById('project-status-filter');
if(!root)return;
statusFilter?.addEventListener('change',()=>renderProjects());

function el(tag,className,text){
  const node=document.createElement(tag);
  if(className)node.className=className;
  if(text!==undefined)node.textContent=text;
  return node;
}
function action(label,handler,style='secondary'){
  const button=el('button',style,label);
  button.type='button';
  button.addEventListener('click',handler);
  return button;
}
function copyValue(value,feedback){
  if(!value)return;
  navigator.clipboard?.writeText(value).then(()=>{
    feedback.textContent='Copied to clipboard';
  }).catch(()=>window.prompt('Copy',value)) ?? window.prompt('Copy',value);
}
function closePanel(){
  root.querySelectorAll('.admin-project-drawer').forEach(item=>item.remove());
  root.querySelectorAll('.project-review-settings-popover').forEach(item=>item.remove());
}
function drawer(project,title,description){
  closePanel();
  const panel=el('section','admin-project-drawer');
  panel.setAttribute('aria-label',title+' · '+project.name);
  const header=el('div','admin-drawer-heading');
  const info=el('div');
  info.append(el('div','admin-drawer-eyebrow',project.name),el('h3',null,title));
  header.append(info,action('Close',()=>panel.remove()));
  panel.append(header);
  if(description)panel.append(el('p','admin-drawer-description',description));
  root.prepend(panel);
  panel.scrollIntoView({block:'nearest',behavior:'auto'});
  return panel;
}
function row(label,value){
 const wrap=el('div','admin-drawer-data');
 wrap.append(el('span',null,label),el('strong',null,value||''));
 return wrap;
}
async function share(project){
 const panel=drawer(project,'Share project','One permanent project link. Access follows the current project PIN settings.');
 const url=location.origin+'/r/'+encodeURIComponent(project.slug);
 const link=el('div','admin-share-link');
 const input=el('input');input.type='text';input.readOnly=true;input.value=url;input.setAttribute('aria-label','Project review URL');
 link.append(input,action('Copy link',()=>copyValue(url,notice)));
 panel.append(link);
 const info=el('div','admin-share-content');
 const qr=el('div','admin-share-qr');
 const img=el('img');img.alt='QR code for project review link';img.width=160;img.height=160;
 const qrStatus=el('span','hint','Loading QR…');
 qr.append(qrStatus,img);
 img.hidden=true;
 img.onload=()=>{qrStatus.remove();img.hidden=false};
 img.onerror=()=>{qrStatus.textContent='QR not available right now'};
 img.src='/api/admin/projects/'+encodeURIComponent(project.id)+'/qr';
 const details=el('div','admin-share-details');
 details.append(row('Access',project.public_review?'Link access · no PIN':'PIN required'));
 details.append(row('Downloads',project.allow_downloads?'Allowed':'Disabled'));
 const pinData=el('div','admin-share-pin');
 if(!project.public_review){
   pinData.append(row('Project PIN','Loading…'));
 }
 details.append(pinData);
 info.append(qr,details);
 panel.append(info);
 const notice=el('p','admin-drawer-feedback');notice.setAttribute('role','status');
 const controls=el('div','admin-drawer-controls');
 controls.append(action('Open review',()=>window.open(url,'_blank','noopener')));
 controls.append(action('Manage access',()=>manage(project.id)));
 panel.append(controls,notice);
 if(!project.public_review){
   try{
      const settings=await api('/api/admin/projects/'+encodeURIComponent(project.id)+'/review-settings',{cache:'no-store'});
      if(!panel.isConnected)return;
      pinData.replaceChildren();
      pinData.append(row('Project PIN',settings.pin||'Not available'));
      if(settings.pin){
        pinData.append(action('Copy PIN',()=>copyValue(settings.pin,notice)));
      }
      if(settings.pin_expires_at)
        pinData.append(el('p','hint',settings.pin_expired?'PIN expired':'PIN valid until '+new Date(settings.pin_expires_at*1000).toLocaleDateString('en-GB')));
   }catch(err){pinData.replaceChildren(el('p','hint','PIN unavailable: '+(err.message||'Try again')))}
 }
}
function fmtDate(t){
 return new Date(Number(t)*1000).toLocaleString('en-GB',{dateStyle:'medium',timeStyle:'short'});
}
async function loadActivity(project,panel,container){
 container.replaceChildren(el('p','hint','Loading upload history…'));
 try{
   const data=await api('/api/admin/projects/'+encodeURIComponent(project.id)+'/activity',{cache:'no-store'});
   if(!panel.isConnected)return;
   container.replaceChildren();
   const warnings=data.warnings||[];
   if(warnings.length){
     const alert=el('div','admin-activity-warning');
     alert.append(el('strong',null,warnings.length+' transfer warning'+(warnings.length!==1?'s':'')));
     warnings.forEach(w=>alert.append(el('p',null,w.filename+' · '+w.message)));
     container.append(alert);
   }
   const entries=data.activity||[];
   if(!entries.length){container.append(el('p','hint','No uploaded clips or active transfers yet.'));return}
   const list=el('div','admin-activity-list');
   for(const item of entries){
     const rowEl=el('div','admin-activity-item');
     const desc=el('div','admin-activity-desc');
     desc.append(el('strong',null,item.filename),el('small',null,fmtDate(item.time)));
     const stage=el('span','admin-activity-stage stage-'+item.kind,
       item.kind==='received'?'Received':item.kind==='stalled'?'Waiting / stalled':'Uploading');
     rowEl.append(desc,stage);list.append(rowEl);
   }
   container.append(list);
 }catch(err){container.replaceChildren(el('p','error',err.message||'Could not load history'))}
}
async function manageClips(project){
 const panel=drawer(project,'Clip visibility','Hidden clips stay in the project and can be shown again here.');
 const endpoint='/api/admin/projects/'+encodeURIComponent(project.id)+'/clips';
 const search=el('input');
 search.type='search';search.placeholder='Find a clip';search.setAttribute('aria-label','Find a clip');
 const notice=el('p','admin-drawer-feedback','Loading clips…');
 notice.setAttribute('role','status');
 const list=el('div','admin-activity-list');
 panel.append(search,notice,list);
 try{
   const clips=await api(endpoint,{cache:'no-store'});
   if(!panel.isConnected)return;
   notice.textContent='';
   const items=[];
   for(const clip of clips){
     const item=el('div','admin-activity-item');
     const info=el('div','admin-activity-desc');
     const state=el('small',null,clip.hidden?'Hidden':'Visible');
     info.append(el('strong',null,clip.filename),state);
     const toggle=action(clip.hidden?'Show':'Hide',async()=>{
       toggle.disabled=true;
       notice.textContent='';
       try{
         const updated=await api(endpoint+'/'+encodeURIComponent(clip.id)+'/visibility',{
           method:'PATCH',headers:{'Content-Type':'application/json'},
           body:JSON.stringify({hidden:!clip.hidden})
         });
         clip.hidden=updated.hidden;
         state.textContent=clip.hidden?'Hidden':'Visible';
         toggle.textContent=clip.hidden?'Show':'Hide';
         toggle.setAttribute('aria-label',toggle.textContent+' '+clip.filename);
         notice.textContent=clip.filename+(clip.hidden?' is hidden from review.':' is visible in review.');
       }catch(err){notice.textContent=err.message||'Could not update visibility'}
       finally{toggle.disabled=false}
     });
     toggle.setAttribute('aria-label',toggle.textContent+' '+clip.filename);
     item.append(info,toggle);
     items.push({item,name:clip.filename.toLowerCase()});
   }
   const render=()=>{
     const query=search.value.trim().toLowerCase();
     const matches=items.filter(({name})=>name.includes(query));
     list.replaceChildren(...matches.map(({item})=>item));
     if(!matches.length)list.append(el('p','hint',clips.length?'No matching clips.':'No uploaded clips yet.'));
   };
   search.addEventListener('input',render);
   render();
 }catch(err){notice.textContent=err.message||'Could not load clips'}
}
async function manage(projectId){
 const project=allProjects.find(p=>p.id===projectId);
 if(!project)return;
 const panel=drawer(project,'Manage project','Project options, transfers and archive.');
 const controls=el('div','admin-drawer-controls');
 const notice=el('p','admin-drawer-feedback');
 notice.setAttribute('role','status');
 const settings=action('Access & PIN',()=>projectReviewSettings(projectId));
 const rename=action('Rename',()=>renameProject(projectId));
 const archive=action(project.archived?'Restore project':'Archive project',async()=>{
   if(!confirm(project.archived?
     'Restore '+project.name+' and allow new uploads?':
     'Archive '+project.name+'? Existing clips and review links remain available. New uploads will be paused.'))return;
   archive.disabled=true;
   try{
     await api('/api/admin/projects/'+encodeURIComponent(projectId)+'/archive',{
        method:'PATCH',headers:{'Content-Type':'application/json'},
        body:JSON.stringify({archived:!project.archived})
     });
     // Keep the edited project visible after changing its archive state.
     if(statusFilter)statusFilter.value=project.archived?'active':'archived';
     await load();
     const updated=allProjects.find(p=>p.id===projectId);
     if(updated)await manage(projectId);
   }catch(err){notice.textContent=err.message||'Archive change failed';archive.disabled=false}
 });
 const remove=action('Delete project',()=>deleteProject(projectId),'secondary admin-manage-delete');
 controls.append(settings,action('Clip visibility',()=>manageClips(project)),rename,archive,remove);
 panel.append(controls,notice);
 const header=el('div','admin-activity-heading');
 header.append(el('h4',null,'Recent transfers'),action('Refresh history',()=>loadActivity(project,panel,history)));
 panel.append(header);
 const history=el('div','admin-activity');
 panel.append(history);
 await loadActivity(project,panel,history);
}
root.addEventListener('click',(event)=>{
  const shareButton=event.target.closest('button[data-share-id]');
  const manageButton=event.target.closest('button[data-manage-id]');
  if(shareButton){const p=allProjects.find(p=>p.id===shareButton.dataset.shareId);if(p)share(p).catch(console.error)}
  if(manageButton)manage(manageButton.dataset.manageId);
});
})();
