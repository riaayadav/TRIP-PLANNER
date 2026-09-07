'use strict';
const managementNav=[['operations','Overview'],['admin-packages','Packages'],['admin-hotels','Hotels'],['admin-transport','Transportation'],['admin-bookings','Bookings & payments'],['admin','Users, destinations & reviews'],['profile','Account'],['logout','Sign out']];
function packageImg(img){
    if(!img)return 'assets/mountains.jpg';
    if(img.startsWith('api/')||img.startsWith('/')||img.startsWith('http')||img.startsWith('data:'))return img;
    return 'assets/'+img;
}
function managementShell(content){const current=route().split('?')[0];app.innerHTML=`<div class="shell management-shell"><aside class="sidebar"><button class="btn btn-light mobile-menu" data-action="menu">Close ×</button><a class="brand" href="#operations"><span class="brand-mark">↗</span>roam.</a><p class="management-label">ADMINISTRATION</p><nav aria-label="Administration">${managementNav.map(([r,l])=>`<a href="#${r}" class="${current===r?'active':''}" ${current===r?'aria-current="page"':''}>${l}</a>`).join('')}</nav><div class="sidebar-footer">Your travel operations workspace.<br>Payment mode: simulation</div></aside><div class="workspace"><header class="topbar"><button class="btn btn-light mobile-menu" data-action="menu" aria-label="Open navigation">☰</button><span class="eyebrow">Roam / Administration</span><span class="pill">Administrator · ${esc(state.user.name)}</span></header><main id="main" class="content" tabindex="-1">${content}</main></div></div>`;}
function packageCards(){const list=state.packages.packages;return heading('Find your next package.','Hotel stays, transportation and the details in between — curated by our team.',`<a href="#bookings" class="btn btn-primary">My bookings ↗</a>`)+`<p class="demo-banner">Demo booking & payment mode. No real reservations or money transfers are made.</p><div class="cards">${list.map(p=>`<article class="destination-card"><div class="card-image"><img loading="lazy" src="${packageImg(p.image)}" alt="Travel inspiration"><span class="season-pill">${esc(p.season)} · ${p.duration} days</span></div><div class="card-body"><h3>${esc(p.name)}</h3><p>${esc(p.destination)}</p><p>${esc(p.description)}</p><p>${esc(p.hotel)} · ${esc(p.mode)}</p><div class="card-bottom"><strong>${money(p.price)} / person</strong>${button('View package','package-view',p.id,'btn-primary btn-sm')}</div></div></article>`).join('')}</div>${list.length?'':empty('New adventures are on their way','The team has not published any available packages yet. You can still plan your own trip.','plan','Plan independently')}`;}
function packageDetails(p){return `<div class="package-cover mb-3"><img style="width:100%;height:220px;object-fit:cover;border-radius:12px;" src="${packageImg(p.image)}" alt="${esc(p.name)}"></div><p>${esc(p.description)}</p><div class="package-detail-grid"><section><h3>Stay · ${esc(p.hotel)}</h3><p>${esc(p.hotel_location)}</p><p>${esc(p.hotel_description)}</p><p>${esc(p.amenities)}</p></section><section><h3>Travel · ${esc(p.transport)}</h3><p>${esc(p.mode)} · ${esc(p.route_details)}</p><p>${esc(p.transport_description)}</p></section></div>${[['Inclusions',p.inclusions],['Exclusions',p.exclusions||'None specified'],['Day-by-day plan',p.itinerary],['Booking & cancellation terms',p.terms]].map(([l,t])=>`<section class="package-text"><h3>${l}</h3><p>${esc(t)}</p></section>`).join('')}`;}
function packageView(id){const p=state.packages.packages.find(p=>Number(p.id)===id);if(!p)return;openDialog(p.name,`<p class="pill">${p.duration} days · ${money(p.price)} per person · up to ${p.max_people} travelers</p>${packageDetails(p)}<p class="demo-banner">This creates a demo booking request. Availability requires administrator confirmation.</p><form data-form="package-book"><input type="hidden" name="package_id" value="${p.id}"><input type="hidden" name="version" value="${p.version}"><input type="hidden" name="request_key" value="${crypto.randomUUID()}">${input('start_date','Departure date',today(),'date',`required min="${today()}"`)}${input('people','Travelers',1,'number',`required min="1" max="${p.max_people}"`)}<p class="mt-3">Total: <strong id="booking-quote">${money(p.price)}</strong></p><label class="check-row"><input type="checkbox" name="accepted_terms" required> I have reviewed the package details and accept the terms shown above.</label>${submit('Request demo booking')}</form>`);}
function bookingRows(admin=false){const rows=state.packages.bookings;return `<div class="table-responsive"><table class="table"><thead><tr><th>Booking</th>${admin?'<th>Customer</th>':''}<th>Travel dates</th><th>Status</th><th>Total / paid</th><th>Payment</th><th>Details</th></tr></thead><tbody>${rows.map(b=>`<tr><td>#${b.id}<br>${esc(b.package_name)}<small class="d-block">${b.people} travelers</small></td>${admin?`<td>${esc(b.customer)}<small class="d-block">${esc(b.email)}</small></td>`:''}<td>${fmt(b.start_date)}<br>${fmt(b.end_date)}</td><td><span class="pill">${esc(b.status)}</span></td><td>${money(b.total)}<small class="d-block">Paid: ${money(b.paid)}</small></td><td>${esc(b.payment_status)}${b.status==='CANCELLED'&&Number(b.paid)>0?'<small class="d-block">Refund review needed</small>':''}</td><td>${button('Open','booking-view',b.id,'btn-light btn-sm')}</td></tr>`).join('')}</tbody></table></div>${rows.length?'':'<p class="muted">No bookings yet.</p>'}`;}
function clientBookings(){return heading('Your package bookings.','Keep your requests, package details and simulated payment history together.',`<a class="btn btn-primary" href="#packages">Explore packages</a>`)+`<p class="demo-banner">Payment statuses are simulated. No money is collected.</p><div class="panel">${bookingRows()}</div>`;}
function bookingView(id){const b=state.packages.bookings.find(b=>Number(b.id)===id);if(!b)return;const admin=state.user.role==='ADMIN';const p=JSON.parse(b.snapshot);openDialog('Booking #'+b.id,`<h3>${esc(b.package_name)}</h3><p>${fmt(b.start_date)} – ${fmt(b.end_date)} · ${b.people} travelers</p><p>Status: ${esc(b.status)} · Payment: ${esc(b.payment_status)}</p><div class="budget-total">Total ${money(b.total)} · Paid ${money(b.paid)}<p>${b.status==='CANCELLED'?'Paid amount to review for refund':'Outstanding'}: ${money(b.status==='CANCELLED'?b.paid:b.balance)}</p></div><p class="demo-banner">Demo ledger only. These entries do not move money.</p>${admin?`<form data-form="booking-status"><input type="hidden" name="id" value="${b.id}">${select('status','Booking status',[b.status,...(b.status==='REQUESTED'?['CONFIRMED','CANCELLED']:b.status==='CONFIRMED'?['COMPLETED','CANCELLED']:[])],b.status)}${submit('Update booking')}</form>${['REQUESTED','CONFIRMED','COMPLETED'].includes(b.status)||Number(b.paid)>0?`<form data-form="payment-entry" class="panel mt-3"><h3>Record a simulated payment</h3><input type="hidden" name="booking_id" value="${b.id}">${select('kind','Entry type',b.status==='CANCELLED'?['REFUND']:['RECEIPT','REFUND'])}${input('amount','Amount (INR)','','number','required min="0.01" max="99999999.99" step="0.01"')}${input('reference','Unique demo reference',crypto.randomUUID(),'text','required maxlength="150"')}${area('note','Reason / note','Demo entry. No money moved.',1000)}${submit('Save demo entry')}</form>`:''}`:`<div class="actions">${b.status==='REQUESTED'?button('Cancel request','booking-cancel',b.id):''}${b.status!=='CANCELLED'&&Number(b.balance)>0?button('Simulate full payment','demo-payment',b.id,'btn-primary'):''}</div>`}<h3 class="mt-4">Payment history</h3>${b.payments.map(p=>`<p>${esc(p.kind)} · ${money(p.amount)}<br><small>${esc(p.reference)} · ${esc(p.created_at)}</small><br>${esc(p.note)}</p>`).join('')||'<p>No payments recorded.</p>'}<details><summary>Package details at time of booking</summary>${packageDetails(p)}</details>`);}
function operationPage(page){const a=state.packages;if(page==='admin-bookings')return heading('Bookings & payments','Manage requests and review the simulated payment ledger.')+`<p class="demo-banner">Simulation only: no payment gateway is connected.</p><div class="panel">${bookingRows(true)}</div>`;
if(page==='operations'){const received=a.bookings.reduce((n,b)=>n+Number(b.paid),0);return heading('Operations overview','Your catalog, your customers, your travel business.',button('Create package','package-new','','btn-primary'))+`<div class="management-welcome"><span class="small-label">Roam control center</span><h2>Build the experience.<br>Let travelers enjoy the journey.</h2><p>Manage packages, accommodation, transport and booking requests from your dedicated admin workspace.</p></div><div class="stats"><div class="stat"><span>Published packages</span><strong>${a.packages.filter(p=>p.active).length}</strong></div><div class="stat"><span>Booking requests</span><strong>${a.bookings.filter(b=>b.status==='REQUESTED').length}</strong></div><div class="stat"><span>Simulated net payments</span><strong>${money(received)}</strong></div></div><div class="cards">${[['admin-packages','Package catalog','Create offers with clear prices and inclusions.'],['admin-hotels','Hotels & stays','Maintain accommodation details and amenities.'],['admin-transport','Transportation','Manage modes, routes and service details.']].map(([r,t,d])=>`<a class="panel text-decoration-none" href="#${r}"><h3>${t} ↗</h3><p>${d}</p></a>`).join('')}</div><div class="panel"><h2>Bookings</h2>${bookingRows(true)}</div>`;}
if(page==='admin-packages')return heading('Package catalog','Create, edit, publish or remove the packages travelers can request.',button('Create package','package-new','','btn-primary'))+`<p class="note">Deleting removes the package from sale. Existing bookings retain their original details and payment history.</p><div class="panel table-responsive"><table class="table"><thead><tr><th>Package</th><th>Hotel / transport</th><th>Price per person</th><th>Visibility</th><th>Actions</th></tr></thead><tbody>${a.packages.map(p=>`<tr><td><div class="d-flex align-items-center gap-2"><img class="package-thumb" width="48" height="36" src="${packageImg(p.image)}" alt="${esc(p.name)}"><div>${esc(p.name)}<small class="d-block">${esc(p.destination)} · ${p.duration} days</small></div></div></td><td>${esc(p.hotel)}<br>${esc(p.transport)}</td><td>${money(p.price)}</td><td>${p.active?'Published':'Draft'}</td><td>${button('Edit','package-edit',p.id,'btn-light btn-sm')} ${button('Delete','package-delete',p.id,'btn-light btn-sm')}</td></tr>`).join('')}</tbody></table>${a.packages.length?'':'<p>Add your first package with any destination, hotel and transportation.</p>'}</div>`;
const hotel=page==='admin-hotels',list=hotel?a.hotels:a.transport,type=hotel?'hotel':'transport';return heading(hotel?'Hotels & accommodation':'Transportation','Maintain the services included in your packages.',button(hotel?'Add hotel':'Add transportation',type+'-new','','btn-primary'))+`<div class="panel table-responsive"><table class="table"><thead><tr><th>Name</th><th>Details</th><th>Availability</th><th>Actions</th></tr></thead><tbody>${list.map(x=>`<tr><td>${esc(x.name)}</td><td>${esc(hotel?x.location:x.mode+' · '+x.route_details)}</td><td>${x.active?'Active':'Archived'}</td><td>${button('Edit',type+'-edit',x.id,'btn-light btn-sm')} ${x.active?button('Archive',type+'-delete',x.id,'btn-light btn-sm'):''}</td></tr>`).join('')}</tbody></table>${list.length?'':'<p>No entries yet.</p>'}</div>`;
}
function packageForm(p={}){
    const a=state.packages;
    const destVal=p.destination||(a.destinations.find(d=>d.id===p.destination_id)?.name)||'';
    const hotelVal=p.hotel||(a.hotels.find(h=>h.id===p.hotel_id)?.name)||'';
    const transportVal=p.transport||(a.transport.find(t=>t.id===p.transport_id)?.name)||'';
    const hasPhoto=Boolean(p.image);
    openDialog(p.id?'Edit package':'Create package',`<form data-form="package-save" class="form-grid">
        ${p.id?`<input type="hidden" name="id" value="${p.id}"><input type="hidden" name="version" value="${p.version}">`:''}
        ${input('name','Package name',p.name||'','text','required maxlength="150" placeholder="e.g. Scenic Mountain Getaway"')}
        <div>
            <label class="form-label" for="f-destination_name">Destination / City</label>
            <input class="form-control" id="f-destination_name" name="destination_name" list="destination-suggestions" placeholder="Type any city (e.g. Paris, Goa, Tokyo, Manali...)" value="${esc(destVal)}" required maxlength="120" autocomplete="off">
            <datalist id="destination-suggestions">
                ${a.destinations.map(d=>`<option value="${esc(d.name)}">`).join('')}
            </datalist>
        </div>
        <div>
            <label class="form-label" for="f-hotel_name">Hotel / Stay</label>
            <input class="form-control" id="f-hotel_name" name="hotel_name" list="hotel-suggestions" placeholder="Type any hotel (e.g. Grand Palace, Pine Valley Lodge...)" value="${esc(hotelVal)}" required maxlength="120" autocomplete="off">
            <datalist id="hotel-suggestions">
                ${a.hotels.map(h=>`<option value="${esc(h.name)}">`).join('')}
            </datalist>
        </div>
        <div>
            <label class="form-label" for="f-transport_name">Transportation</label>
            <input class="form-control" id="f-transport_name" name="transport_name" list="transport-suggestions" placeholder="Type any transportation (e.g. AC Cab, Express Train, Flight...)" value="${esc(transportVal)}" required maxlength="120" autocomplete="off">
            <datalist id="transport-suggestions">
                ${a.transport.map(t=>`<option value="${esc(t.name)}">`).join('')}
            </datalist>
        </div>
        ${input('price','Total package price per person (INR)',p.price??'','number','required min="0.01" max="99999999.99" step="0.01"')}
        ${input('duration','Duration in days',p.duration||1,'number','required min="1" max="60"')}
        ${input('max_people','Maximum travelers per booking',p.max_people||10,'number','required min="1" max="50"')}
        <div class="full">
            <label class="form-label" for="f-photo_file">Cover photo</label>
            <div class="package-photo-container">
                <input class="form-control" id="f-photo_file" name="photo_file" type="file" accept="image/jpeg,image/png,image/webp">
                <div class="d-flex align-items-center gap-2 mt-2">
                    <small class="muted">Or choose preset:</small>
                    <select class="form-select form-select-sm" style="max-width:220px;" id="f-image" name="image">
                        <option value="" ${p.image?.startsWith('api/')?'selected':''}>-- Custom uploaded photo --</option>
                        <option value="mountains.jpg" ${(p.image||'mountains.jpg')==='mountains.jpg'?'selected':''}>Mountains (Preset)</option>
                        <option value="coast.jpg" ${p.image==='coast.jpg'?'selected':''}>Coast (Preset)</option>
                        <option value="city.jpg" ${p.image==='city.jpg'?'selected':''}>City (Preset)</option>
                    </select>
                </div>
                <div id="photo-preview-wrap" class="mt-2">
                    <small class="d-block muted mb-1">Cover preview:</small>
                    <div style="position:relative;display:inline-block;">
                        <img id="photo-preview-img" src="${packageImg(p.image||'mountains.jpg')}" alt="Cover preview" style="width:180px;height:110px;object-fit:cover;border-radius:8px;border:1px solid #cbd5e1;display:block;">
                        <button type="button" id="photo-preview-clear" class="btn btn-sm btn-light" style="position:absolute;top:4px;right:4px;padding:2px 6px;font-size:11px;background:rgba(255,255,255,0.92);border-radius:4px;">&times; Reset</button>
                    </div>
                </div>
            </div>
        </div>
        ${area('description','Overview',p.description||'',4000)}
        ${area('inclusions','Everything included (meals, rooms, activities, transfers, taxes)',p.inclusions||'',4000)}
        ${area('exclusions','Not included',p.exclusions||'',2000)}
        ${area('itinerary','Day-by-day itinerary',p.itinerary||'',4000)}
        ${area('terms','Availability, booking and cancellation terms',p.terms||'',2000)}
        ${select('active','Visibility',[['false','Draft'],['true','Published']],String(p.active??false))}
        ${submit('Save package')}
    </form>`);
}
function supplierForm(type,x={}){const hotel=type==='hotel';openDialog((x.id?'Edit ':'Add ')+type,`<form data-form="supplier-save" data-type="${type}" class="form-grid">${x.id?`<input type="hidden" name="id" value="${x.id}">`:''}${input('name','Name',x.name||'','text','required maxlength="120"')}${hotel?input('location','Location / address',x.location||'','text','required maxlength="200"'):select('mode','Travel mode',['Flight','Train','Bus','Car','Ferry','Mixed'],x.mode)}${hotel?area('amenities','Amenities and room details',x.amenities||'',2000):area('route_details','Route / pickup / drop-off',x.route_details||'',500)}${area('description','Service details',x.description||'',2000)}${select('active','Availability',[['true','Active'],['false','Archived']],String(x.active??true))}${submit()}</form>`);}
async function packageAction(action,id){if(action==='package-view'){packageView(id);return true;}if(action==='booking-view'){bookingView(id);return true;}
if(['package-new','package-edit'].includes(action)){packageForm(state.packages.packages.find(p=>Number(p.id)===id)||{});return true;}
if(action==='package-delete'){const p=state.packages.packages.find(p=>Number(p.id)===id);confirmAction('Delete package?',`Remove ${esc(p.name)} from the client catalog? Existing bookings will be retained.`,'admin/package',{id,version:p.version,action:'delete'},'Delete package');return true;}
for(const type of ['hotel','transport']){if(action===type+'-new'||action===type+'-edit'){supplierForm(type,(type==='hotel'?state.packages.hotels:state.packages.transport).find(x=>Number(x.id)===id)||{});return true;}if(action===type+'-delete'){confirmAction('Archive '+type+'?','Unpublish linked packages first. Historical bookings remain unchanged.','admin/'+type,{id,action:'delete'},'Archive');return true;}}
if(action==='booking-cancel'){confirmAction('Cancel booking request?','This cancels your pending request. Any simulated payment remains in the ledger for refund review.','booking-status',{id,status:'CANCELLED'},'Cancel request');return true;}
if(action==='demo-payment'){confirmAction('Simulate payment?','This marks the outstanding amount as paid in the demo ledger. No money will move.','booking-demo-payment',{booking_id:id,request_key:crypto.randomUUID()},'Simulate payment');return true;}return false;}
async function packageSubmit(type,body,form){
    const paths={'package-save':'admin/package','supplier-save':'admin/'+form.dataset.type,'package-book':'booking','booking-status':'admin/booking-status','payment-entry':'admin/payment'};
    if(!paths[type])return false;
    if(type==='package-save'){
        const file=form.elements.photo_file?.files?.[0];
        delete body.photo_file;
        if(file){
            if(file.size>4_000_000)throw Error('Photo must be smaller than 4 MB.');
            if(!['image/jpeg','image/png','image/webp'].includes(file.type))throw Error('Upload a JPEG, PNG or WebP photograph.');
            body.photo=await new Promise((resolve,reject)=>{
                const r=new FileReader();
                r.onload=()=>resolve(r.result);
                r.onerror=()=>reject(Error('Could not read this photo file.'));
                r.readAsDataURL(file);
            });
        }
    }
    if('active' in body)body.active=body.active==='true';
    if(type==='package-book')body.accepted_terms=body.accepted_terms==='on';
    await api(paths[type],body);
    modal.close();
    if(type==='package-book')location.hash='bookings';
    await render();
    toast(type==='package-book'?'Demo booking requested.':'Saved.');
    return true;
}
document.addEventListener('input',event=>{const f=event.target.form;if(f?.dataset.form==='package-book'&&event.target.name==='people'){const p=state.packages.packages.find(p=>Number(p.id)===Number(f.elements.package_id.value));document.querySelector('#booking-quote').textContent=money(Number(p.price)*Number(event.target.value));}});
document.addEventListener('change',event=>{
    const el=event.target;
    if(el.name==='photo_file'&&el.form?.dataset.form==='package-save'){
        const file=el.files?.[0];
        const preview=el.form.querySelector('#photo-preview-img');
        const wrap=el.form.querySelector('#photo-preview-wrap');
        const select=el.form.querySelector('#f-image');
        if(file&&preview&&wrap){
            const r=new FileReader();
            r.onload=e=>{preview.src=e.target.result;wrap.style.display='block';if(select)select.value='';};
            r.readAsDataURL(file);
        }
    }
    if(el.name==='image'&&el.form?.dataset.form==='package-save'){
        const preview=el.form.querySelector('#photo-preview-img');
        const wrap=el.form.querySelector('#photo-preview-wrap');
        const fileInput=el.form.querySelector('#f-photo_file');
        if(el.value&&preview&&wrap){
            if(fileInput)fileInput.value='';
            preview.src='assets/'+el.value;
            wrap.style.display='block';
        }
    }
});
document.addEventListener('click',event=>{
    if(event.target.id==='photo-preview-clear'||event.target.closest('#photo-preview-clear')){
        const form=event.target.closest('form');
        if(form){
            const fileInput=form.querySelector('#f-photo_file');
            if(fileInput)fileInput.value='';
            const preview=form.querySelector('#photo-preview-img');
            const wrap=form.querySelector('#photo-preview-wrap');
            const select=form.querySelector('#f-image');
            if(select)select.value='mountains.jpg';
            if(preview)preview.src='assets/mountains.jpg';
            if(wrap)wrap.style.display='block';
        }
    }
});
