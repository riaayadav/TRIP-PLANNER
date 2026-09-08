const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
test('landing entry leads to the existing login and protects dashboard routes', async () => {
  const v = view();
  await v.run('render()');
  assert.match(v.node('#app').innerHTML, /Less figuring/);
  assert.match(v.node('#app').innerHTML, /href="#login"/);
  v.run("state.authMode='register';location.hash='#login'");
  await v.run('render()');
  assert.match(v.node('#app').innerHTML, /Welcome back\./);
  assert.match(v.node('#app').innerHTML, /class="auth-layout"/);
  v.run("location.hash='#budget/1'");
  await v.run('render()');
  assert.match(v.node('#app').innerHTML, /data-form="login"/);
});
test('signed-in visitors can see the public overview without private records', async () => {
  const v = view();
  v.run("location.hash='#welcome';state.user={name:'Private name',role:'CLIENT'}");
  await v.run('render()');
  assert.match(v.node('#app').innerHTML, /Open dashboard/);
  assert.doesNotMatch(v.node('#app').innerHTML, /Private name/);
});
const path = require('node:path');
test('memory dates default inside the trip and adjust when the selected trip changes', () => {
  const v = view();
  v.run(
    "state.data={trips:[{id:1,status:'COMPLETED',start_date:'2025-01-01',end_date:'2025-01-03'},{id:2,status:'COMPLETED',start_date:'2025-06-01',end_date:'2025-06-05'}]};var form={elements:{trip_id:{value:'1'},memory_date:{value:'2026-09-07'}}};syncMemoryDate(form)",
  );
  assert.equal(v.run('form.elements.memory_date.value'), '2025-01-03');
  assert.equal(v.run('form.elements.memory_date.min'), '2025-01-01');
  assert.equal(v.run('form.elements.memory_date.max'), '2025-01-03');
  v.run("form.elements.memory_date.value='2025-01-02';syncMemoryDate(form)");
  assert.equal(v.run('form.elements.memory_date.value'), '2025-01-02');
  v.run("form.elements.trip_id.value='2';syncMemoryDate(form)");
  assert.equal(v.run('form.elements.memory_date.value'), '2025-06-05');
  assert.equal(v.run('form.elements.memory_date.min'), '2025-06-01');
});
function view(fetch) {
  const nodes = new Map();
  const node = (id) => {
    if (!nodes.has(id))
      nodes.set(id, {
        innerHTML: '',
        open: false,
        addEventListener() {},
        close() {
          this.open = false;
        },
        showModal() {
          this.open = true;
        },
      });
    return nodes.get(id);
  };
  const context = vm.createContext({
    console,
    Intl,
    URLSearchParams,
    Date,
    Set,
    Map,
    fetch,
    clearInterval() {},
    clearTimeout() {},
    setTimeout() {},
    location: { hash: '#home' },
    window: { addEventListener() {} },
    document: { querySelector: node, addEventListener() {} },
  });
  const source = fs
    .readFileSync(path.join(__dirname, '../../main/webapp/assets/app.js'), 'utf8')
    .replace(/start\(\);\s*$/, '');
  vm.runInContext(
    fs.readFileSync(path.join(__dirname, '../../main/webapp/assets/packages.js'), 'utf8'),
    context,
  );
  vm.runInContext(source, context);
  return { run: (code) => vm.runInContext(code, context), node };
}
test('existing hidden destination remains selected while editing', () => {
  const v = view();
  v.run(
    "state.data={destinations:[{id:2,name:'Other place'}],trips:[{id:10,destination:'Original place'}]}",
  );
  const html = v.run(
    "plan({id:10,destination_id:7,title:'Original trip',start_date:'2026-09-06',end_date:'2026-09-06',people:1,budget:0})",
  );
  assert.match(html, /value="7" selected/);
  assert.match(html, /Original place \(no longer published\)/);
  assert.match(html, /name="budget"[^>]*value="0"/);
});
test('packing editor keeps the selected item identity and contents', () => {
  const v = view();
  v.run(
    "state.trip={id:1};childForm('packing',{id:5,category:'Other',label:'Medicine & supplies'})",
  );
  const html = v.node('#modal').innerHTML;
  assert.match(html, /name="id" value="5"/);
  assert.match(html, /value="Other" selected/);
  assert.match(html, /Medicine &amp; supplies/);
});
test('expired sessions obtain a fresh CSRF token and close private dialogs', async () => {
  const requests = [];
  const v = view(async (url, options) => {
    requests.push({ url, options });
    if (url === 'api/data')
      return { ok: false, status: 401, json: async () => ({ error: 'Please sign in' }) };
    return { ok: true, status: 200, json: async () => ({ csrf: 'fresh-token', user: null }) };
  });
  v.node('#modal').open = true;
  v.run("state.user={name:'Previous user'};state.csrf='expired-token';state.compare.add(3)");
  await assert.rejects(v.run("api('data')"), /Please sign in/);
  assert.equal(v.run('state.csrf'), 'fresh-token');
  assert.equal(v.run('state.user'), null);
  assert.equal(v.run('state.compare.size'), 0);
  assert.equal(v.node('#modal').open, false);
  assert.deepEqual(
    requests.map((x) => x.url),
    ['api/data', 'api/session'],
  );
});

test('administrator shell contains operations tools instead of traveler navigation', () => {
  const v = view();
  v.run(
    "state.user={role:'ADMIN',name:'Operator'};location.hash='#operations';shell('<h1>Operations</h1>')",
  );
  const html = v.node('#app').innerHTML;
  assert.match(html, /Bookings &amp; payments|Bookings & payments/);
  assert.match(html, /admin-hotels/);
  assert.match(html, /admin-transport/);
  assert.doesNotMatch(html, /href="#packing"/);
  assert.doesNotMatch(html, /href="#wishlist"/);
});
test('administrator login route redirects to the operations workspace', async () => {
  const v = view();
  v.run("state.user={role:'ADMIN',name:'Operator'};location.hash='#home'");
  await v.run('render()');
  assert.equal(v.run('location.hash'), 'operations');
});

test('destination editor uses category text and photo upload while preserving an existing cover', () => {
  const v = view();
  v.run("destinationForm({id:7,category:'Wildlife',image:'api/destination-photo?id=7'})");
  const html = v.node('#modal').innerHTML;
  assert.match(html, /name="category" type="text"/);
  assert.match(html, /name="photo_file" type="file"/);
  assert.doesNotMatch(html, /<select[^>]*name="(?:category|image)"/);
  assert.match(html, /src="api\/destination-photo\?id=7"/);
  v.run(
    "state.data={destinations:[{category:'Wildlife'},{category:'Wellness'},{category:'Wildlife'}]}",
  );
  assert.equal(v.run('destinationCategories().join(",")'), 'Wellness,Wildlife');
});
test('new trips offer a blank itinerary without resetting an existing trip', () => {
  const v=view();
  v.run("state.data={destinations:[{id:1,name:'Manali'}],trips:[]}");
  assert.match(v.run('plan()'),/value="blank"/);
  assert.doesNotMatch(v.run("plan({id:1,destination_id:1,title:'My trip',start_date:'2026-09-08',end_date:'2026-09-09',people:1,budget:0})"),/name="itinerary_mode"/);
  v.run("state.trip={id:1,start_date:'2026-09-08',end_date:'2026-09-09'};childForm('itinerary',{id:2,day_number:2,time_slot:'14:15',title:'Museum',note:'Tickets'})");
  assert.match(v.node('#modal').innerHTML,/Edit activity/);
  assert.match(v.node('#modal').innerHTML,/value="14:15"/);
});
