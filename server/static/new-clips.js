/* Per-project visit baselines. Store clip IDs only, never media or access tokens. */
(()=>{
 const MAX_IDLE=30*60*1000;
 const read=(storage,key)=>{try{return JSON.parse(storage.getItem(key)||'null')}catch(_){return null}};
 const write=(storage,key,value)=>{try{storage.setItem(key,JSON.stringify(value))}catch(_){}};
 const valid=record=>record&&Array.isArray(record.ids)&&record.ids.every(id=>typeof id==='string');
 window.OffsetNewClips={create(project){
  let persistent=null,session=null;try{persistent=localStorage}catch(_){}try{session=sessionStorage}catch(_){}
  const key='offset:last-visit:'+project,sessionKey='offset:visit-baseline:'+project;
  const saved=read(session,sessionKey),previous=read(persistent,key),now=Date.now();
  const continuing=saved&&Array.isArray(saved.ids)&&saved.ids.every(id=>typeof id==='string')&&typeof saved.first==='boolean'&&Number.isFinite(saved.touched)&&now>=saved.touched&&now-saved.touched<MAX_IDLE;
  const first=continuing?saved.first:!valid(previous);
  const baseline=new Set(continuing?saved.ids:(valid(previous)?previous.ids:[]));
  return {update(clips){
   const ids=clips.map(clip=>String(clip.id)),fresh=new Set(first?[]:ids.filter(id=>!baseline.has(id)));
   write(persistent,key,{ids});
   write(session,sessionKey,{ids:[...baseline],first,touched:Date.now()});
   return fresh;
  }};
 }};
})();