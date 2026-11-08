const {test}=require('node:test');
const assert=require('node:assert/strict');
const {readFileSync}=require('node:fs');
const {join}=require('node:path');
const vm=require('node:vm');

async function viewer(allowDownloads,validPin=true){
  const nodes=new Map();
  function node(id){
    if(!nodes.has(id)){
      const classes=new Set(id==='content'?['hidden']:[]);
      nodes.set(id,{value:'123456',textContent:'',innerHTML:'',classList:{add:n=>classes.add(n),remove:n=>classes.delete(n),contains:n=>classes.has(n)},addEventListener(){},before(){}});
    }
    return nodes.get(id);
  }
  let initialized=false;
  const window={OffsetUploadTime:{update(){}},initOffsetPlayer(clips,base,auth,downloads){initialized=true;assert.equal(downloads,allowDownloads)}};
  const document={getElementById(id){if(id==='selected-count'&&!allowDownloads)return null;return node(id)},querySelectorAll(){return []}};
  const response=(body,ok=true)=>({ok,json:async()=>body});
  const fetch=async(url)=>{
    if(url==='/api/site/settings')return response({});
    if(url.endsWith('/public-unlock'))return response({},false);
    if(url.endsWith('/unlock'))return validPin?response({token:'test-token',expires:9999999999}):response({detail:'Invalid PIN'},false);
    if(url.endsWith('/clips'))return response([]);
    if(url.endsWith('/settings'))return response({allow_downloads:allowDownloads});
    if(url.includes('/storage'))return response({used_bytes:52428800,clip_count:1});
    throw Error('Unexpected request: '+url);
  };
  const context=vm.createContext({window,document,fetch,location:{pathname:'/r/test',hash:'',search:''},URLSearchParams,sessionStorage:{getItem(){return null},setItem(){},removeItem(){}}});
  const html=readFileSync(join(__dirname,'../static/review.html'),'utf8');
  const script=[...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].at(-1)[1];
  vm.runInContext(script,context);
  await vm.runInContext('unlock()',context);
  return {node,initialized};
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
