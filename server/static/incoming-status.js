/* Authenticated transfer status. Percentages are confirmed bytes, never estimates. */
(()=>{
 const panel=document.getElementById('offset-incoming');if(!panel)return;
 const esc=value=>String(value).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
 const num=value=>Math.max(0,Number(value)||0);
 const fmt=value=>{let n=num(value),i=0;const units=['B','KB','MB','GB','TB'];while(n>=1024&&i<4){n/=1024;i++}return n.toFixed(i?1:0)+' '+units[i]};
 const age=value=>num(value)<60?Math.max(1,Math.floor(num(value)))+'s':Math.floor(num(value)/60)+'m';
 const states={queued:'Queued',starting:'Starting upload',uploading:'Uploading',reconnecting:'Waiting to resume',processing:'Received · processing'};
 let pending=false,showList=true,previous='',hadResponse=false,lastActive=new Set();
 function connection(message=''){
  let notice=panel.querySelector('.offset-incoming-connection');
  if(!notice&&message&&!panel.hidden){
   notice=document.createElement('p');notice.className='offset-incoming-connection';notice.setAttribute('role','status');panel.append(notice);
  }
  if(notice){notice.textContent=message;notice.hidden=!message}
 }
 async function refresh(){
  if(!navigator.onLine){connection('You are offline · showing last confirmed progress');return}
  if(pending||document.hidden||document.getElementById('content')?.classList.contains('hidden'))return;
  if(typeof token!=='string'||!token)return;
  pending=true;
  const requestToken=token,controller=new AbortController(),timeout=setTimeout(()=>controller.abort(),10000);
  try{
   const slug=encodeURIComponent(location.pathname.split('/').filter(Boolean).pop()||'');
   const response=await fetch('/api/review/'+slug+'/incoming',{headers:{Authorization:'Bearer '+requestToken},cache:'no-store',signal:controller.signal});
   if(!response.ok)throw Error('Status unavailable');
   const data=await response.json();if(token!==requestToken)return;
   connection();
   const clips=Array.isArray(data.clips)?data.clips:[],count=clips.length;
   const received=num(data.summary?.received_bytes??clips.reduce((n,c)=>n+num(c.received),0));
   const total=num(data.summary?.total_bytes??clips.reduce((n,c)=>n+num(c.size),0));
   const pct=total?Math.min(100,Math.round(received*100/total)):0;
   const finished=hadResponse&&[...lastActive].some(name=>!clips.some(c=>c.filename===name));
   lastActive=new Set(clips.filter(c=>c.status==='uploading'||c.status==='processing'||c.status==='reconnecting').map(c=>c.filename));hadResponse=true;
   if(finished&&typeof load==='function'){
    const video=document.querySelector('.offset-featured-video');
    if(!video||(!video.currentTime&&video.paused))load().catch(()=>{});
   }
   const serialized=JSON.stringify({clips,summary:data.summary});
   if(serialized===previous)return;previous=serialized;
   panel.hidden=!count;if(!count){panel.replaceChildren();return}
   const counts=clips.reduce((result,c)=>{result[c.status]=(result[c.status]||0)+1;return result},{});
   const summary=[counts.uploading?counts.uploading+' uploading':'',counts.queued||counts.starting?((counts.queued||0)+(counts.starting||0))+' queued':'',counts.reconnecting?counts.reconnecting+' waiting to resume':'',counts.processing?counts.processing+' processing':''].filter(Boolean).join(' · ');
   panel.innerHTML='<div class="offset-incoming-head"><strong>Incoming rushes</strong><span>'+count+' clip'+(count===1?'':'s')+' · '+esc(summary)+'</span></div>'+
    '<div class="offset-incoming-overview"><div class="offset-incoming-amount"><strong>'+fmt(received)+' <small>/ '+fmt(total)+'</small></strong><span>'+pct+'% received</span></div>'+
    '<div class="offset-incoming-track offset-incoming-total" role="progressbar" aria-label="Total bytes received" aria-valuemin="0" aria-valuemax="100" aria-valuenow="'+pct+'"><div style="width:'+pct+'%"></div></div>'+
    '<div class="offset-incoming-summary"><span>Clips become playable after verification.</span><button type="button" class="offset-incoming-toggle" aria-expanded="'+showList+'">'+(showList?'Hide details':'Show details')+'</button></div></div>'+
    '<div class="offset-incoming-grid" '+(showList?'':'hidden')+'>'+clips.map(c=>{
     const name=esc(c.filename),stage=Object.hasOwn(states,c.status)?c.status:'queued';
     const percent=stage==='processing'?100:Math.min(100,num(c.percent));
     const status=states[stage]+' · '+percent+'%';
     const waiting=stage==='queued'?'Waiting for the controller to start':stage==='starting'?'Waiting for the first data':stage==='reconnecting'?'No data received for '+age(c.stalled_seconds)+' · waiting for transfer to resume':stage==='processing'?'All bytes received · checking the clip before playback':'Receiving data from the controller';
     const detail=stage==='processing'?'Transfer complete · verifying storage':fmt(c.received)+' / '+fmt(c.size);
     const thumb='/api/review/'+slug+'/incoming-thumbnail?session='+encodeURIComponent(requestToken)+'&filename='+encodeURIComponent(c.filename);
     return '<article class="offset-incoming-card state-'+stage+'" aria-label="'+name+' '+esc(status)+'"><div class="offset-incoming-art"><img loading="lazy" alt="" src="'+esc(thumb)+'"><span>PREVIEW</span></div>'+
      '<div class="offset-incoming-details"><strong title="'+name+'">'+name+'</strong><span class="offset-incoming-state">'+esc(status)+'</span>'+
      '<div class="offset-incoming-track" role="progressbar" aria-label="'+name+' transfer progress" aria-valuemin="0" aria-valuemax="100" aria-valuenow="'+percent+'"><div style="width:'+percent+'%"></div></div>'+
      '<span class="offset-incoming-size">'+esc(detail)+'</span><span class="offset-incoming-wait">'+esc(waiting)+'</span></div></article>';
    }).join('')+'</div>';
   const toggle=panel.querySelector('.offset-incoming-toggle');
   toggle?.addEventListener('click',()=>{
    showList=!showList;const grid=panel.querySelector('.offset-incoming-grid');if(grid)grid.hidden=!showList;
    toggle.textContent=showList?'Hide details':'Show details';toggle.setAttribute('aria-expanded',String(showList));
   });
   panel.querySelectorAll('.offset-incoming-art img').forEach(img=>img.addEventListener('error',()=>{img.style.display='none'}));
  }catch(_){
   if(token===requestToken){
    if(!hadResponse){panel.hidden=false;panel.innerHTML='<div class="offset-incoming-head"><strong>Incoming rushes</strong></div>'}
    connection(navigator.onLine?(hadResponse?'Live status unavailable · showing last confirmed progress':'Live status unavailable · retrying automatically'):'You are offline · showing last confirmed progress');
   }
  }finally{clearTimeout(timeout);pending=false}
 }
 setTimeout(refresh,400);setInterval(refresh,6000);
 document.addEventListener('visibilitychange',()=>{if(!document.hidden)refresh()});
 window.addEventListener('online',refresh);
 window.addEventListener('offline',()=>connection('You are offline · showing last confirmed progress'));
})();