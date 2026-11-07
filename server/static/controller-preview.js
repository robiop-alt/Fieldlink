/* OFFSET browser-only controller design sandbox.
 * Deliberately no fetch, upload, device access, storage or external URLs.
 */
(() => {
  'use strict';
  const app=document.getElementById('app');
  const modal=document.getElementById('modalLayer');
  const frame=document.getElementById('deviceFrame');
  const screenSize=document.getElementById('deviceSize');
  const scenario=document.getElementById('demoScenario');
  const sampleFiles=[
    {name:'DJI_0001.MP4',size:'842 MB',bytes:842},
    {name:'DJI_0002.MP4',size:'1.2 GB',bytes:1229},
    {name:'DJI_0003.MP4',size:'526 MB',bytes:526},
    {name:'DJI_0004.MP4',size:'709 MB',bytes:709}
  ];
  const defaults={
    wifi_only:false,charging_only:false,error_alerts:true,
    sd_backup_status:true,startup_health_check:true,
    adaptive_speed:false,smart_recovery:true,
    low_data_mode:false,low_battery_protection:true,
    overlay:true,overlay_pilot_only:false,
    show_upload_status:true,start_on_boot:true
  };
  let settings={...defaults};
  let state={screen:'library',project:0,upload:'idle',progress:0,sdProgress:0,sdRunning:false,demoNotification:'',selectedFile:null,priorityClip:null,selectedMedia:[],demoExcluded:[],demoPriority:[]};
  const projects=['DEMO · Northern Light','DEMO · Mountain Unit','DEMO · Second Camera'];
  const escapeHTML=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;','&#39;':'&#39;'}[c]||'&#39;'));
  function button(label,action,kind='') {
    return '<button type="button" class="'+kind+'" data-action="'+action+'">'+label+'</button>';
  }
  const group=(title,inside,open=false)=>'<details class="settings-section"'+(open?' open':'')+'><summary>'+title+'</summary><div class="settings-content">'+inside+'</div></details>';
  const toggle=(key,title,desc)=>'<label class="switch-row"><span><strong>'+title+'</strong><p>'+desc+'</p></span><input type="checkbox" data-setting="'+key+'" '+(settings[key]?'checked':'')+' aria-label="'+title+'"></label>';
  const activeStatus=()=>state.upload==='uploading'?['Uploading demo clips','',state.progress]:
    state.upload==='waiting'?['No network · waiting to reconnect','waiting',state.progress]:
    state.upload==='error'?['Attention · folder access missing','error',state.progress]:
    ['Connected · monitoring for new clips','',0];
  const nav=(title)=>'<div class="app-head"><div class="app-brand"><span class="offset">OFFSET<span style="color:#b7f34d">.</span></span><span class="subtitle">'+title+'</span></div>'+button(state.screen==='home'?'Settings':'Back',state.screen==='home'?'settings':'home')+'</div>';
  function renderHome() {
    const [status,kind,pct]=activeStatus();
    const noProgress=state.upload==='waiting';
    return nav('INSPIRE 3  /  RUSHES DELIVERY')+
      '<div class="app-main">'+
        '<section class="card"><h2>ACTIVE PROJECT</h2><div class="app-project">'+projects[state.project]+'</div><div class="hint">DEMO-001 · Current project</div><div class="toolbar">'+
        button('Choose project','projects','primary')+button('Share / QR','qr')+'</div></section>'+
        '<section class="card"><h2>UPLOAD CONTROL</h2><div class="state-line"><span class="state-dot '+kind+'"></span><strong>'+status+'</strong></div>'+
        (state.upload==='uploading'||noProgress?'<div class="meter"><div class="meter-fill" style="width:'+pct+'%"></div></div><div class="status-meta"><span>'+pct+'% received</span><span>'+ (noProgress?'Last confirmed progress saved':'2 clips in queue')+'</span></div>':'<p class="small-stat">Automatic monitoring enabled · no new files yet</p>')+
        (state.upload==='error'?'<div class="notice">DEMO ERROR · Media folder permission requires attention.</div>':'')+
        '<div class="toolbar">'+button('Start upload','start','primary')+button('Stop upload','stop')+'</div>'+
        '<div class="toolbar">'+button('Media library','library')+button('Upload queue','queue')+'</div>'+
        '<div class="toolbar">'+button('Select cache folder','folder')+button('Add proxy files','proxy')+'</div>'+
        '<div class="toolbar">'+button('Copy rushes to SD card','sd')+'</div></section>'+
        '<div class="toolbar">'+button('More tools','tools')+button('Settings','settings')+'</div>'+
      '</div><div class="app-foot"><span>OFFSET / CONTROLLER</span><span>PREVIEW MODE · NO DEVICE</span></div>';
  }
  function renderSettings(){
    const upload=toggle('low_data_mode','Low Data Mode','New clips use smaller 480p proxies; originals remain untouched.')+
      toggle('wifi_only','Wi-Fi only','Wait for Wi-Fi before sending footage to the server.')+
      toggle('charging_only','Upload only while charging','Pause cloud uploads while running on battery.')+
      toggle('low_battery_protection','Low battery protection','Automatically pause below 20%; resume at 25%.')+
      toggle('error_alerts','Important error notifications','Only critical or repeated problems produce alerts.');
    const sd=toggle('sd_backup_status','Show SD backup status','Show copied count, errors and available space.')+
      '<div class="toolbar">'+button('Choose SD card','chooseSd')+button('SD backup status','sdStatus')+'</div>'+
      '<p class="hint">SD copying starts only when you press Copy rushes to SD card. Never in the background.</p>';
    const advanced=toggle('startup_health_check','Startup health check','Verify configuration when OFFSET opens.')+
      toggle('adaptive_speed','Adaptive upload speed','Conservative pacing when DJI Pilot is visible or controller is warm.')+
      toggle('smart_recovery','Smart recovery','Retry transient failures without restarting a verified transfer.')+
      toggle('start_on_boot','Automatic startup','Reuse saved project and continue monitoring after restart.')+
      toggle('overlay','Floating status pill','Show a compact transfer status over DJI Pilot.')+
      toggle('overlay_pilot_only','Pill only in DJI Pilot 2','Hide the overlay when other apps are in foreground.')+
      '<div class="toolbar">'+button('Check permissions','permissions')+button('Check for updates','updates')+'</div>';
    return nav('SAVED SETTINGS · DEMO ONLY')+
      '<div class="card"><h2>SETTINGS</h2><p class="hint">These switches change only the browser simulation, not your APK.</p>'+
      '<div class="settings-groups">'+group('UPLOAD',upload,true)+group('SD CARD',sd)+group('ADVANCED',advanced)+'</div></div>'+
      '<div class="app-foot"><span>Changes are temporary</span><span>NO LIVE CONFIGURATION</span></div>';
  }
  function renderProjects(){
    return nav('SELECT PROJECT · SIMULATED')+'<div class="card"><h2>PROJECTS</h2>'+
    projects.map((name,i)=>'<div class="library-row"><div class="library-meta"><strong>'+name+'</strong><div class="hint">DEMO-00'+(i+1)+'</div></div>'+
      '<button type="button" class="'+(i===state.project?'primary':'')+'" data-action="project:'+i+'">'+(i===state.project?'Selected':'Choose')+'</button></div>').join('')+
    '<div class="toolbar">'+button('Create project','createProject')+'</div></div>';
  }
  function renderQr(){
    return nav('SHARE · DEMONSTRATION')+'<div class="card"><h2>SHARE PROJECT</h2><div class="app-project">'+projects[state.project]+'</div>'+
    '<div class="qr-fake" role="img" aria-label="Decorative non-scannable QR demonstration"><span>DEMO ONLY</span></div>'+
    '<p class="qr-note">Illustration only · this is NOT a scannable project QR code.<br>Real project links and PINs are never loaded in the preview.</p>'+
    '<div class="toolbar">'+button('Copy demo link','copyDemo')+'</div></div>';
  }
  function renderLibrary(){
    const selected=state.selectedMedia.length;
    const [status,kind,pct]=activeStatus();
    const all=sampleFiles.length;
    const waiting=sampleFiles.filter((f,i)=>!state.demoExcluded.includes(i)).length;
    const cols=state.mediaLayout==='list'?'offset-media-list':'offset-media-grid';
    const mediaHeader='<div class="offset-media-top"><button data-action="projects" type="button" class="offset-project-picker">'+escapeHTML(projects[state.project])+'⌄</button>'+
      '<span class="offset-root-label">▱ Project root ⌄</span><span class="offset-top-spacer"></span>'+
      button('▦ QR','qr')+'<span class="offset-live-dot"></span>'+button('⚙ Settings','settings')+'</div>';
    const filters='<div class="offset-media-toolbar">'+
      button('All clips','filterAll')+
      '<span class="offset-status-pill">☁ '+all+' clips · '+(state.upload==='uploading'?'1 uploading · ':'')+waiting+' waiting · '+state.demoExcluded.length+' excluded</span>'+
      button('Upload '+waiting,'queue')+
      button('▦','gridView')+button('☷','listView')+button('⋯','tools')+'</div>';
    const batch='<div class="offset-media-batch"><span class="hint">'+selected+' selected</span>'+
      button('Queue','batchQueue')+button('Priority','batchPriority')+button('Clear ★','batchClearPriority')+button('Exclude','batchExclude')+
      button('Storage','mediaStorage')+'</div>';
    const cards=sampleFiles.map((f,i)=>{
      const excluded=state.demoExcluded.includes(i);
      const urgent=state.demoPriority.includes(i);
      const duration=['0:13','0:52','0:33','1:04'][i];
      const badge=excluded?'Excluded':urgent?'★ Priority':state.upload==='uploading'&&i===0?'Uploading':'Queued';
      return '<div class="offset-media-card">'+
        '<div class="offset-media-image" data-action="file:'+i+'" role="button" tabindex="0" aria-label="Preview '+f.name+'">'+
          '<div class="offset-media-art offset-media-art-'+i+'"></div>'+
          '<span class="offset-image-status">☁ '+badge+'</span><span class="offset-image-duration">'+duration+'</span></div>'+
        '<div class="offset-media-info"><label class="offset-media-name">'+
          '<input aria-label="Select '+f.name+'" class="demo-check" type="checkbox" data-demo-select="'+i+'" '+(state.selectedMedia.includes(i)?'checked':'')+'>'+
          '<strong>'+f.name+'</strong></label>'+
          '<span class="offset-media-sub">'+f.size+' · '+(urgent?'PRIORITY':excluded?'EXCLUDED':'Metadata arrives with upload')+'</span>'+
          '<div class="offset-media-foot"><span>7 Nov, 14:08</span>'+button(urgent?'Clear ★':'★ Priority','togglePriority:'+i)+'</div></div></div>';
    }).join('');
    return mediaHeader+filters+batch+
      '<div class="'+cols+'">'+cards+'</div>'+
      '<div class="offset-media-bottom"><span class="hint">'+status+'</span>'+
      button('Upload queue','queue')+button('SD backup','sd')+'</div>'+
      '<p class="offset-media-disclaimer">DEMO · Sample clips only. No device media accessed.</p>';
  }
  function renderQueue(){
    const rows=sampleFiles.slice(1,4).map((f,i)=>({file:f,index:i+1}));
    rows.sort((a,b)=>Number(state.demoPriority.includes(b.index))-Number(state.demoPriority.includes(a.index))||a.index-b.index);
    return nav('UPLOAD QUEUE · SAMPLE CLIPS')+
    '<div class="card"><h2>UPLOAD QUEUE</h2><p class="hint">Select Prioritize next for an urgent clip. The active upload finishes first, then the chosen clip goes next. Demo only.</p>'+
    rows.map(({file:f,index:i})=>'<div class="queue-row"><div><strong>'+(state.demoPriority.includes(i)?'★ ':'')+f.name+'</strong><span class="hint">'+f.size+' · '+(state.demoPriority.includes(i)?'PRIORITY · next': 'Waiting')+'</span></div>'+
    '<button type="button" data-action="prioritize:'+i+'">'+(state.demoPriority.includes(i)?'★ Next':'Prioritize')+'</button>'+
    (state.demoPriority.includes(i)?'<button type="button" data-action="togglePriority:'+i+'">Clear ★</button>':'')+
    '</div>').join('')+
    '<div class="toolbar">'+button('Simulate transfer','start','primary')+'</div></div>';
  }
  function renderTools(){
    return nav('MORE TOOLS · PREVIEW')+'<div class="card"><h2>MORE TOOLS</h2>'+
    '<div class="toolbar">'+button('Server history','history')+button('Local history','history')+'</div>'+
    '<div class="toolbar">'+button('Camera metadata','metadata')+button('Run diagnostics','diagnostics')+'</div>'+
    '<div class="toolbar">'+button('Check permissions','permissions')+button('Check for updates','updates')+'</div></div>';
  }
  function renderSd(){
    return nav('SD BACKUP · MANUAL ONLY')+'<div class="card"><h2>END-OF-DAY SD BACKUP</h2>'+
    '<p class="hint">Source: demo files from '+projects[state.project]+'. No files are read, written or copied.</p>'+
    '<div class="state-line"><span class="state-dot"></span><strong>'+(state.sdRunning?'Copying demo files':'Ready for manual copy')+'</strong></div>'+
    '<div class="meter"><div class="meter-fill" style="width:'+state.sdProgress+'%"></div></div>'+
    '<div class="status-meta"><span>'+state.sdProgress+'% simulated</span><span>SHA-256 verified in the real app</span></div>'+
    '<div class="toolbar">'+button(state.sdRunning?'Stop demo copy':'Start demo copy',state.sdRunning?'stopSd':'startSd',state.sdRunning?'danger':'primary')+'</div>'+
    '<p class="small-stat">A real SD backup only runs after you explicitly press Start. DJI originals remain untouched.</p></div>';
  }
  function render(){
    app.innerHTML=({
      home:renderHome,settings:renderSettings,projects:renderProjects,
      qr:renderQr,library:renderLibrary,queue:renderQueue,tools:renderTools,sd:renderSd
    }[state.screen]||renderHome)();
  }
  function showModal(title,message,action=null){
    modal.hidden=false;
    modal.innerHTML='<div class="dialog" role="dialog" aria-modal="true" aria-label="'+escapeHTML(title)+'"><h3>'+escapeHTML(title)+'</h3><p>'+escapeHTML(message)+'</p>'+
      '<div class="toolbar">'+(action?button(action.label,action.action,'primary'):'')+button('Close','closeModal')+'</div></div>';
  }
  function setScreen(v){state.screen=v;render();app.scrollIntoView({behavior:'smooth',block:'nearest'});}
  function simulateScenario(v) {
    state.upload=v;state.progress=v==='uploading'?42:v==='waiting'?62:v==='error'?18:0;
    if(state.screen!=='library')state.screen='library';
    render();
  }
  // Static simulated playback: no media requests, video decoding or device access.
  let playerTicker=null;
  const playerDurations=[13,52,33,64];
  function closePlayer(){
    if(playerTicker!==null){clearInterval(playerTicker);playerTicker=null;}
    modal.hidden=true;
    modal.innerHTML='';
  }
  function playerClock(seconds){
    const n=Math.max(0,Math.floor(seconds));
    return String(Math.floor(n/60)).padStart(2,'0')+':'+String(n%60).padStart(2,'0');
  }
  function renderPlayer(){
    if(state.playerIndex==null)return;
    const i=state.playerIndex, f=sampleFiles[i], duration=playerDurations[i];
    const seconds=Math.min(duration,state.playerSeconds||0);
    modal.hidden=false;
    modal.innerHTML='<div class="offset-player-backdrop" data-action="closePlayer"></div>'+
      '<div class="offset-demo-player" role="dialog" aria-modal="true" aria-label="OFFSET player demo">'+
      '<div class="offset-player-header"><strong>OFFSET · PLAYER</strong><span>'+escapeHTML(f.name)+'</span>'+
      button('✕ Close','closePlayer')+'</div>'+
      '<div class="offset-player-stage"><div class="offset-media-art offset-media-art-'+i+'"></div>'+
      '<span>DEMO FOOTAGE · NO VIDEO PLAYBACK</span></div>'+
      '<div class="offset-player-seek"><input type="range" min="0" max="'+duration+'" value="'+seconds+
      '" aria-label="Seek through demo clip" id="demoPlayerSeek"><span>'+playerClock(seconds)+' / '+playerClock(duration)+'</span></div>'+
      '<div class="offset-player-controls">'+button('◀ Previous','playerPrev')+
      button(state.playerPlaying?'❚❚ Pause':'▶ Play','playerToggle')+
      button('Next ▶','playerNext')+'</div>'+
      '<div class="offset-player-details">Clip '+(i+1)+' of '+sampleFiles.length+
      ' · 3840 × 2160 · '+f.size+' · Modified: 7 Nov 2026</div>'+
      '<p class="hint">Interactive playback simulation only. The APK uses local MP4 files.</p></div>';
  }
  function openPlayer(i){
    state.playerIndex=Math.max(0,Math.min(sampleFiles.length-1,i));
    state.playerSeconds=0;
    state.playerPlaying=false;
    renderPlayer();
    if(playerTicker!==null)clearInterval(playerTicker);
    playerTicker=setInterval(()=>{
      if(!state.playerPlaying || state.playerIndex==null || modal.hidden)return;
      state.playerSeconds=Math.min(playerDurations[state.playerIndex],state.playerSeconds+0.25);
      if(state.playerSeconds>=playerDurations[state.playerIndex])state.playerPlaying=false;
      const slider=document.getElementById('demoPlayerSeek');
      if(slider)slider.value=String(state.playerSeconds);
      const time=modal.querySelector('.offset-player-seek span');
      if(time)time.textContent=playerClock(state.playerSeconds)+' / '+playerClock(playerDurations[state.playerIndex]);
      const play=modal.querySelector('[data-action="playerToggle"]');
      if(play)play.textContent=state.playerPlaying?'❚❚ Pause':'▶ Play';
    },250);
  }
  function action(code){
    if(code==='closePlayer'){closePlayer();state.playerIndex=null;return}
    if(code==='playerToggle'){state.playerPlaying=!state.playerPlaying;renderPlayer();return}
    if(code==='playerPrev'||code==='playerNext'){
      const next=state.playerIndex+(code==='playerNext'?1:-1);
      if(next>=0&&next<sampleFiles.length){state.playerIndex=next;state.playerSeconds=0;state.playerPlaying=false;renderPlayer()}
      return;
    }
    if(code==='closeModal'){modal.hidden=true;modal.replaceChildren();return}
    if(code==='home'){setScreen('library');return}
    if(code==='gridView'){state.mediaLayout='grid';render();return}
    if(code==='listView'){state.mediaLayout='list';render();return}
    if(code==='filterAll'){state.selectedMedia=[];render();return}
    if(['settings','projects','qr','library','queue','tools','sd'].includes(code)){setScreen(code);return}
    if(code==='start'){simulateScenario('uploading');scenario.value='uploading';return}
    if(code==='stop'){simulateScenario('idle');scenario.value='idle';return}
    if(code==='startSd'){state.sdRunning=true;state.sdProgress=0;render();return}
    if(code==='stopSd'){state.sdRunning=false;render();return}
    if(code.startsWith('project:')){state.project=Number(code.split(':')[1])||0;setScreen('library');return}
    if(code.startsWith('file:')){
      const f=sampleFiles[Number(code.split(':')[1])];
      if(f)openPlayer(Number(code.split(':')[1]));
      return;
    }
    if(['batchQueue','batchPriority','batchClearPriority','batchExclude'].includes(code)){
      const picked=state.selectedMedia;
      if(!picked.length){showModal('Choose clips','Select one or more sample clips first.');return}
      if(code==='batchPriority')state.demoPriority=[...new Set([...state.demoPriority,...picked])];
      if(code==='batchClearPriority')state.demoPriority=state.demoPriority.filter(i=>!picked.includes(i));
      if(code==='batchExclude')state.demoExcluded=[...new Set([...state.demoExcluded,...picked])];
      state.selectedMedia=[];
      render();
      showModal('Batch action · demo only',picked.length+' selected clips · '+
        (code==='batchQueue'?'queued':code==='batchPriority'?'prioritized':code==='batchClearPriority'?'returned to normal priority':'excluded')+
        ' in the browser preview. No upload queue, production file or setting was changed.');
      return;
    }
    if(code==='mediaStorage'){
      showModal('Media storage · sample figures',
        'Visible DJI MP4s: 3.2 GB · OFFSET temporary chunks: 38 MB · Low-data copies: 510 MB · Internal free space: 23.8 GB. Clean old temp files is manual in the real APK. No data is deleted here.');
      return;
    }
    if(code.startsWith('prioritize:')){
      const i=Number(code.split(':')[1]);
      state.demoPriority=[i,...state.demoPriority.filter(x=>x!==i)];render();return;
    }
    if(code.startsWith('togglePriority:')){
      const i=Number(code.split(':')[1]);
      state.demoPriority=state.demoPriority.includes(i)
        ?state.demoPriority.filter(x=>x!==i):[i,...state.demoPriority];
      render();return;
    }
    if(code==='clearPriority'){state.demoPriority=[];state.priorityClip=null;render();return}
    const labels={
      folder:['Cache folder','A real DJI cache folder is only accessible from the Android APK.'],
      proxy:['Add proxy files','Android file picker is disabled in this sandbox preview.'],
      createProject:['Create project','Preview projects are fixed examples. Nothing is created on the server.'],
      chooseSd:['SD folder selection','No SD card is connected to the browser demo.'],
      sdStatus:['SD backup status','No real files copied. Use the manual SD demo to preview the progress UI.'],
      history:['History','Demo upload history: DJI_0001.MP4 finished; DJI_0002.MP4 queued.'],
      metadata:['Camera metadata','Metadata capture needs DJI and screen capture permissions.'],
      diagnostics:['Startup diagnostics','Demo: project configured ✓ · cache simulated ✓ · server simulated ✓'],
      permissions:['Permissions','Android permissions cannot be granted from the browser.'],
      updates:['App updates','No update check is performed in the preview.'],
      copyDemo:['Demo QR','The preview QR is intentionally not scannable and has no production destination.']
    };
    const [title,message]=labels[code]||['Preview','This action is disabled in demo mode.'];
    showModal(title,message);
  }
  app.addEventListener('click',event=>{
    const target=event.target.closest('[data-action]');
    if(target)action(target.dataset.action);
  });
  app.addEventListener('change',event=>{
    const target=event.target;
    if(target.matches('[data-demo-select]')){
      const i=Number(target.dataset.demoSelect);
      state.selectedMedia=target.checked
        ?[...new Set([...state.selectedMedia,i])]
        :state.selectedMedia.filter(x=>x!==i);
      render();
      return;
    }
    if(target.matches('[data-setting]')){
      settings[target.dataset.setting]=target.checked;
    }
  });
  modal.addEventListener('input',event=>{
    if(event.target.id==='demoPlayerSeek'){
      state.playerSeconds=Number(event.target.value);
      const time=modal.querySelector('.offset-player-seek span');
      if(time)time.textContent=playerClock(state.playerSeconds)+' / '+playerClock(playerDurations[state.playerIndex]);
    }
  });
  modal.addEventListener('click',event=>{
    const target=event.target.closest('[data-action]');
    if(target)action(target.dataset.action);
  });
  screenSize.addEventListener('change',()=>{
    frame.classList.toggle('mobile',screenSize.value==='mobile');
  });
  scenario.addEventListener('change',()=>simulateScenario(scenario.value));
  document.getElementById('openSettings').addEventListener('click',()=>setScreen('settings'));
  document.getElementById('resetDemo').addEventListener('click',()=>{
    closePlayer();settings={...defaults};
    state={screen:'library',project:0,upload:'idle',progress:0,sdProgress:0,sdRunning:false,demoNotification:'',selectedFile:null,priorityClip:null,selectedMedia:[],demoExcluded:[],demoPriority:[],playerIndex:null,playerSeconds:0,playerPlaying:false};
    scenario.value='idle';screenSize.value='tablet';frame.classList.remove('mobile');render();
  });
  setInterval(()=>{
    if(state.upload==='uploading' && state.progress<100){
      state.progress=Math.min(100,state.progress+4);
      if(state.progress===100){
        state.upload='idle';scenario.value='idle';
        showModal('Demo transfer completed','Sample clips are now marked complete. No data was uploaded.');
      }
      if(state.screen==='library')render();
    }
    if(state.sdRunning) {
      state.sdProgress=Math.min(100,state.sdProgress+6);
      if(state.sdProgress===100){state.sdRunning=false;showModal('Demo SD backup complete','Simulated copy finished. No files were written or read.');}
      if(state.screen==='sd')render();
    }
  },1600);
  render();
})();
