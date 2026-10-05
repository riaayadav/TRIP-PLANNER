import {chromium} from 'playwright';
import fs from 'node:fs/promises';
const dir='D:/Github/TRIP-PLANNER/.presentation-build/screens';await fs.mkdir(dir,{recursive:true});
const browser=await chromium.launch({headless:true,channel:"msedge"});const page=await browser.newPage({viewport:{width:1440,height:960},deviceScaleFactor:1});
await page.goto('http://127.0.0.1:8080');await page.waitForTimeout(1200);
await page.screenshot({path:dir+'/landing.png'});
const result=await page.evaluate(async()=>{const sess=await (await fetch('/api/session')).json();const email='presentation.'+Date.now()+'@example.com';const res=await fetch('/api/register',{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-Token':sess.csrf},body:JSON.stringify({name:'Demo Traveler',email,password:crypto.randomUUID()+'Aa1!'})});if(!res.ok)throw Error(await res.text());const next=await (await fetch('/api/session')).json();const data=await (await fetch('/api/data')).json();const dest=data.destinations.find(d=>/manali/i.test(d.name))||data.destinations[0];const r=await fetch('/api/trip',{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-Token':next.csrf},body:JSON.stringify({title:'Mountain Escape',destination_id:dest.id,start_date:'2026-10-15',end_date:'2026-10-19',people:2,budget:30000,interests:'Nature, local food and photography',itinerary_mode:'suggested'})});if(!r.ok)throw Error(await r.text());return await r.json();});
await page.reload();await page.waitForTimeout(600);
for(const route of ['home','season','explore','perfect','plan','itinerary/'+result.id,'budget/'+result.id,'packing/'+result.id,'compare','wishlist','countdown','profile','packages']){
 await page.goto('http://127.0.0.1:8080/#'+route);await page.waitForTimeout(550);await page.screenshot({path:dir+'/'+route.split('/')[0]+'.png'});
}
await browser.close();console.log('Screenshots captured');

