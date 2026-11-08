const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');

function setup(clips){
  class Element {
    constructor(tag){this.tag=tag;this.children=[];this.dataset={};this.style={};this.events={};this.className='';this.value='off';this.paused=true;this.classList={add(){},remove(){},contains(){return false},toggle(){}};}
    append(...children){this.children.push(...children)}
    replaceChildren(...children){this.children=children}
    setAttribute(){} removeAttribute(){} pause(){} load(){}
    play(){return Promise.resolve()}
    addEventListener(name,fn){this.events[name]=fn}
    removeEventListener(){}
    querySelectorAll(){return []}
    getBoundingClientRect(){return {width:640,height:360}}
    get options(){return this.children}
  }
  const host=new Element('host'),list=new Element('list');
  const document=new Element('document');
  document.getElementById=id=>id==='clips'?list:host;
  document.createElement=tag=>new Element(tag);
  const window=new Element('window');
  const context=vm.createContext({window,document,location:{pathname:'/r/test'},localStorage:{getItem(){return null}},navigator:{},requestAnimationFrame:fn=>fn()});
  vm.runInContext(fs.readFileSync(path.join(__dirname,'../static/offset-player.js'),'utf8'),context);
  window.initOffsetPlayer(clips,'/api/test','',false);
  const player=host.children[0];
  const fields=player.children.find(el=>el.className==='offset-player-metadata').children[0];
  return {fields,next:player.children.find(el=>el.className==='offset-player-controls').children[4]};
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
