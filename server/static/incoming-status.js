/* Incoming rushes: authenticated, read-only, and separate from playable media. */
(() => {
  const panel=document.getElementById('offset-incoming');
  if(!panel)return;
  let previous='';
  let lastUploading=new Set();
  const escape=s=>String(s).replace(/[&<>"']/g,ch=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]));
  const formatBytes=n=>n>=1048576?(n/1048576).toFixed(1)+' MB':Math.round(n/1024)+' KB';
  async function refresh() {
    if(document.hidden || document.getElementById('content')?.classList.contains('hidden')) return;
    if(typeof token!=='string'||!token)return;
    try {
      const response=await fetch('/api/review/'+encodeURIComponent(location.pathname.split('/').pop())+'/incoming',{
        headers:{Authorization:'Bearer '+token},cache:'no-store'});
      if(!response.ok) return;
      const data=await response.json();
      const clips=Array.isArray(data.clips)?data.clips:[];
      const nowUploading=new Set(clips.filter(clip=>clip.status==='uploading').map(clip=>clip.filename));
      const completed=[...lastUploading].some(name=>!clips.some(clip=>clip.filename===name));
      lastUploading=nowUploading;
      if(completed && typeof load==='function') {
        const video=document.querySelector('.offset-featured-video');
        // Don't interrupt a clip the director is reviewing, even if paused.
        if(!video || (!video.currentTime && video.paused)) load().catch(()=>{});
      }
      const serialized=JSON.stringify(clips);
      if(serialized===previous)return;
      previous=serialized;
      panel.hidden=!clips.length;
      if(!clips.length){panel.replaceChildren();return}
      panel.innerHTML='<div class="offset-incoming-head"><strong>Incoming rushes</strong><span>'+clips.length+' on the way · Updates automatically</span></div>'+
        '<div class="offset-incoming-grid">'+clips.map(clip=>{
          const name=escape(clip.filename);
          const percent=Math.max(0,Math.min(100,Number(clip.percent)||0));
          const status=clip.status==='processing'?'Processing · nearly ready':
            clip.status==='reconnecting'?'Waiting for controller to reconnect':
            clip.status==='uploading'?'Uploading '+percent+'%':
            'Queued · waiting to upload';
          const thumbUrl='/api/review/'+encodeURIComponent(location.pathname.split('/').pop())+
            '/incoming-thumbnail?session='+encodeURIComponent(token)+'&filename='+encodeURIComponent(clip.filename);
          return '<article class="offset-incoming-card" aria-label="'+name+' '+status+'">'+
            '<div class="offset-incoming-art"><img loading="lazy" alt="" src="'+escape(thumbUrl)+'"><span>COMING SOON</span></div>'+
            '<div class="offset-incoming-details"><strong>'+name+'</strong>'+
            '<span>'+escape(status)+' · '+escape(formatBytes(clip.size||0))+'</span>'+
            '<div class="offset-incoming-track"><div style="width:'+percent+'%"></div></div></div></article>';
        }).join('')+'</div>';
      panel.querySelectorAll('.offset-incoming-art img').forEach(img=>img.addEventListener('error',()=>{img.style.display='none'}));
    }catch(_) { /* Offline: don't interrupt playback. */ }
  }
  setTimeout(refresh,600);
  setInterval(refresh,6000);
  document.addEventListener('visibilitychange',()=>{if(!document.hidden)refresh()});
})();
