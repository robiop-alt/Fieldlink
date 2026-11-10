/* OFFSET Player: one active HTML5 video decoder, fast poster-only clip grid. */
(() => {
  let destroy = null;
  const elapsed = n => Number.isFinite(n) ? `${Math.floor(n/60).toString().padStart(2,'0')}:${Math.floor(n%60).toString().padStart(2,'0')}` : '--:--';

  window.initOffsetPlayer = function (clips, base, auth, allowDownloads=true) {
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
    const previous=button('','Previous clip');
    previous.classList.add('offset-clip-nav');
    previous.setAttribute('aria-label','Previous clip');
    previous.innerHTML='<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><rect x="4" y="5" width="2.5" height="14" rx="1" fill="currentColor"/><path d="M19 5 8 12l11 7V5Z" fill="currentColor"/></svg>';
    const toggle=button('','Play / pause');
    toggle.classList.add('offset-play-toggle');
    toggle.innerHTML=playSvg;
    const next=button('','Next clip');
    next.classList.add('offset-clip-nav');
    next.setAttribute('aria-label','Next clip');
    next.innerHTML='<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="m5 5 11 7-11 7V5Z" fill="currentColor"/><rect x="17.5" y="5" width="2.5" height="14" rx="1" fill="currentColor"/></svg>';
    const clock=make('span','offset-player-clock','00:00 / --:--');
    const seek=make('input','offset-player-seek');
    seek.type='range';seek.min='0';seek.max='1000';seek.value='0';seek.step='1';
    seek.setAttribute('aria-label','Playback position');
    const fullscreen=button('','Fullscreen');
    fullscreen.classList.add('offset-fullscreen-toggle');
    // Geometric enter/exit fullscreen glyphs: equal optical size to Play.
    const fullscreenEnterSvg='<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="M9 4H4v5M15 4h5v5M4 15v5h5M20 15v5h-5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>';
    const fullscreenExitSvg='<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="M4 9h5V4M20 9h-5V4M4 15h5v5M20 15h-5v5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>';
    fullscreen.innerHTML=fullscreenEnterSvg;
    const listeners=[];
    const listen=(el,type,fn) => {el.addEventListener(type,fn);listeners.push([el,type,fn]);};
    const frameMenu=make('details','offset-frame-menu');
    const frameTrigger=make('summary','offset-frame-trigger');
    frameTrigger.title='Frame lines';
    frameTrigger.setAttribute('aria-label','Frame lines');
    frameTrigger.innerHTML='<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="4" y="6" width="16" height="12" rx="2" fill="none" stroke="currentColor" stroke-width="1.7"/><path d="M4 10h16M4 14h16" stroke="currentColor" stroke-width="1.4"/></svg>';
    const framePanel=make('div','offset-frame-panel');
    framePanel.setAttribute('role','group');
    framePanel.setAttribute('aria-label','Frame line formats');
    const frameStorageKey='offset:framelines:'+encodeURIComponent(location.pathname.split('/').filter(Boolean).pop()||'');
    let frameValue='off';
    try { frameValue=localStorage.getItem(frameStorageKey)||'off' } catch (_) {}
    const formats=[['off','Off'],['2.39','2.39:1'],['2.20','2.20:1'],['2.00','2.00:1'],['1.85','1.85:1'],['1.777778','16:9'],['1.333333','4:3']];
    if(!formats.some(([value])=>value===frameValue))frameValue='off';
    frameTrigger.dataset.active=String(frameValue!=='off');
    for(const [value,title] of formats){
      const choice=make('label','offset-frame-choice');
      const radio=make('input');
      radio.type='radio';radio.name='offset-frame-format';radio.value=value;radio.checked=value===frameValue;
      listen(radio,'change',()=>{
        if(!radio.checked)return;
        frameValue=value;
        frameTrigger.dataset.active=String(value!=='off');
        try { localStorage.setItem(frameStorageKey,value) } catch (_) {}
        updateFrames();
        frameMenu.open=false;
        frameTrigger.focus();
        showChrome();
      });
      choice.append(radio,make('span',null,title));
      framePanel.append(choice);
    }
    const frameSettings=make('div','offset-frame-settings');
    function addFrameSetting(label, key, values, fallback, apply) {
      const field=make('label','offset-frame-setting',label);
      const select=make('select');
      const storageKey=frameStorageKey+':'+key;
      let value=fallback;
      try { value=localStorage.getItem(storageKey)||fallback } catch (_) {}
      for(const [optionValue,title] of values){
        const option=make('option',null,title);
        option.value=optionValue;
        select.append(option);
      }
      select.value=values.some(([optionValue])=>optionValue===value)?value:fallback;
      apply(select.value);
      listen(select,'change',()=>{
        apply(select.value);
        try { localStorage.setItem(storageKey,select.value) } catch (_) {}
      });
      field.append(select);
      frameSettings.append(field);
    }
    addFrameSetting('Shading','shade',
      [['0.25','25%'],['0.5','50%'],['0.75','75%'],['1','100%']],'0.75',
      value=>{frames.style.boxShadow='0 0 0 100vmax rgba(0,0,0,'+value+')'});
    addFrameSetting('Outline','outline',
      [['transparent','Off'],['white','White'],['#ff5353','Red'],['#78ca54','Green']],'transparent',
      value=>{frames.style.outlineColor=value});
    framePanel.append(frameSettings);
    frameMenu.append(frameTrigger,framePanel);
    controls.append(previous,toggle,clock,seek,next,frameMenu,fullscreen);
    const options=make('div','offset-player-options');
    const download=make('a','offset-player-download','Download clip ↓');
    if(allowDownloads) options.append(download);
    const metadata=make('div','offset-player-metadata');
    const cameraFields=make('div','offset-camera-fields');
    cameraFields.setAttribute('aria-label','Camera details for selected clip');
    metadata.append(cameraFields);
    const updateCameraDetails=clip=>{
      // Only DJI Pilot camera readings; proxy metadata must never fill these fields.
      const data=clip.camera_metadata&&typeof clip.camera_metadata==='object'?clip.camera_metadata:{};
      cameraFields.replaceChildren();
      for(const [label,key] of [['RAW / ProRes','recording_format'],['Lens','lens'],['FPS','fps'],['ISO','iso'],['WB','white_balance'],['Shutter','shutter']]){
        const value=data[key]===null||data[key]===undefined?'':String(data[key]).trim();
        const field=make('span','offset-camera-field offset-camera-'+key);
        field.dataset.filled=String(!!value);
        field.append(make('b',null,label),make('span',null,value));
        cameraFields.append(field);
      }
    };
    player.append(head,stage,controls,options,metadata,error);
    host.append(player);

    let index=0, loading=false;
    let cards=[...list.querySelectorAll('.clip')];
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
      if(frameValue==='off'){frames.hidden=true;return}
      const ratio=Number(frameValue);
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
      updateCameraDetails(clip);
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
    let chromeTimer=null,chromeInteracting=false;
    const compactFullscreen=()=>isFullscreen()&&!!window.matchMedia?.('(orientation: landscape) and (max-height: 650px)')?.matches;
    const stopChromeTimer=()=>{if(chromeTimer!==null){clearTimeout(chromeTimer);chromeTimer=null}};
    const showChrome=(autoHide=true)=>{
      stopChromeTimer();player.classList.remove('offset-player-chrome-hidden');
      if(autoHide&&compactFullscreen()&&!video.paused&&!video.ended&&!chromeInteracting&&!frameMenu.open&&!frameMenu.open){
        chromeTimer=setTimeout(()=>{
          chromeTimer=null;
          const focused=document.activeElement;
          if(compactFullscreen()&&!video.paused&&!chromeInteracting&&!frameMenu.open&&
             !(focused&&player.contains(focused)&&focused.matches?.(':focus-visible')))
            player.classList.add('offset-player-chrome-hidden');
        },2500);
      }
    };
    const updateFullscreenButton=()=>{
      const active=isFullscreen();
      fullscreen.innerHTML=active?fullscreenExitSvg:fullscreenEnterSvg;
      fullscreen.title=active?'Exit fullscreen':'Fullscreen with frame lines';
      fullscreen.setAttribute('aria-label',fullscreen.title);
      requestAnimationFrame(updateFrames);
      showChrome();
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
      if(event.key==='Escape'&&frameMenu.open){
        frameMenu.open=false;frameTrigger.focus();event.preventDefault();return;
      }
      if(isFullscreen())showChrome(false);
      if(event.key==='Escape' && player.classList.contains('offset-player-theater'))exitTheater();
    });
    listen(frameMenu,'toggle',()=>showChrome());
    listen(document,'click',event=>{
      if(frameMenu.open&&!frameMenu.contains(event.target))frameMenu.open=false;
    });
    listen(video,'timeupdate',updateTime);
    listen(video,'loadedmetadata',()=>{updateTime();updateFrames()});
    listen(video,'play',()=>{error.hidden=true;updateButtons()});
    listen(video,'pause',()=>{updateButtons();showChrome(false)});
    listen(video,'ended',()=>{updateButtons();showChrome(false)});
    listen(video,'error',()=>{loading=false;error.hidden=false;updateButtons();showChrome(false)});
    listen(video,'playing',()=>{loading=false;error.hidden=true;showChrome()});
    listen(stage,'click',()=>{
      if(!compactFullscreen())return;
      if(player.classList.contains('offset-player-chrome-hidden'))showChrome();
      else {stopChromeTimer();player.classList.add('offset-player-chrome-hidden')}
    });
    [controls,options,metadata].forEach(surface=>listen(surface,'pointerdown',()=>{
      chromeInteracting=true;showChrome(false);
    }));
    const finishChromeInteraction=()=>{if(chromeInteracting){chromeInteracting=false;showChrome()}};
    listen(document,'pointerup',finishChromeInteraction);
    listen(document,'pointercancel',finishChromeInteraction);
    listen(controls,'focusin',()=>showChrome(false));
    listen(controls,'focusout',()=>showChrome());
    listen(window,'resize',()=>{updateFrames();showChrome()});
    listen(window,'orientationchange',()=>{requestAnimationFrame(updateFrames);showChrome()});
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
      listen(preview,'click',()=>{const current=cards.indexOf(card);if(current>=0)choose(current,true);player.scrollIntoView({behavior:'smooth',block:'start'})});
    });
    choose(0,false);
    // Keep the active clip and playback position intact when the review
    // grid is re-sorted. Navigation and card highlighting follow the new order.
    window.offsetReorderPlayer = orderedIds => {
      if(!Array.isArray(orderedIds)||orderedIds.length!==clips.length)return;
      const clipById=new Map(clips.map(clip=>[String(clip.id),clip]));
      const cardById=new Map(cards.map((card,i)=>[String(clips[i].id),card]));
      const nextClips=orderedIds.map(id=>clipById.get(String(id)));
      const nextCards=orderedIds.map(id=>cardById.get(String(id)));
      if(nextClips.some(clip=>!clip)||nextCards.some(card=>!card))return;
      const activeId=String(clips[index]?.id||'');
      clips.splice(0,clips.length,...nextClips);
      cards=nextCards;
      index=Math.max(0,clips.findIndex(clip=>String(clip.id)===activeId));
      counter.textContent=`${index+1} of ${clips.length}`;
      cards.forEach((card,i)=>{
        card.classList.toggle('offset-active-clip',i===index);
        card.querySelector('.offset-clip-preview')?.setAttribute('aria-pressed',String(i===index));
      });
      updateButtons();
    };
    destroy=()=>{
      stopChromeTimer();
      delete window.offsetReorderPlayer;
      frameObserver?.disconnect();
      if(player.classList.contains('offset-player-theater'))exitTheater();
      if(document.fullscreenElement===player)document.exitFullscreen?.().catch(()=>{});
      video.pause();video.removeAttribute('src');video.load();
      listeners.forEach(([el,name,fn])=>el.removeEventListener(name,fn));
      host.replaceChildren();
    };
  };
})();
