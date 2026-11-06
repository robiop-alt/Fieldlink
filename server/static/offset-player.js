/* OFFSET Player: one active HTML5 video decoder, fast poster-only clip grid. */
(() => {
  let destroy = null;
  const elapsed = n => Number.isFinite(n) ? `${Math.floor(n/60).toString().padStart(2,'0')}:${Math.floor(n%60).toString().padStart(2,'0')}` : '--:--';

  window.initOffsetPlayer = function (clips, base, auth) {
    if (destroy) destroy();
    const list = document.getElementById('clips');
    const host = document.getElementById('review-player-host');
    if (!list || !host) return;
    host.replaceChildren();
    if (!clips.length) return;

    const videoUrl = clip => `${base}/clips/${encodeURIComponent(clip.id)}/file?${auth}`;
    const posterUrl = clip => `${base}/clips/${encodeURIComponent(clip.id)}/thumbnail?${auth}`;
    const make = (tag, className, label) => {
      const el = document.createElement(tag);
      if (className) el.className=className;
      if (label != null) el.textContent=label;
      return el;
    };
    const playSvg='<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="M8.5 5.75 18 12l-9.5 6.25V5.75Z" fill="currentColor"/></svg>';
    const pauseSvg='<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="M8 6.5v11M16 6.5v11" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"/></svg>';
    const button = (label, title) => {
      const el = make('button','offset-player-btn',label);
      el.type='button';
      if (title) el.title=title;
      return el;
    };

    const player = make('section','offset-player');
    player.setAttribute('aria-label','OFFSET video player');
    const head = make('div','offset-player-head');
    const title = make('div','offset-player-title');
    const counter = make('span','offset-player-counter');
    head.append(title,counter);

    const stage = make('div','offset-player-stage');
    const video = make('video','offset-featured-video');
    video.playsInline = true;
    video.setAttribute('playsinline','');
    video.setAttribute('webkit-playsinline','');
    video.preload = 'none';
    // Native video decoding remains in Safari/Chrome; controls are custom.
    video.controls = false;
    const frames = make('div','offset-player-frames');
    frames.hidden=true;
    frames.setAttribute('aria-hidden','true');
    stage.append(video,frames);

    const error = make('div','offset-player-error');
    error.hidden=true;
    const errorText = make('span',null,'Playback could not start in this browser. ');
    const direct = make('a',null,'Open video directly');
    direct.target='_blank';direct.rel='noopener';
    error.append(errorText,direct);

    const controls=make('div','offset-player-controls');
    const previous=button('‹','Previous clip');
    const toggle=button('','Play / pause');
    toggle.classList.add('offset-play-toggle');
    toggle.innerHTML=playSvg;
    const next=button('›','Next clip');
    const clock=make('span','offset-player-clock','00:00 / --:--');
    const seek=make('input','offset-player-seek');
    seek.type='range';seek.min='0';seek.max='1000';seek.value='0';seek.step='1';
    seek.setAttribute('aria-label','Playback position');
    const fullscreen=button('⛶','Fullscreen');
    controls.append(previous,toggle,clock,seek,next,fullscreen);

    const options=make('div','offset-player-options');
    const frameLabel=make('label',null,'Frame lines');
    const frameSelect=make('select','offset-player-frames-select');
    [['off','Off'],['0.5625','9:16'],['0.8','4:5'],['1','1:1'],['1.333333','4:3'],['1.777778','16:9'],['1.85','1.85:1'],['2.00','2.00:1'],['2.20','2.20:1'],['2.39','2.39:1']].forEach(([value,text])=>{
      const opt=make('option',null,text);opt.value=value;frameSelect.append(opt);
    });
    // Per-project setting in this browser, never attached to the media file.
    const frameStorageKey='offset:framelines:'+encodeURIComponent(location.pathname.split('/').filter(Boolean).pop()||'');
    try {
      const saved=localStorage.getItem(frameStorageKey);
      if(saved && [...frameSelect.options].some(option=>option.value===saved)) frameSelect.value=saved;
    } catch (_) { /* Private browsing can disable storage. */ }
    frameLabel.append(frameSelect);
    const download=make('a','offset-player-download','Download clip ↓');
    options.append(frameLabel,download);
    player.append(head,stage,controls,options,error);
    host.append(player);

    let index=0, loading=false;
    const cards=[...list.querySelectorAll('.clip')];
    const listeners=[];
    const listen=(el,type,fn) => {el.addEventListener(type,fn);listeners.push([el,type,fn]);};
    const duration = () => Number.isFinite(video.duration) && video.duration>0 ? video.duration : NaN;
    const updateTime=()=>{
      const total=duration();
      clock.textContent=`${elapsed(video.currentTime||0)} / ${elapsed(total)}`;
      seek.value=Number.isFinite(total)?String(Math.round((video.currentTime||0)/total*1000)):'0';
      seek.disabled=!Number.isFinite(total);
    };
    const updateButtons=()=>{
      toggle.innerHTML=video.paused?playSvg:pauseSvg;
      toggle.setAttribute('aria-label',video.paused?'Play video':'Pause video');
      previous.disabled=index===0;
      next.disabled=index===clips.length-1;
    };
    const updateFrames=()=>{
      if(frameSelect.value==='off'){frames.hidden=true;return}
      const ratio=Number(frameSelect.value);
      const bounds=stage.getBoundingClientRect();
      if(!bounds.width||!bounds.height)return;
      const native=(video.videoWidth&&video.videoHeight)?video.videoWidth/video.videoHeight:16/9;
      const vw=Math.min(bounds.width,bounds.height*native);
      const vh=vw/native;
      const w=ratio<native?vh*ratio:vw;
      const h=ratio>native?vw/ratio:vh;
      frames.style.width=`${100*w/bounds.width}%`;
      frames.style.height=`${100*h/bounds.height}%`;
      frames.hidden=false;
    };
    function choose(i, autoplay=false) {
      index=Math.min(Math.max(i,0),clips.length-1);
      const clip=clips[index];
      video.pause();
      video.removeAttribute('src');
      video.load();
      loading=false;
      video.poster=posterUrl(clip);
      title.textContent=clip.filename;
      counter.textContent=`${index+1} of ${clips.length}`;
      direct.href=videoUrl(clip);
      download.href=`${videoUrl(clip)}&download=1`;
      error.hidden=true;
      cards.forEach((card,n)=>{
        card.classList.toggle('offset-active-clip',n===index);
        const btn=card.querySelector('.offset-clip-preview');
        if(btn) btn.setAttribute('aria-pressed',String(n===index));
      });
      updateTime();updateButtons();updateFrames();
      if(autoplay) start();
    }
    function start() {
      if(!video.src) {
        video.src=videoUrl(clips[index]);
        video.preload='auto';
      }
      loading=true;
      const result=video.play();
      if(result&&typeof result.catch==='function')result.catch(()=>{
        loading=false;
        error.hidden=false;
        updateButtons();
      });
    }
    listen(toggle,'click',()=>{
      if(video.paused) start(); else video.pause();
    });
    listen(previous,'click',()=>choose(index-1,true));
    listen(next,'click',()=>choose(index+1,true));
    listen(seek,'input',()=>{
      const total=duration();
      if(Number.isFinite(total))video.currentTime=total*(Number(seek.value)/1000);
    });
    // Fullscreen the whole OFFSET player, never the <video> element itself:
    // native iOS video fullscreen hides HTML frame guides and custom controls.
    // On Safari versions without element fullscreen, use an inline viewport
    // takeover so the guides stay composited above the playing video.
    const isFullscreen=()=>document.fullscreenElement===player ||
      player.classList.contains('offset-player-theater');
    const updateFullscreenButton=()=>{
      const active=isFullscreen();
      fullscreen.textContent=active?'⤢':'⛶';
      fullscreen.title=active?'Exit fullscreen':'Fullscreen with frame lines';
      fullscreen.setAttribute('aria-label',fullscreen.title);
      requestAnimationFrame(updateFrames);
    };
    const exitTheater=()=>{
      player.classList.remove('offset-player-theater');
      document.body.classList.remove('offset-theater-open');
      updateFullscreenButton();
    };
    const enterTheater=()=>{
      player.classList.add('offset-player-theater');
      document.body.classList.add('offset-theater-open');
      updateFullscreenButton();
    };
    listen(fullscreen,'click',async()=>{
      if(player.classList.contains('offset-player-theater')){exitTheater();return}
      if(document.fullscreenElement){await document.exitFullscreen?.();return}
      // iOS can delegate video fullscreen to the native player, which
      // cannot display HTML overlays. Always use inline theater on iOS.
      const ios=/iPad|iPhone|iPod/.test(navigator.userAgent) ||
        (navigator.platform==='MacIntel' && navigator.maxTouchPoints>1);
      if(!ios && player.requestFullscreen){
        try{await player.requestFullscreen();updateFullscreenButton();return}
        catch(_){/* Element fullscreen unavailable: use inline theater. */ }
      }
      enterTheater();
    });
    listen(document,'fullscreenchange',updateFullscreenButton);
    listen(document,'webkitfullscreenchange',updateFullscreenButton);
    listen(document,'keydown',event=>{
      if(event.key==='Escape' && player.classList.contains('offset-player-theater'))exitTheater();
    });
    listen(frameSelect,'change',()=>{
      try {localStorage.setItem(frameStorageKey,frameSelect.value)} catch (_) {}
      updateFrames();
    });
    listen(video,'timeupdate',updateTime);
    listen(video,'loadedmetadata',()=>{updateTime();updateFrames()});
    listen(video,'play',()=>{error.hidden=true;updateButtons()});
    listen(video,'pause',updateButtons);
    listen(video,'ended',updateButtons);
    listen(video,'error',()=>{loading=false;error.hidden=false;updateButtons()});
    listen(video,'playing',()=>{loading=false;error.hidden=true});
    listen(window,'resize',updateFrames);
    listen(window,'orientationchange',()=>requestAnimationFrame(updateFrames));
    // Fullscreen changes available space without a window resize on some
    // browsers; observe the real video stage, not only the outer viewport.
    const frameObserver=typeof ResizeObserver!=='undefined'
      ? new ResizeObserver(()=>updateFrames()) : null;
    frameObserver?.observe(stage);
    listen(video,'webkitbeginfullscreen',()=>{
      // A browser-native video surface cannot contain HTML frame guides.
      // Return to inline playback whenever that native surface appears.
      try {video.webkitExitFullscreen?.()} catch(_){}
      if(!isFullscreen()) enterTheater();
    });

    cards.forEach((card,i)=>{
      const old=card.querySelector('.offset-video-stage');
      if(!old)return;
      const preview=button('','View '+clips[i].filename);
      preview.className='offset-clip-preview';
      preview.setAttribute('aria-pressed','false');
      const img=make('img','offset-clip-thumb');
      img.src=posterUrl(clips[i]);img.loading=i<6?'eager':'lazy';
      img.alt=`Preview of ${clips[i].filename}`;
      const playIcon=make('span','offset-clip-play-icon');
      playIcon.innerHTML=playSvg;
      preview.append(img,playIcon);
      old.replaceWith(preview);
      card.querySelector('.offset-view-controls')?.remove();
      card.querySelector('.playback-help')?.remove();
      listen(preview,'click',()=>{choose(i,true);player.scrollIntoView({behavior:'smooth',block:'start'})});
    });
    choose(0,false);
    destroy=()=>{
      frameObserver?.disconnect();
      if(player.classList.contains('offset-player-theater'))exitTheater();
      if(document.fullscreenElement===player)document.exitFullscreen?.().catch(()=>{});
      video.pause();video.removeAttribute('src');video.load();
      listeners.forEach(([el,name,fn])=>el.removeEventListener(name,fn));
      host.replaceChildren();
    };
  };
})();
