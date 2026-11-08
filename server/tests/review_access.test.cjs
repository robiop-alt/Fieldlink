const {test}=require('node:test');
const assert=require('node:assert/strict');
const {readFileSync}=require('node:fs');
const {join}=require('node:path');
const vm=require('node:vm');

async function viewer(allowDownloads,validPin=true,clips=[]){
  const nodes=new Map();
  function node(id){
    if(!nodes.has(id)){
      const classes=new Set(id==='content'?['hidden']:[]);
      nodes.set(id,{value:'123456',textContent:'',innerHTML:'',classList:{add:n=>classes.add(n),remove:n=>classes.delete(n),contains:n=>classes.has(n)},addEventListener(){},before(){},setAttribute(){}});
    }
    return nodes.get(id);
  }
  const inputs=allowDownloads?clips.map(clip=>{
    const label={textContent:'Select'},classes=new Set();
    const card={classList:{toggle(name,enabled){enabled?classes.add(name):classes.delete(name)},contains(name){return classes.has(name)}},querySelector(){return label}};
    return {dataset:{select:clip.id},checked:false,closest(){return card},label,card};
  }):[];
  let initialized=false;
  const window={OffsetUploadTime:{update(){}},initOffsetPlayer(clips,base,auth,downloads){initialized=true;assert.equal(downloads,allowDownloads)}};
  const document={getElementById(id){if(id==='selected-count'&&!allowDownloads)return null;return node(id)},querySelectorAll(selector){return selector==='[data-select]'?inputs:[]}};
  const response=(body,ok=true)=>({ok,json:async()=>body});
  const fetch=async(url)=>{
    if(url==='/api/site/settings')return response({});
    if(url.endsWith('/public-unlock'))return response({},false);
    if(url.endsWith('/unlock'))return validPin?response({token:'test-token',expires:9999999999}):response({detail:'Invalid PIN'},false);
    if(url.endsWith('/clips'))return response(clips);
    if(url.endsWith('/settings'))return response({allow_downloads:allowDownloads});
    if(url.includes('/storage'))return response({used_bytes:52428800,clip_count:1});
    throw Error('Unexpected request: '+url);
  };
  const context=vm.createContext({window,document,fetch,location:{pathname:'/r/test',hash:'',search:''},URLSearchParams,sessionStorage:{getItem(){return null},setItem(){},removeItem(){}}});
  const html=readFileSync(join(__dirname,'../static/review.html'),'utf8');
  const script=[...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].at(-1)[1];
  vm.runInContext(script,context);
  await vm.runInContext('unlock()',context);
  return {node,initialized,inputs};
}

for(const allowDownloads of [false,true])test(`valid PIN opens review with downloads ${allowDownloads?'enabled':'disabled'}`,async()=>{
  const {node,initialized}=await viewer(allowDownloads);
  assert.equal(node('error').textContent,'');
  assert.equal(node('gate').classList.contains('hidden'),true);
  assert.equal(node('content').classList.contains('hidden'),false);
  assert.equal(initialized,true);
  assert.equal(node('storage-status').textContent,'Project media: 50 MB · 1 uploaded clip');
});

test('invalid PIN still shows the access gate',async()=>{
  const {node,initialized}=await viewer(false,false);
  assert.equal(node('error').textContent,'Invalid PIN');
  assert.equal(node('content').classList.contains('hidden'),true);
  assert.equal(initialized,false);
});

const selectionClips=[
 {id:'a',filename:'A.mov',size:50*1024**2},
 {id:'b',filename:'B.mov',size:25*1024**2}
];
test('selection updates total bytes, card labels and ZIP availability',async()=>{
 const {node,inputs}=await viewer(true,true,selectionClips);
 assert.equal(node('selected-count').textContent,'No clips selected');
 assert.equal(node('download-selected').disabled,true);
 inputs[0].checked=true;inputs[0].onchange();
 assert.equal(node('selected-count').textContent,'1 selected · 50 MB');
 assert.equal(inputs[0].label.textContent,'Selected');
 assert.equal(inputs[0].card.classList.contains('offset-clip-selected'),true);
 inputs[1].checked=true;inputs[1].onchange();
 assert.equal(node('selected-count').textContent,'2 selected · 75 MB');
 assert.equal(node('download-selected').disabled,false);
 assert.equal(node('select-all').textContent,'Clear selection');
 inputs[0].checked=false;inputs[0].onchange();
 assert.equal(node('selected-count').textContent,'1 selected · 25 MB');
 assert.equal(inputs[0].label.textContent,'Select');
});
test('select all and clear selection keep count and size in sync',async()=>{
 const {node,inputs}=await viewer(true,true,selectionClips);
 node('select-all').onclick();
 assert.equal(node('selected-count').textContent,'2 selected · 75 MB');
 assert.ok(inputs.every(input=>input.checked));
 node('select-all').onclick();
 assert.equal(node('selected-count').textContent,'No clips selected');
 assert.equal(node('download-selected').disabled,true);
 assert.ok(inputs.every(input=>!input.checked));
});
test('download-disabled projects do not render selection or download actions',async()=>{
 const {node,inputs}=await viewer(false,true,selectionClips);
 assert.equal(inputs.length,0);
 assert.ok(!node('clips').innerHTML.includes('class="clip-actions"'));
});
