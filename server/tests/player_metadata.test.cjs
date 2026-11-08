const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');

function setup(clips,compact=true){
  const timers=new Map();let timerId=0;
  class Element {
    constructor(tag){this.tag=tag;this.children=[];this.dataset={};this.style={};this.events={};this.className='';this.value='off';this.paused=true;const classes=new Set();this.classList={add(name){classes.add(name)},remove(name){classes.delete(name)},contains(name){return classes.has(name)},toggle(name,on){on?classes.add(name):classes.delete(name)}};}
    append(...children){this.children.push(...children)}
    replaceChildren(...children){this.children=children}
    setAttribute(){} removeAttribute(){} pause(){} load(){}
    play(){this.paused=false;return Promise.resolve()}
    addEventListener(name,fn){const previous=this.events[name];this.events[name]=previous?(...args)=>{previous(...args);fn(...args)}:fn}
    contains(){return false}
    removeEventListener(){}
    querySelectorAll(){return []}
    getBoundingClientRect(){return {width:640,height:360}}
    get options(){return this.children}
  }
  const host=new Element('host'),list=new Element('list');
  const document=new Element('document');
  document.getElementById=id=>id==='clips'?list:host;
  document.createElement=tag=>new Element(tag);
  document.body=new Element('body');
  const window=new Element('window');window.matchMedia=()=>({matches:compact});
  const context=vm.createContext({window,document,location:{pathname:'/r/test'},localStorage:{getItem(){return null}},navigator:{userAgent:'iPhone'},setTimeout(fn){timers.set(++timerId,fn);return timerId},clearTimeout(id){timers.delete(id)},requestAnimationFrame:fn=>fn()});
  vm.runInContext(fs.readFileSync(path.join(__dirname,'../static/offset-player.js'),'utf8'),context);
  window.initOffsetPlayer(clips,'/api/test','',false);
  const player=host.children[0];
  const fields=player.children.find(el=>el.className==='offset-player-metadata').children[0];
  return {fields,next:player.children.find(el=>el.className==='offset-player-controls').children[4],player,stage:player.children.find(el=>el.className==='offset-player-stage'),video:player.children.find(el=>el.className==='offset-player-stage').children[0],controls:player.children.find(el=>el.className==='offset-player-controls'),document,timers,flush(){const jobs=[...timers.values()];timers.clear();jobs.forEach(fn=>fn())}};
}

test('main player shows six camera fields and clears them for a clip without camera data',()=>{
  const {fields,next}=setup([
    {id:'a',filename:'A.mov',camera_metadata:{recording_format:'RAW',lens:'35mm',fps:25,iso:800,white_balance:5600,shutter:'1/50'}},
    {id:'b',filename:'B.mov',metadata:{fps:30,iso:400}},
  ]);
  assert.deepEqual(fields.children.map(el=>el.children[1].textContent),['RAW','35mm','25','800','5600','1/50']);
  next.events.click();
  assert.equal(fields.children.length,6);
  assert.ok(fields.children.every(el=>el.children[1].textContent===''&&el.dataset.filled==='false'));
});

test('camera readings are rendered as text',()=>{
  const {fields}=setup([{id:'a',filename:'A.mov',camera_metadata:{lens:'<img onerror=alert(1)>'}}]);
  assert.equal(fields.children[1].children[1].textContent,'<img onerror=alert(1)>');
  assert.equal(fields.children[1].children[1].children.length,0);
});

test('landscape fullscreen hides controls during playback and a tap reveals them',async()=>{
 const s=setup([{id:'a',filename:'A.mov'}]);s.video.paused=false;
 await s.controls.children[5].events.click();
 assert.equal(s.player.classList.contains('offset-player-theater'),true);
 assert.equal(s.timers.size,1);
 s.flush();assert.equal(s.player.classList.contains('offset-player-chrome-hidden'),true);
 s.stage.events.click();assert.equal(s.player.classList.contains('offset-player-chrome-hidden'),false);
 assert.equal(s.timers.size,1);
 s.video.paused=true;s.video.events.pause();
 assert.equal(s.timers.size,0);
 assert.equal(s.player.classList.contains('offset-player-chrome-hidden'),false);
});

test('scrubbing holds controls visible and exiting fullscreen clears the timer',async()=>{
 const s=setup([{id:'a',filename:'A.mov'}]);s.video.paused=false;
 await s.controls.children[5].events.click();
 s.controls.events.pointerdown();assert.equal(s.timers.size,0);
 s.document.events.pointerup();assert.equal(s.timers.size,1);
 await s.controls.children[5].events.click();
 assert.equal(s.timers.size,0);
 assert.equal(s.player.classList.contains('offset-player-theater'),false);
 assert.equal(s.player.classList.contains('offset-player-chrome-hidden'),false);
});

test('portrait fullscreen keeps controls visible',async()=>{
 const s=setup([{id:'a',filename:'A.mov'}],false);s.video.paused=false;
 await s.controls.children[5].events.click();
 assert.equal(s.timers.size,0);
 s.stage.events.click();
 assert.equal(s.player.classList.contains('offset-player-chrome-hidden'),false);
});
