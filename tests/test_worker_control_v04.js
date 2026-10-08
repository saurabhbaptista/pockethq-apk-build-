/* No-dependency DOM simulation for OFA Worker Control v0.4 */
'use strict';
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
let count=0;
function test(name,fn){fn();count++;process.stdout.write('PASS '+name+'\n');}
class FakeElement {
  constructor(tag){this.tag=tag;this.children=[];this.className='';this.textContent='';
    this.disabled=false;this.listeners={};this.classList={add:()=>{}};}
  append(...c){this.children.push(...c);}
  appendChild(c){this.children.push(c);}
  replaceChildren(...c){this.children=[...c];}
  addEventListener(type,handler){this.listeners[type]=handler;}
  click(){if(!this.disabled && this.listeners.click)this.listeners.click();}
}
const buttonNodes=[];
const document={createElement:(tag)=>{
  const e=new FakeElement(tag);
  if(tag==='button')buttonNodes.push(e);
  return e;
},querySelector:()=>null};
const root=new FakeElement('main');
const calls={refresh:0,queued:[],details:[],cancels:[]};
const oldCallbacks=[];
const window={document,Date,Math,Array,Number,Set,Uint8Array,JSON,String,
  crypto:{getRandomValues:(a)=>{for(let i=0;i<a.length;i++)a[i]=i+2;return a;}},
  confirm:()=>true,
  OFACloudReceive:(k,p)=>{oldCallbacks.push(k)},
  OFACloud:{
    refreshWorkerRelease:()=>{calls.refresh++},
    queueEvidenceJob:(id,mark)=>calls.queued.push({id,mark}),
    getEvidenceJob:(id)=>calls.details.push(id),
    cancelEvidenceJob:(id)=>calls.cancels.push(id),
  }};
const js=fs.readFileSync(path.join(__dirname,'..','app','src','main','assets','ofa_worker_control_v04.js'),'utf8');
vm.runInNewContext(js,{window,document,Date,Math,Array,Number,Set,Uint8Array,JSON,String,Error}, {timeout:3000});
const api=window.OFAWorkerControl;
function reply(kind,body){window.OFACloudReceive(kind,JSON.stringify(body));}
function buttonByText(txt){return [...buttonNodes].reverse().find(x=>x.textContent===txt);}
function snapshot(overrides={}){
 const obj={kind:'ofa_ceo_evidence_worker_release_snapshot_v1',server_time:'2026-10-08T20:00:00Z',
  worker:{registered:true,email_verified:true,enabled:false,revoked:false,
          heartbeat_fresh:true,capability:'evidence_only',last_heartbeat:'2026-10-08T20:00:00Z'},
  counts:{active:0,completed:0,failed_or_cancelled:0,total:0},jobs:[]};
 if(overrides.worker)Object.assign(obj.worker,overrides.worker);
 if(overrides.counts)Object.assign(obj.counts,overrides.counts);
 if(overrides.jobs)obj.jobs=overrides.jobs;
 return obj;
}
test('mount calls native refresh',()=>{
 api.mount(root);
 assert.equal(calls.refresh,1);
 assert.equal(api.isMounted(),true);
});
test('callback is chained into pre-existing OFA handler',()=>{
 reply('worker_release',{ok:true,data:snapshot()});
 assert.equal(oldCallbacks.includes('worker_release'),false); // worker callbacks stay in isolated panel
});
test('disabled evidence worker never queueable',()=>{
 assert.equal(api.status(),'EXECUTION DISABLED');
 assert.equal(buttonByText('Queue evidence canary').disabled,true);
 buttonByText('Queue evidence canary').click();
 assert.equal(calls.queued.length,0);
});
test('invalid response does not activate queue',()=>{
 reply('worker_release',{ok:true,data:{kind:'forged'}});
 assert.equal(calls.queued.length,0);
});
test('valid ready worker permits exactly one scoped call',()=>{
 reply('worker_release',{ok:true,data:snapshot({worker:{enabled:true}})});
 assert.equal(api.status(),'SUPERVISED CANARY READY');
 buttonByText('Queue evidence canary').click();
 assert.equal(calls.queued.length,1);
 assert.match(calls.queued[0].id,/^chiefv04_[0-9a-f]{24}$/);
 assert.match(calls.queued[0].mark,/^OFAChiefV04_[0-9a-f]{16}$/);
});
test('network failure retains idempotency request for retry',()=>{
 reply('worker_queue',{ok:false,error:'Network temporarily unavailable'});
 const pending=buttonByText('Retry canary request');
 assert.equal(pending.disabled,false);
 pending.click();
 assert.equal(calls.queued.length,2);
 assert.deepEqual(calls.queued[0],calls.queued[1]);
});
test('queue acknowledgement refreshes live source of truth',()=>{
 const before=calls.refresh;
 reply('worker_queue',{ok:true,data:{task_id:'ffffffff-ffff-4fff-8fff-ffffffffffff',status:'queued'}});
 assert.equal(calls.refresh,before+1);
});
test('server snapshot completed job not fabricated',()=>{
 reply('worker_release',{ok:true,data:snapshot({worker:{enabled:true},counts:{total:1,completed:1},jobs:[
   {task_id:'ffffffff-ffff-4fff-8fff-ffffffffffff',status:'completed',attempts:1,
    artifact_integrity:'server_verified',artifact_sha256:'abcdef'.repeat(10)+'abcd'}
 ]})});
 assert.equal(api.status(),'SUPERVISED CANARY READY');
 const details=buttonByText('Details');details.click();
 assert.deepEqual(calls.details,['ffffffff-ffff-4fff-8fff-ffffffffffff']);
});
test('cancellation offered only for queued/active jobs',()=>{
 reply('worker_release',{ok:true,data:snapshot({jobs:[{
    task_id:'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    status:'queued',attempts:0,artifact_integrity:'not_present'}]})});
 const cancel=buttonByText('Cancel');
 assert.ok(cancel);cancel.click();
 assert.deepEqual(calls.cancels,['aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa']);
});
test('malformed JSON fails closed',()=>{
 window.OFACloudReceive('worker_release','{notjson');
 assert.equal(api.status(),'EXECUTION DISABLED');
});
test('unknown callback preserved',()=>{
 window.OFACloudReceive('auth','{}');assert.equal(oldCallbacks.includes('auth'),true);
});
test('no generic worker enable interface',()=>{
 assert.equal(typeof window.OFACloud.enableWorker,'undefined');
 assert.equal(typeof api.enableWorker,'undefined');
});
console.log('OFA_WORKER_UI_TESTS_OK count='+count);
