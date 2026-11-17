/* Site-wide accent; previews stay on this page until an admin saves. */
(function(root){
  'use strict';
  const DEFAULT='#57b62f', CACHE='offset.siteAccent.v1';
  const valid=value=>typeof value==='string'&&value.length===7&&/^#[0-9a-f]{6}$/i.test(value);
  const rgb=hex=>[1,3,5].map(i=>parseInt(hex.slice(i,i+2),16));
  const hex=values=>'#'+values.map(v=>Math.round(v).toString(16).padStart(2,'0')).join('');
  const mix=(a,b,t)=>a.map((v,i)=>v+(b[i]-v)*t);
  function luminance(color){return rgb(color).map(v=>{v/=255;return v<=.04045?v/12.92:((v+.055)/1.055)**2.4}).reduce((sum,v,i)=>sum+v*[.2126,.7152,.0722][i],0)}
  function contrast(a,b){const x=luminance(a),y=luminance(b);return (Math.max(x,y)+.05)/(Math.min(x,y)+.05)}
  const ink=color=>contrast(color,'#000000')>=contrast(color,'#ffffff')?'#000000':'#ffffff';
  function readable(color){let result=color;for(let i=1;contrast(result,'#14181c')<4.5&&i<=100;i++)result=hex(mix(rgb(color),[255,255,255],i/100));return result}
  function palette(color){
    const c=rgb(color),hover=hex(mix(c,[255,255,255],.12));
    return {'--accent':color,'--offset-accent':color,'--accent-text':readable(color),
      '--accent-ink':ink(color),'--accent-hover':hover,'--accent-hover-ink':ink(hover),
      '--accent-soft':hex(mix([11,13,15],c,.16)),
      '--accent-soft-hover':hex(mix([11,13,15],c,.25)),
      '--accent-border':hex(mix([41,48,55],c,.60)),
      '--accent-ring':`rgba(${c.join(',')},.24)`};
  }
  let saved=DEFAULT,draft=DEFAULT,dirty=false,loaded=false,busy=false,isAdmin=false,loadVersion=0,saveVersion=0,editor=null;
  function apply(color){if(!valid(color))return;const p=palette(color);Object.keys(p).forEach(key=>document.documentElement.style.setProperty(key,p[key]));}
  function cache(color){try{localStorage.setItem(CACHE,color)}catch(_){}}
  try{const cached=localStorage.getItem(CACHE);if(valid(cached))saved=draft=cached.toLowerCase()}catch(_){}
  apply(saved);
  function status(message,error=false){if(!editor)return;editor.status.textContent=message;editor.status.classList.toggle('appearance-error',error)}
  function sync(){
    if(!editor)return;
    editor.color.value=draft;editor.hex.value=draft;editor.hex.removeAttribute('aria-invalid');
    editor.presets.forEach(b=>b.setAttribute('aria-pressed',String(b.dataset.accent===draft)));
    editor.save.disabled=!isAdmin||!loaded||!dirty||busy;
    editor.cancel.disabled=!dirty||busy;editor.reset.disabled=!isAdmin||!loaded||busy;
    editor.color.disabled=!isAdmin||!loaded||busy;editor.hex.disabled=!isAdmin||!loaded||busy;
    editor.presets.forEach(b=>b.disabled=!isAdmin||!loaded||busy);
  }
  function preview(color){
    if(!isAdmin||!loaded||busy||!valid(color))return;
    draft=color.toLowerCase();dirty=draft!==saved;apply(draft);sync();
    status(dirty?'Preview only — not saved. Save to publish across the website.':'Matches the saved website appearance.');
  }
  function cancel(){draft=saved;dirty=false;apply(saved);sync();status('Preview cancelled. Saved website appearance restored.');}
  function setAdmin(value){isAdmin=!!value;if(!isAdmin){saveVersion++;busy=false;cancel()}sync()}
  async function load(){
    const version=++loadVersion;
    try{
      const response=await fetch('/api/site/appearance',{credentials:'same-origin',cache:'no-store'});
      if(!response.ok)throw Error('Could not load appearance.');
      const info=await response.json();
      if(!valid(info.accent_color))throw Error('Invalid appearance response.');
      if(version!==loadVersion)return;
      saved=info.accent_color.toLowerCase();loaded=true;cache(saved);
      if(!dirty){draft=saved;apply(saved)}
      if(editor){editor.retry.hidden=true;sync();if(!dirty)status('Saved website appearance. Changes below are previewed before publishing.');}
    }catch(_){if(version!==loadVersion)return;if(editor){editor.retry.hidden=false;sync();status('Could not load website appearance. Retry before making changes.',true)}}
  }
  async function save(){
    if(!isAdmin||!loaded||!dirty||busy||!valid(draft)||(editor&&!valid(editor.hex.value.trim())))return;
    const version=++saveVersion,color=draft;busy=true;sync();status('Saving appearance…');
    try{
      const response=await fetch('/api/admin/appearance',{method:'PUT',credentials:'same-origin',headers:{'Content-Type':'application/json'},body:JSON.stringify({accent_color:color})});
      if(version!==saveVersion||!isAdmin)return;
      if(!response.ok)throw Error(response.status===401||response.status===403?'Please sign in again before saving.':'Could not save appearance. Your preview has not been published.');
      const info=await response.json();
      if(version!==saveVersion||!isAdmin)return;
      if(!valid(info.accent_color))throw Error('Could not confirm saved appearance. Reload this page to check.');
      saved=draft=info.accent_color.toLowerCase();dirty=false;cache(saved);apply(saved);
      status('Appearance saved. Other pages use this color when opened or refreshed.');
    }catch(error){if(version===saveVersion&&isAdmin)status(error.message,true)}
    finally{if(version===saveVersion){busy=false;sync()}}
  }
  function initEditor(){
    const panel=document.getElementById('appearance-settings');if(!panel)return;
    const get=id=>document.getElementById('appearance-'+id);
    editor={color:get('color'),hex:get('hex'),save:get('save'),cancel:get('cancel'),reset:get('reset'),retry:get('retry'),status:get('status'),presets:Array.from(panel.querySelectorAll('[data-accent]'))};
    editor.presets.forEach(b=>b.addEventListener('click',()=>preview(b.dataset.accent)));
    editor.color.addEventListener('input',()=>preview(editor.color.value));
    editor.hex.addEventListener('input',()=>{
      const value=editor.hex.value.trim();
      if(valid(value))preview(value);
      else{editor.hex.setAttribute('aria-invalid','true');editor.save.disabled=true;editor.cancel.disabled=false;status('Enter a six-digit hex color, for example #9dd9ff.',true)}
    });
    editor.save.addEventListener('click',save);editor.cancel.addEventListener('click',cancel);
    editor.reset.addEventListener('click',()=>preview(DEFAULT));editor.retry.addEventListener('click',load);
    sync();if(loaded)status('Saved website appearance. Changes below are previewed before publishing.');
    else load();
  }
  root.OffsetAppearance={setAdmin,palette,contrast,preview,cancel};
  load();
  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',initEditor,{once:true});else initEditor();
  root.addEventListener('storage',event=>{if(event.key===CACHE&&valid(event.newValue)&&!dirty&&!busy){saved=draft=event.newValue.toLowerCase();apply(saved);sync()}});
})(window);
