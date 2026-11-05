const fs = require('node:fs');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const validate = vm.runInNewContext('('+fs.readFileSync('couchdb/validate.js','utf8')+')');
function fixture() {
 const entry={by:'alice',action:'CREATE',state:'OPEN',at:'2026-01-01T00:00:00.000000000Z'};
 return {_id:'event:00000000-0000-0000-0000-000000000001',id:'00000000-0000-0000-0000-000000000001',kind:'event',schemaVersion:2,title:'Books',type:'MEETING',startsAt:entry.at,createdAt:entry.at,capacity:20,state:'OPEN',updatedAt:entry.at,lastModified:entry,history:[entry]};
}
const old=fixture(); validate(old,null,{});
for (const mutate of [d=>d.capacity=0,d=>d.schemaVersion=1,d=>d.history=[],d=>d.lastModified={...d.lastModified,by:''},d=>d._deleted=true,d=>delete d.createdAt,d=>d.startsAt='not-a-date',d=>d.id='invalid',d=>d.capacity=2147483648]) {
 const d=structuredClone(old); mutate(d); assert.throws(()=>validate(d,null,{}));
}
const next=structuredClone(old);
const entry={by:'bob',action:'UPDATE',state:'CLOSED',at:'2026-01-02T00:00:00.000000000Z'};
Object.assign(next,{lastModified:entry,updatedAt:entry.at,state:entry.state});next.history.push(entry);
validate(next,old,{});
const rewritten=structuredClone(next);rewritten.history[0].by='mallory';assert.throws(()=>validate(rewritten,old,{}));
const emitted=[];
const map=vm.runInNewContext('('+fs.readFileSync('couchdb/user_actions.js','utf8')+')',{emit:(k,v)=>emitted.push([Array.from(k),v])});
map(next);assert.deepEqual(emitted,[[['alice','CREATE'],1],[['bob','UPDATE'],1]]);
console.log('Validation and MapReduce map: 13 checks passed');
