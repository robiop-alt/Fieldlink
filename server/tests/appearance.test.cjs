const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
function setup(){
 const properties={},cache={},nodes=new Map(),requests=[];let init;
 function node(id){if(!nodes.has(id)){const listeners={};nodes.set(id,{value:'',disabled:false,hidden:false,textContent:'',dataset:{},attributes:{},classList:{toggle(){}},setAttribute(k,v){this.attributes[k]=v},removeAttribute(k){delete this.attributes[k]},addEventListener(k,v){listeners[k]=v},listeners,querySelectorAll(){return[]}})}return nodes.get(id)}
 const doc={readyState:'loading',documentElement:{style:{setProperty(k,v){properties[k]=v}}},getElementById:node,addEventListener(k,v){if(k==='DOMContentLoaded')init=v}};
 const context={document:doc,window:{addEventListener(){}},localStorage:{getItem:k=>cache[k],setItem:(k,v)=>cache[k]=v},fetch:(url,opts)=>new Promise(resolve=>requests.push({url,opts,resolve}))};
 vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../static/appearance.js'),'utf8'),context);
 return {api:context.window.OffsetAppearance,node,properties,cache,requests,init:()=>init(),async ready(){requests[0].resolve({ok:true,json:async()=>({accent_color:'#57b62f'})});await new Promise(r=>setImmediate(r));init();context.window.OffsetAppearance.setAdmin(true)}};
}
test('custom colors preserve readable button ink and highlighted text',()=>{
 const {api}=setup();for(const color of ['#000000','#ffffff','#9dd9ff','#57b62f','#c2c9ce','#331177','#777777']){
  const p=api.palette(color);assert.ok(api.contrast(color,p['--accent-ink'])>=4.5,color);
  assert.ok(api.contrast('#14181c',p['--accent-text'])>=4.5,color);
 }
});
test('preview is local; cancel and logout restore the saved theme',async()=>{
 const s=setup();await s.ready();s.api.preview('#9dd9ff');assert.equal(s.properties['--accent'],'#9dd9ff');
 assert.equal(s.requests.length,1);assert.equal(s.cache['offset.siteAccent.v1'],'#57b62f');
 s.api.cancel();assert.equal(s.properties['--accent'],'#57b62f');
 s.api.preview('#c2c9ce');s.api.setAdmin(false);assert.equal(s.properties['--accent'],'#57b62f');
 assert.equal(s.node('appearance-save').disabled,true);
});
test('failed save keeps preview and does not cache an unpublished color',async()=>{
 const s=setup();await s.ready();s.api.preview('#9dd9ff');const pending=s.node('appearance-save').listeners.click();
 assert.equal(s.requests[1].opts.credentials,'same-origin');s.requests[1].resolve({ok:false,status:500});await pending;
 assert.equal(s.cache['offset.siteAccent.v1'],'#57b62f');assert.equal(s.properties['--accent'],'#9dd9ff');
 assert.match(s.node('appearance-status').textContent,/not been published/);assert.equal(s.node('appearance-save').disabled,false);
});
test('successful save updates cache and reset requires another save',async()=>{
 const s=setup();await s.ready();s.api.preview('#9dd9ff');const pending=s.node('appearance-save').listeners.click();
 assert.equal(JSON.parse(s.requests[1].opts.body).accent_color,'#9dd9ff');
 s.requests[1].resolve({ok:true,json:async()=>({accent_color:'#9dd9ff'})});await pending;
 assert.equal(s.cache['offset.siteAccent.v1'],'#9dd9ff');assert.equal(s.node('appearance-save').disabled,true);
 s.node('appearance-reset').listeners.click();assert.equal(s.properties['--accent'],'#57b62f');
 assert.equal(s.cache['offset.siteAccent.v1'],'#9dd9ff');s.api.cancel();assert.equal(s.properties['--accent'],'#9dd9ff');
});
test('a late save response after logout cannot replace the saved theme',async()=>{
 const s=setup();await s.ready();s.api.preview('#9dd9ff');const pending=s.node('appearance-save').listeners.click();s.api.setAdmin(false);
 s.requests[1].resolve({ok:true,json:async()=>({accent_color:'#9dd9ff'})});await pending;
 assert.equal(s.properties['--accent'],'#57b62f');assert.equal(s.cache['offset.siteAccent.v1'],'#57b62f');
});
test('invalid input blocks save without changing the current preview',async()=>{
 const s=setup();await s.ready();s.api.preview('#9dd9ff');s.node('appearance-hex').value='invalid';s.node('appearance-hex').listeners.input();
 assert.equal(s.node('appearance-save').disabled,true);await s.node('appearance-save').listeners.click();assert.equal(s.requests.length,1);
 assert.equal(s.properties['--accent'],'#9dd9ff');s.node('appearance-cancel').listeners.click();assert.equal(s.node('appearance-hex').value,'#57b62f');
});
