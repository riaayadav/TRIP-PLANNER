const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
function view(fetch) {
  const nodes = new Map();
  const node = id => { if (!nodes.has(id)) nodes.set(id, {innerHTML:'',open:false,addEventListener(){},close(){this.open=false;},showModal(){this.open=true;}}); return nodes.get(id); };
  const context = vm.createContext({console,Intl,URLSearchParams,Date,Set,Map,fetch,clearInterval(){},clearTimeout(){},setTimeout(){},location:{hash:'#home'},window:{addEventListener(){}},document:{querySelector:node,addEventListener(){}}});
  const source = fs.readFileSync(path.join(__dirname,'../../main/webapp/assets/app.js'),'utf8').replace(/start\(\);\s*$/, '');
  vm.runInContext(source,context);
  return {run:code=>vm.runInContext(code,context),node};
}
test('existing hidden destination remains selected while editing', () => {
  const v=view();
  v.run("state.data={destinations:[{id:2,name:'Other place'}],trips:[{id:10,destination:'Original place'}]}");
  const html=v.run("plan({id:10,destination_id:7,title:'Original trip',start_date:'2026-09-06',end_date:'2026-09-06',people:1,budget:0})");
  assert.match(html, /value="7" selected/);assert.match(html,/Original place \(no longer published\)/);
  assert.match(html,/name="budget"[^>]*value="0"/);
});
test('packing editor keeps the selected item identity and contents', () => {
  const v=view();v.run("state.trip={id:1};childForm('packing',{id:5,category:'Other',label:'Medicine & supplies'})");
  const html=v.node('#modal').innerHTML;
  assert.match(html,/name="id" value="5"/);assert.match(html,/value="Other" selected/);assert.match(html,/Medicine &amp; supplies/);
});
test('expired sessions obtain a fresh CSRF token and close private dialogs', async () => {
  const requests=[];
  const v=view(async (url,options)=>{requests.push({url,options});if(url==='api/data')return {ok:false,status:401,json:async()=>({error:'Please sign in'})};return {ok:true,status:200,json:async()=>({csrf:'fresh-token',user:null})};});
  v.node('#modal').open=true;v.run("state.user={name:'Previous user'};state.csrf='expired-token';state.compare.add(3)");
  await assert.rejects(v.run("api('data')"),/Please sign in/);
  assert.equal(v.run('state.csrf'),'fresh-token');assert.equal(v.run('state.user'),null);assert.equal(v.run('state.compare.size'),0);assert.equal(v.node('#modal').open,false);
  assert.deepEqual(requests.map(x=>x.url),['api/data','api/session']);
});

