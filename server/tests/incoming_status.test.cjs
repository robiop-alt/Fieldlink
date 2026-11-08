const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');

function setup(){
 let refresh,notice=null,next;
 const events={};
 const panel={hidden:true,innerHTML:'',querySelector(selector){return selector==='.offset-incoming-connection'?notice:null},querySelectorAll(){return []},replaceChildren(){this.innerHTML='';notice=null},append(el){notice=el}};
 const context=vm.createContext({
  token:'review-token',navigator:{onLine:true},location:{pathname:'/r/test'},AbortController,
  document:{hidden:false,getElementById:id=>id==='offset-incoming'?panel:{classList:{contains(){return false}}},querySelector(){return {currentTime:12,paused:false}},addEventListener(){},createElement(){return {setAttribute(){},hidden:false,textContent:''}}},
  window:{addEventListener(name,fn){events[name]=fn}},
  setTimeout(fn,delay){if(delay===400)refresh=fn;return 1},clearTimeout(){},setInterval(){},
  fetch:async()=>{if(next instanceof Error)throw next;return {ok:true,json:async()=>next}},
 });
 vm.runInContext(fs.readFileSync(path.join(__dirname,'../static/incoming-status.js'),'utf8'),context);
 return {panel,context,events,notice:()=>notice,async poll(data){next=data;await refresh()}};
}
const sample={clips:[
 {filename:'queued.mp4',status:'queued',size:100,received:0,percent:0},
 {filename:'active.mp4',status:'uploading',size:100,received:40,percent:40},
 {filename:'stalled.mp4',status:'reconnecting',size:100,received:25,percent:25,stalled_seconds:120},
 {filename:'processing.mp4',status:'processing',size:100,received:100,percent:100},
],summary:{received_bytes:165,total_bytes:400}};

test('confirmed progress distinguishes queued, active, stalled and processing clips',async()=>{
 const s=setup();await s.poll(sample);
 assert.equal(s.panel.hidden,false);
 for(const value of ['Queued · 0%','Uploading · 40%','Waiting to resume · 25%','Received · processing · 100%','No data received for 2m','checking the clip before playback'])assert.ok(s.panel.innerHTML.includes(value),value);
 assert.ok(s.panel.innerHTML.includes('aria-valuenow="40"'));
});

test('offline and failed polling preserve progress; unchanged successful response clears stale notice',async()=>{
 const s=setup();await s.poll(sample);const confirmed=s.panel.innerHTML;
 s.context.navigator.onLine=false;s.events.offline();
 assert.match(s.notice().textContent,/offline/);
 assert.equal(s.panel.innerHTML,confirmed);
 s.context.navigator.onLine=true;await s.poll(Error('network failed'));
 assert.match(s.notice().textContent,/last confirmed progress/);
 assert.equal(s.panel.innerHTML,confirmed);
 await s.poll(sample);
 assert.equal(s.notice().hidden,true);
});

test('empty queue hides panel and first-request failure does not fabricate progress',async()=>{
 const s=setup();await s.poll(Error('unavailable'));
 assert.equal(s.panel.hidden,false);
 assert.match(s.notice().textContent,/retrying automatically/);
 assert.ok(!s.panel.innerHTML.includes('progressbar'));
 await s.poll({clips:[],summary:{}});
 assert.equal(s.panel.hidden,true);
});

test('late response for an old review session is ignored',async()=>{
 const s=setup();let resolve;
 s.context.fetch=()=>new Promise(r=>{resolve=r});
 const loading=s.poll(sample);
 s.context.token='different-session';
 resolve({ok:true,json:async()=>sample});
 await loading;
 assert.equal(s.panel.innerHTML,'');
});
