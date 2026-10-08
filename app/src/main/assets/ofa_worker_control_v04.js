/* OFA Chief Worker Control v0.4. Additive PRIVATE-dashboard module.
 * Load after existing OFACloudReceive; requires CEO-authenticated OFACloud.
 * No credentials, worker enable API or arbitrary RPC. */
(function (g) {
"use strict";
var callbacks=new Set(["worker_release","worker_queue","worker_job","worker_cancel"]);
var root=null, snapshot=null, busy=false, retry=null, lastTask=null, err="", lastUpdated=null;
function E(tag,cls,txt){var n=document.createElement(tag);if(cls)n.className=cls;if(txt!==undefined)n.textContent=String(txt);return n;}
function workerState(s){
  if(!s||!s.worker||!s.worker.registered)return "UNREGISTERED";
  var w=s.worker;
  if(w.revoked||!w.email_verified||w.capability!=="evidence_only")return "BLOCKED";
  if(!w.enabled)return "EXECUTION DISABLED";
  if(!w.heartbeat_fresh)return "HEARTBEAT STALE";
  return "SUPERVISED CANARY READY";
}
function date(v){if(!v)return "—";var d=new Date(v);return Number.isFinite(d.getTime())?d.toLocaleString():"—";}
function metric(box,k,v){var item=E("div","ofa-wrk-metric");item.append(E("span","ofa-wrk-small",k),E("strong","",v));box.append(item);}
function button(label,func,disabled,cls){var b=E("button","ofa-wrk-btn "+(cls||""),label);b.type="button";b.disabled=!!disabled;b.addEventListener("click",func);return b;}
function cloud(name){return !!(g.OFACloud && typeof g.OFACloud[name]==="function");}
function refresh(){if(!cloud("refreshWorkerRelease")){err="Worker native bridge unavailable";render();return;}busy=true;err="";render();g.OFACloud.refreshWorkerRelease();}
function newRequest(){
  if(retry)return retry;
  if(!g.crypto||typeof g.crypto.getRandomValues!=="function")throw Error("Secure request identity unavailable");
  var bytes=new Uint8Array(12);g.crypto.getRandomValues(bytes);
  var nonce=Array.from(bytes,function(b){return b.toString(16).padStart(2,"0");}).join("");
  retry={id:"chiefv04_"+nonce,marker:"OFAChiefV04_"+nonce.slice(0,16)};
  return retry;
}
function queue(){
  if(busy||workerState(snapshot)!=="SUPERVISED CANARY READY")return;
  if(!cloud("queueEvidenceJob")){err="Job bridge missing";render();return;}
  try{var r=newRequest();busy=true;err="";render();g.OFACloud.queueEvidenceJob(r.id,r.marker);}
  catch(e){err=String(e.message).slice(0,150);render();}
}
function details(id){
  if(!cloud("getEvidenceJob"))return;
  lastTask=id;busy=true;render();g.OFACloud.getEvidenceJob(id);
}
function cancel(id){
  if(!g.confirm("Cancel this evidence-only job? Database cancellation cannot prove the physical worker process stopped."))return;
  if(!cloud("cancelEvidenceJob"))return;
  busy=true;render();g.OFACloud.cancelEvidenceJob(id);
}
function render(){
  if(!root)return;
  root.replaceChildren();
  var h=E("header","ofa-wrk-head");
  h.append(E("span","ofa-wrk-eyebrow","CHIEF / CONTROL PLANE"),E("h2","","Worker operations"),
    E("p","ofa-wrk-lead","Evidence-backed automation, not simulated progress."));
  h.append(E("div","ofa-wrk-pill",workerState(snapshot)));root.append(h);
  if(err)root.append(E("div","ofa-wrk-error",err));
  var counts=snapshot&&snapshot.counts||{},grid=E("div","ofa-wrk-metrics");
  metric(grid,"Active",counts.active===undefined?"—":counts.active);
  metric(grid,"Completed",counts.completed===undefined?"—":counts.completed);
  metric(grid,"Failed / cancelled",counts.failed_or_cancelled===undefined?"—":counts.failed_or_cancelled);
  metric(grid,"Total",counts.total===undefined?"—":counts.total);
  root.append(grid);
  var w=snapshot&&snapshot.worker||{},detailsBox=E("section","ofa-wrk-section");
  detailsBox.append(E("h3","","Restricted worker"));
  var facts=E("div","ofa-wrk-facts");
  metric(facts,"Verified identity",w.email_verified?"VERIFIED":"NOT VERIFIED");
  metric(facts,"Capability",w.capability==="evidence_only"?"EVIDENCE ONLY":"UNAVAILABLE");
  metric(facts,"Heartbeat",w.heartbeat_fresh?"FRESH":"STALE");
  metric(facts,"Last heartbeat",date(w.last_heartbeat));
  detailsBox.append(facts);root.append(detailsBox);
  var bar=E("div","ofa-wrk-actions");
  bar.append(button(busy?"Working…":"Refresh",refresh,busy,"ofa-wrk-ghost"));
  var canQueue=workerState(snapshot)==="SUPERVISED CANARY READY" && !busy && (counts.active||0)<3;
  bar.append(button(retry?"Retry canary request":"Queue evidence canary",queue,!canQueue,"ofa-wrk-accent"));
  root.append(bar);
  var jobsBox=E("section","ofa-wrk-section");jobsBox.append(E("h3","","Recent evidence-only jobs"));
  var items=Array.isArray(snapshot&&snapshot.jobs)?snapshot.jobs:[];
  if(!items.length)jobsBox.append(E("p","ofa-wrk-muted","No independently verified executions yet."));
  items.forEach(function(job){
    var row=E("div","ofa-wrk-job"),info=E("div","ofa-wrk-jobcopy"),controls=E("div","ofa-wrk-jobactions");
    info.append(E("strong","",String(job.status||"unknown").toUpperCase()),
      E("span","ofa-wrk-small","Attempt "+String(job.attempts||0)+" · "+date(job.created_at)),
      E("span","ofa-wrk-small",job.artifact_integrity==="server_verified"?
         "SERVER SHA-256 VERIFIED · "+String(job.artifact_sha256||"").slice(0,16)+"…":
         "Artifact proof: "+String(job.artifact_integrity||"not_present")));
    controls.append(button("Details",function(){details(job.task_id);},busy,"ofa-wrk-ghost"));
    if(["queued","leased","working"].includes(job.status))
      controls.append(button("Cancel",function(){cancel(job.task_id);},busy,"ofa-wrk-danger"));
    row.append(info,controls);jobsBox.append(row);
  });
  root.append(jobsBox);
  root.append(E("footer","ofa-wrk-foot","Server: "+date(snapshot&&snapshot.server_time)+
    " · Updated: "+date(lastUpdated)+
    " · Completion requires independently verified evidence."));
}
function receive(kind,payload){
  if(!callbacks.has(kind))return;
  var data;
  try{data=typeof payload==="string"?JSON.parse(payload):payload;}
  catch(e){busy=false;err="Malformed worker response";render();return;}
  busy=false;
  if(!data||data.ok!==true){err=String(data&&data.error||"Worker request failed").slice(0,180);render();return;}
  var v=data.data||{};err="";
  if(kind==="worker_release"){
    if(v.kind!=="ofa_ceo_evidence_worker_release_snapshot_v1"){
      err="Unexpected snapshot contract";render();return;
    }
    snapshot=v;lastUpdated=Date.now();
  }else if(kind==="worker_queue"){
    if(typeof v.task_id==="string")lastTask=v.task_id;
    retry=null;busy=true;g.OFACloud.refreshWorkerRelease();
  }else if(kind==="worker_cancel"){
    busy=true;g.OFACloud.refreshWorkerRelease();
  }else if(kind==="worker_job"){
    if(lastTask&&v.task_id&&v.task_id!==lastTask){err="Unexpected job response";render();return;}
    busy=true;g.OFACloud.refreshWorkerRelease();
  }
  render();
}
function mount(target){
  if(typeof target==="string")target=document.querySelector(target);
  if(!target)throw Error("OFA worker control mount target missing");
  root=target;root.classList.add("ofa-wrk-root");render();refresh();
}
var previous=typeof g.OFACloudReceive==="function"?g.OFACloudReceive:null;
g.OFACloudReceive=function(kind,payload){
  if(callbacks.has(kind)){receive(kind,payload);return;}
  if(previous)return previous.apply(this,arguments);
};
g.OFAWorkerControl=Object.freeze({mount:mount,receive:receive,refresh:refresh,
  isMounted:function(){return !!root;},status:function(){return workerState(snapshot);}});
})(window);
