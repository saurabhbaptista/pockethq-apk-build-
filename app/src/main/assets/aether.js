/* Aether: original low-power procedural atmosphere, not a video or web image. */
(() => {
'use strict';
const c=document.getElementById('aether');if(!c||!c.getContext)return;
const ctx=c.getContext('2d',{alpha:true}),reduce=matchMedia('(prefers-reduced-motion: reduce)');
let W=0,H=0,dpr=1,particles=[],last=0,frame=0,raf=0,visible=true;
const fract=x=>x-Math.floor(x),random=i=>fract(Math.sin(i*127.1+32.9)*43758.5453);
function resize(){dpr=Math.min(1.5,devicePixelRatio||1);W=innerWidth;H=innerHeight;c.width=Math.floor(W*dpr);c.height=Math.floor(H*dpr);c.style.width=W+'px';c.style.height=H+'px';ctx.setTransform(dpr,0,0,dpr,0,0);particles=Array.from({length:Math.min(48,Math.max(20,Math.floor(W*H/17000)))},(_,i)=>({x:random(i+10)*W,y:random(i+70)*H,s:.4+random(i+340)*1.4,v:.11+random(i+421)*.34,p:random(i+787)*Math.PI*2}));draw(0);}
function draw(t){if(!visible)return;ctx.clearRect(0,0,W,H);const glow=ctx.createRadialGradient(W*.55,H*.22,4,W*.55,H*.22,Math.max(W,H)*.54);glow.addColorStop(0,'rgba(66,102,204,.105)');glow.addColorStop(.46,'rgba(45,75,164,.045)');glow.addColorStop(1,'rgba(8,11,25,0)');ctx.fillStyle=glow;ctx.fillRect(0,0,W,H);
 for(const p of particles){const x=p.x+Math.sin(t*.00017+p.p)*5,y=(p.y+t*p.v*.0013)%(H+20),alpha=.14+.16*(.5+Math.sin(t*.0008+p.p)*.5);ctx.beginPath();ctx.fillStyle=`rgba(161,202,255,${alpha})`;ctx.arc(x,y,p.s,0,Math.PI*2);ctx.fill();}
 ctx.strokeStyle='rgba(88,141,216,.075)';ctx.lineWidth=.8;
 for(let i=0;i<particles.length;i++){const a=particles[i];for(let j=i+1;j<particles.length;j++){const b=particles[j],dx=a.x-b.x,dy=a.y-b.y,dist=dx*dx+dy*dy;if(dist<8700){ctx.globalAlpha=(1-dist/8700)*.5;ctx.beginPath();ctx.moveTo(a.x,a.y);ctx.lineTo(b.x,b.y);ctx.stroke();}}}ctx.globalAlpha=1;
}
function tick(t){raf=0;if(!visible||reduce.matches)return;if(t-last>45){last=t;draw(t);}raf=requestAnimationFrame(tick);}
function control(){visible=!document.hidden;if(!raf&&visible&&!reduce.matches)raf=requestAnimationFrame(tick);if(!visible&&raf){cancelAnimationFrame(raf);raf=0;}if(reduce.matches)draw(0);}
addEventListener('resize',resize,{passive:true});document.addEventListener('visibilitychange',control);reduce.addEventListener?.('change',control);resize();control();
})();
