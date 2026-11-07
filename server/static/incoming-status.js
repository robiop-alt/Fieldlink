/* Compact, authenticated OFFSET incoming dashboard. No early video access. */
(() => {
  const panel=document.getElementById('offset-incoming');
  if(!panel) return;

  const esc=s=>String(s).replace(/[&<>"']/g,ch=>({
    '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'
  }[ch]));
  const fmt=n=>{
    const size=Math.max(0,Number(n)||0);
    if(size<1024) return size+' B';
    if(size<1024**2) return (size/1024).toFixed(0)+' KB';
    if(size<1024**3) return (size/1024**2).toFixed(1)+' MB';
    return (size/1024**3).toFixed(2)+' GB';
  };
  const number=n=>Math.max(0,Number(n)||0);
  const clipPercent=c=>Math.max(0,Math.min(100,Number(c.percent)||0));
  const statuses={
    queued:'Queued',starting:'Starting upload',uploading:'Uploading',
    reconnecting:'No recent progress',processing:'Verifying & processing'
  };
  let previous='',lastActive=new Set(),hadResponse=false;
  let showList=true, pending=false, lastSuccess=0;

  async function refresh(){
    if(!navigator.onLine||pending||document.hidden||document.getElementById('content')?.classList.contains('hidden'))return;
    if(typeof token!=='string'||!token)return;
    pending=true;
    try {
      const slug=encodeURIComponent(location.pathname.split('/').filter(Boolean).pop()||'');
      const response=await fetch('/api/review/'+slug+'/incoming',{
        headers:{Authorization:'Bearer '+token},cache:'no-store'
      });
      if(!response.ok)throw Error('Status unavailable');
      const data=await response.json();
      const clips=Array.isArray(data.clips)?data.clips:[];
      const counts=data.summary?.counts||{};
      const count=clips.length;
      const received=number(data.summary?.received_bytes ?? clips.reduce((n,c)=>n+number(c.received),0));
      const total=number(data.summary?.total_bytes ?? clips.reduce((n,c)=>n+number(c.size),0));
      const pct=total?Math.min(100,Math.round(received*100/total)):0;
      // A disappeared active transfer can mean delivery; only refresh the
      // playable list, whose own server endpoint confirms completed clips.
      const nowActive=new Set(clips.filter(c=>c.status==='uploading'||c.status==='processing').map(c=>c.filename));
      const finished=hadResponse&&[...lastActive].some(name=>!clips.some(c=>c.filename===name));
      lastActive=nowActive;hadResponse=true;lastSuccess=Date.now();
      if(finished&&typeof load==='function'){
        const video=document.querySelector('.offset-featured-video');
        if(!video||(!video.currentTime&&video.paused))load().catch(()=>{});
      }
      const serialized=JSON.stringify({clips,summary:data.summary});
      if(serialized===previous)return;
      previous=serialized;
      panel.hidden=!count;
      if(!count){panel.replaceChildren();return}

      const queued=number(counts.queued)+number(counts.starting);
      const transferring=number(counts.uploading);
      const stalled=number(counts.reconnecting);
      const processing=number(counts.processing);
      const summary=[queued?queued+' queued':'',transferring?transferring+' uploading':'',
        stalled?stalled+' waiting':'',processing?processing+' processing':''].filter(Boolean).join(' · ');
      panel.innerHTML=
        '<div class="offset-incoming-head"><strong>Incoming rushes</strong>'+
        '<span>'+count+' clips · '+esc(summary)+'</span></div>'+
        '<div class="offset-incoming-overview">'+
        '<div class="offset-incoming-amount"><strong>'+fmt(received)+' <small>/ '+fmt(total)+'</small></strong>'+
        '<span>'+pct+'% received</span></div>'+
        '<div class="offset-incoming-track offset-incoming-total" role="progressbar" aria-label="Bytes received" aria-valuemin="0" aria-valuemax="100" aria-valuenow="'+pct+'"><div style="width:'+pct+'%"></div></div>'+
        '<div class="offset-incoming-summary"><span>Awaiting upload / processing: '+count+'</span>'+
        '<button type="button" class="offset-incoming-toggle" aria-expanded="'+showList+'">'+
        (showList?'Hide details':'Show details')+'</button></div></div>'+
        '<div class="offset-incoming-grid" '+(showList?'':'hidden')+'>'+
        clips.map(c=>{
          const name=esc(c.filename),percent=clipPercent(c);
          const state=statuses[c.status]||'Queued';
          const status=c.status==='uploading'?state+' · '+percent+'%':
            c.status==='reconnecting'?state+' · will resume automatically':state;
          const thumbUrl='/api/review/'+slug+'/incoming-thumbnail?session='+
            encodeURIComponent(token)+'&filename='+encodeURIComponent(c.filename);
          const progress=c.status==='processing'?100:percent;
          const detail=c.status==='processing'?'Transfer complete · verifying storage':
            fmt(number(c.received))+' / '+fmt(number(c.size));
          return '<article class="offset-incoming-card" aria-label="'+name+' '+esc(status)+'">'+
            '<div class="offset-incoming-art"><img loading="lazy" alt="" src="'+esc(thumbUrl)+'"><span>PREVIEW</span></div>'+
            '<div class="offset-incoming-details"><strong title="'+name+'">'+name+'</strong>'+
            '<span class="offset-incoming-state">'+esc(status)+'</span>'+
            '<div class="offset-incoming-track"><div style="width:'+progress+'%"></div></div>'+
            '<span class="offset-incoming-size">'+esc(detail)+'</span></div></article>';
        }).join('')+'</div>';
      const toggle=panel.querySelector('.offset-incoming-toggle');
      toggle?.addEventListener('click',()=>{
        showList=!showList;
        const grid=panel.querySelector('.offset-incoming-grid');
        if(grid)grid.hidden=!showList;
        toggle.textContent=showList?'Hide details':'Show details';
        toggle.setAttribute('aria-expanded',String(showList));
      });
      panel.querySelectorAll('.offset-incoming-art img').forEach(img=>img.addEventListener('error',()=>{img.style.display='none'}));
    } catch(_) {
      // Never replace prior verified progress with made-up data during a disconnect.
      if(hadResponse&&!panel.hidden&&Date.now()-lastSuccess>15000){
        const header=panel.querySelector('.offset-incoming-head span');
        if(header)header.textContent='Status temporarily unavailable · reconnecting';
      }
    } finally {pending=false}
  }
  setTimeout(refresh,400);
  setInterval(refresh,6000);
  document.addEventListener('visibilitychange',()=>{if(!document.hidden)refresh()});
  window.addEventListener('online',refresh);
})();
