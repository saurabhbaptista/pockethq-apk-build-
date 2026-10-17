/* Optional Firebase Realtime Database sync via a bounded native HTTPS bridge.
   Firebase is NOT provisioned or connected in the distributed APK.
   Local scoring works forever without a network. */
(() => {
'use strict';
const STORE='baptista_brain_family_live_config_v1';
const BRIDGE=()=>window.AndroidBridge&&typeof window.AndroidBridge.configureLive==='function'?window.AndroidBridge:null;
let current=null,callbacks=null,ready=false,cache={events:{},matches:{}};
function readCfg(){try{return JSON.parse(localStorage.getItem(STORE)||'null');}catch(e){return null;}}
function setStatus(phase,details=''){
 current=phase;const pill=document.getElementById('livePill'),league=document.getElementById('leagueStatus'),updated=document.getElementById('leagueUpdated');
 const messages={offline:'LOCAL',connecting:'CONNECTING',live:'LIVE',error:'SYNC ERROR',unavailable:'LOCAL'};
 if(pill){pill.classList.toggle('live',phase==='live');pill.classList.toggle('connecting',phase==='connecting');pill.classList.toggle('error',phase==='error');pill.innerHTML=`<i></i> ${messages[phase]||'LOCAL'}`;pill.title=details||'';}
 if(league)league.innerHTML=phase==='live'?'<span class="dot-off dot-live"></span> FAMILY LIVE · CONNECTED':phase==='connecting'?'<span class="dot-off"></span> CONNECTING TO FAMILY LIVE':phase==='error'?'<span class="dot-off"></span> LIVE SYNC UNAVAILABLE':'<span class="dot-off"></span> SAVED ON THIS PHONE';
 if(updated)updated.textContent=phase==='live'?'UPDATES AUTOMATICALLY':phase==='error'?'LOCAL SCORES PRESERVED':'PLAY TO ADD POINTS';
}
function validate(cfg){if(!cfg||typeof cfg!=='object')throw Error('Enter a valid Firebase configuration');
 const apiKey=String(cfg.apiKey||'').trim(),db=String(cfg.databaseURL||'').trim().replace(/\/+$/,''),roomId=String(cfg.roomId||'').trim();
 if(!/^[A-Za-z0-9_-]{15,180}$/.test(apiKey))throw Error('Invalid Firebase Web API key');
 const url=new URL(db);if(url.protocol!=='https:'||url.port||url.username||url.password||url.search||url.hash||!/^[-a-z0-9]+\.(firebaseio\.com|firebasedatabase\.app)$/.test(url.hostname)||url.pathname!=='/')throw Error('Use a Firebase Realtime Database HTTPS root URL');
 if(!/^[A-Za-z0-9_-]{30,80}$/.test(roomId))throw Error('Family room code must be 30–80 characters');
 return {apiKey,databaseURL:db,roomId};}
function makeRoom(){if(!crypto?.getRandomValues)throw Error('Secure random generator unavailable');const bytes=new Uint8Array(24);crypto.getRandomValues(bytes);return Array.from(bytes,x=>x.toString(16).padStart(2,'0')).join('');}
function nativeStatus(phase,reason=''){if(!['offline','connecting','live','error'].includes(phase))return;ready=phase==='live';setStatus(phase,String(reason).slice(0,160));if(ready&&callbacks)sendPending();}
function nativeEvent(json){try{if(typeof json!=='string'||json.length>2200000)return;const payload=JSON.parse(json);const path=String(payload.path||'/');const val=payload.data;
 if(path==='/'){const root=val||{};cache={events:root.events&&typeof root.events==='object'?root.events:{},matches:root.matches&&typeof root.matches==='object'?root.matches:{}};}
 else if(path==='/events'){cache.events=val&&typeof val==='object'?val:{};}
 else if(path==='/matches'){cache.matches=val&&typeof val==='object'?val:{};}
 else if(path.startsWith('/events/')){const id=path.slice(8);if(/^[A-Za-z0-9_-]{6,120}$/.test(id)){if(val===null)delete cache.events[id];else cache.events[id]=val;}}
 else if(path.startsWith('/matches/')){const id=path.slice(9);if(/^[A-Za-z0-9_-]{6,120}$/.test(id)){if(val===null)delete cache.matches[id];else cache.matches[id]=val;}}
 else if(path==='/events'&&val&&typeof val==='object')Object.assign(cache.events,val);
 else if(path==='/matches'&&val&&typeof val==='object')Object.assign(cache.matches,val);
 else {const chunks=path.replace(/^\//,'').split('/');if(chunks[0]==='events'||chunks[0]==='matches'){const map=cache[chunks[0]];if(chunks.length===2){map[chunks[1]]=val;}}}
 callbacks?.merge(cache);
 }catch(e){console.warn('Discarded malformed Firebase event',e);}}
function sendPending(){const bridge=BRIDGE();if(!bridge||!ready||!callbacks)return;const data=callbacks.getData();const payload=JSON.stringify({events:data.events,matches:data.matches});if(payload.length>2600000){setStatus('error','Too much data for one sync batch. Export backup.');return;}
 try{bridge.uploadFamilyEvents(payload);}catch(e){setStatus('error',String(e));}}
function connect(input){const cfg=validate(input);const bridge=BRIDGE();if(!bridge)throw Error('Family Live needs the Android APK and Firebase connection');localStorage.setItem(STORE,JSON.stringify(cfg));cache={events:{},matches:{}};ready=false;setStatus('connecting');bridge.configureLive(JSON.stringify(cfg));return cfg;}
function init(cbs){callbacks=cbs;setStatus('offline');const cfg=readCfg();if(cfg){try{connect(cfg);}catch(e){setStatus('error',String(e));}}}
window.BaptistaSync={init,getConfig:readCfg,makeRoom,validate,connect,statusText:()=>({live:'Live family leaderboard connected',connecting:'Connecting to Family Live',error:'Family Live unavailable — offline data protected'}[current]||'Offline progress saved on this phone'),onLocalSave:()=>{if(ready)sendPending();},nativeStatus,nativeEvent};
})();
