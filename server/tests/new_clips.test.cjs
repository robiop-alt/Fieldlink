const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const script=fs.readFileSync(path.join(__dirname,'../static/new-clips.js'),'utf8');
function storage(){const data=new Map();return {getItem:k=>data.get(k)||null,setItem:(k,v)=>data.set(k,v)}}
function create(local,session){const context=vm.createContext({window:{},localStorage:local,sessionStorage:session});vm.runInContext(script,context);return project=>context.window.OffsetNewClips.create(project)}
const clips=(...ids)=>ids.map(id=>({id}));
test('first visit has no new labels; a later visit marks only newly arrived clip IDs',()=>{
 const local=storage(),session=storage();
 const first=create(local,session)('project');
 assert.equal(first.update(clips('a')).size,0);
 const next=create(local,storage())('project');
 assert.deepEqual([...next.update(clips('a','b'))],['b']);
});
test('new set remains stable through refreshes and tracker recreation in the same visit',()=>{
 const local=storage();create(local,storage())('p').update(clips('a'));
 const session=storage(),visit=create(local,session)('p');
 assert.deepEqual([...visit.update(clips('a','b'))],['b']);
 assert.deepEqual([...visit.update(clips('a','b','c'))],['b','c']);
 assert.deepEqual([...create(local,session)('p').update(clips('a','b','c'))],['b','c']);
 assert.equal(create(local,storage())('p').update(clips('a','b','c')).size,0);
});
test('project baselines are isolated',()=>{
 const local=storage(),session=storage();
 create(local,session)('one').update(clips('a'));
 assert.equal(create(local,session)('two').update(clips('b')).size,0);
 assert.deepEqual([...create(local,storage())('one').update(clips('a','c'))],['c']);
});
test('expired visit baseline starts a fresh visit',()=>{
 const local=storage(),session=storage();create(local,session)('p').update(clips('a'));
 session.setItem('offset:visit-baseline:p',JSON.stringify({ids:[],first:false,touched:Date.now()-31*60*1000}));
 assert.deepEqual([...create(local,session)('p').update(clips('a','b'))],['b']);
});
test('unavailable or corrupt storage never blocks review',()=>{
 const bad={getItem(){throw Error('denied')},setItem(){throw Error('denied')}};
 assert.equal(create(bad,bad)('p').update(clips('a')).size,0);
 const corrupt=storage();corrupt.setItem('offset:last-visit:p','invalid json');
 assert.equal(create(corrupt,storage())('p').update(clips('a')).size,0);
});
