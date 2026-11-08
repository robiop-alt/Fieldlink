const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {join} = require('node:path');
const vm = require('node:vm');

function setup() {
  const nodes = new Map();
  const created = [];
  function element() {
    const item = {children: [], style: {}, className: '', textContent: '',
      append(...items) { this.children.push(...items); },
      prepend(...items) { this.children.unshift(...items); },
      setAttribute() {}, addEventListener() {}, focus() {}, remove() {},
    };
    created.push(item);return item;
  }
  function node(id) {
    if (!nodes.has(id)) {const classes=new Set();const attrs={};nodes.set(id, {
      value: '', textContent: '', innerHTML: '', disabled: false,
      classList: {toggle(name,force) {if(force===undefined)force=!classes.has(name);force?classes.add(name):classes.delete(name)}, add(name) {classes.add(name)}, remove(name) {classes.delete(name)}, contains(name) {return classes.has(name)}},
      setAttribute(name,value) {attrs[name]=value}, getAttribute(name) {return attrs[name]}, removeAttribute() {}, addEventListener() {}, focus() {},
      prepend(...items) { this.children = [...items, ...(this.children || [])]; },
      replaceChildren() { this.innerHTML = ''; this.textContent = ''; },
    });}
    return nodes.get(id);
  }
  const context = vm.createContext({
    document: {addEventListener() {}, createElement: element, createTextNode: text => ({textContent: text}), querySelector: () => node('projects'), getElementById: node, querySelectorAll: () => [], body: {classList: {toggle() {}}}},
    AbortController, setTimeout, clearTimeout,
    window: {}, location: {origin: 'https://offset.test'},
    // Leave automatic page initialization pending; tests control API responses.
    fetch: () => new Promise(() => {}),
  });
  const html = readFileSync(join(__dirname, '../static/admin.html'), 'utf8');
  vm.runInContext(html.match(/<script>([\s\S]*?)<\/script>/)[1], context);
  return {context, node, created, run: code => vm.runInContext(code, context)};
}

test('project creation blocks repeated clicks while a request is pending', async () => {
  const {context, node, run} = setup();
  node('name').value = 'Drone unit';
  let finish, calls = 0;
  context.api = () => {calls++; return new Promise(resolve => { finish = resolve; });};
  const first = run('createProject(true)');
  await run('createProject(false)');
  assert.equal(calls, 1);
  assert.equal(node('create-with-pin').disabled, true);
  assert.equal(node('create-without-pin').disabled, true);
  // Signing out while creating must not reveal the returned project/PIN.
  run('setSignedIn(null)');
  finish({id: 'private-id', url_path: '/r/test', public_review: false, pin: '123456'});
  await first;
  assert.equal(node('created').innerHTML, '');
  assert.equal(node('create-with-pin').disabled, false);
  assert.equal(node('create-without-pin').disabled, false);
});

test('a delayed project response cannot repopulate data after sign out', async () => {
  const {context, node, run} = setup();
  let finish;
  context.api = () => new Promise(resolve => { finish = resolve; });
  node('created').innerHTML = 'Old PIN';
  const loading = run('load()');
  run('setSignedIn(null)');
  finish([{id: 'old-project'}]);
  await loading;
  assert.equal(run('allProjects.length'), 0);
  assert.equal(node('created').innerHTML, '');
  assert.equal(node('projects').innerHTML, '');
});

test('latest refresh wins when responses arrive out of order', async () => {
  const {context, run} = setup();
  const pending = [];
  context.api = () => new Promise(resolve => pending.push(resolve));
  context.renderProjects = () => {};
  const oldLoad = run('load()');
  const newLoad = run('load()');
  pending[1]([{id: 'latest'}]);
  await newLoad;
  pending[0]([{id: 'old'}]);
  await oldLoad;
  assert.equal(run('allProjects[0].id'), 'latest');
});

test('expired admin session clears private data and shows sign in feedback', async () => {
  const {context, node, run} = setup();
  node('created').innerHTML = 'Old PIN';
  context.fetch = async () => ({ok: false, status: 401, json: async () => ({detail: 'Unauthorized'})});
  await assert.rejects(run("api('/api/admin/projects')"));
  assert.equal(node('created').innerHTML, '');
  assert.match(node('login-error').textContent, /session has expired/);
});


test('Access and PIN clears loading after settings arrive and shows controls', async () => {
  const {context, created, run} = setup();
  run("allProjects=[{id:'p1',name:'Test project'}]");
  let finish;
  context.api = () => new Promise(resolve => {finish = resolve;});
  const loading = run("projectReviewSettings('p1')");
  const status = created.find(el => el.textContent === 'Loading settings…');
  assert.ok(status);
  finish({public_review:false, allow_downloads:true, pin:'123456', pin_days:14});
  await loading;
  assert.equal(status.textContent, '');
  assert.ok(created.some(el => el.textContent === '123456'));
  assert.ok(created.some(el => el.textContent === 'Save' && typeof el.onclick === 'function'));
});

test('Access and PIN replaces loading with a request error', async () => {
  const {context, created, run} = setup();
  run("allProjects=[{id:'p1',name:'Test project'}]");
  context.api = async () => {throw Error('Could not load settings');};
  await run("projectReviewSettings('p1')");
  assert.ok(created.some(el => el.textContent === 'Could not load settings'));
  assert.ok(!created.some(el => el.textContent === 'Loading settings…'));
});


test('Access and PIN shows a retry action after a timed-out request', async () => {
  const {context, created, run} = setup();
  run("allProjects=[{id:'p1',name:'Test project'}]");
  context.api = async () => {const error = Error('aborted');error.name='AbortError';throw error;};
  await run("projectReviewSettings('p1')");
  assert.ok(created.some(el => el.textContent === 'Loading timed out. Please try again.'));
  assert.ok(created.some(el => el.textContent === 'Try again' && typeof el.onclick === 'function'));
});


test('header sign in opens a compact form and signing in hides the public introduction', async () => {
  const {context,node,run}=setup();
  context.api=async()=>[];
  run('setSignedIn(null)');
  assert.equal(node('login-panel').classList.contains('hidden'),true);
  assert.equal(node('landing-info').classList.contains('hidden'),false);
  node('login-toggle').onclick();
  assert.equal(node('login-panel').classList.contains('hidden'),false);
  assert.equal(node('login-toggle').getAttribute('aria-expanded'),'true');
  run("setSignedIn('Robin')");
  assert.equal(node('login-panel').classList.contains('hidden'),true);
  assert.equal(node('landing-info').classList.contains('hidden'),true);
  assert.equal(node('account-panel').classList.contains('hidden'),false);
});
