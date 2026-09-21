const vm = require('node:vm');
const fs = require('node:fs');
const assert = require('node:assert/strict');
const source = fs.readFileSync('app/assets/app.js', 'utf8').split('// ---- boot ----')[1].split('\nshowSection(section);')[0];
let decoded, shown = 0;
const timers = new Map();
let nextTimer = 0;
const image = {getAttribute:()=>'/art/cover.jpg',decode:()=>new Promise(r=>decoded=r)};
const context = vm.createContext({
 state: {appsLoaded:false,scanning:false}, signature:'initial',
 video:{dataset:{},readyState:0},
 document:{querySelectorAll:()=>[image],fonts:{ready:Promise.resolve()},body:{classList:{replace:()=>shown++,remove:()=>{}}}},
 requestAnimationFrame:fn=>fn(),
 setTimeout:(fn,ms)=>{const id=++nextTimer;timers.set(id,{fn,ms});return id;},
 clearTimeout:id=>timers.delete(id)
});
vm.runInContext(source,context);
const settle=()=>vm.runInContext('settleBoot()',context);
const flush=()=>new Promise(r=>setImmediate(r));
(async()=>{
 settle(); assert.equal(shown,0); assert.equal(decoded,undefined,'must wait for native data');
 context.state.appsLoaded=true; context.state.scanning=true; settle(); assert.equal(decoded,undefined);
 context.state.scanning=false; settle(); assert.equal(shown,0,'wait for image decode');
 context.signature='new'; decoded(); await flush(); assert.equal(shown,0,'changed snapshot must wait again');
 decoded(); await flush(); assert.equal(shown,1);
 settle(); await flush(); assert.equal(shown,1,'never replay on subsequent state updates');
 assert.ok(![...timers.values()].some(t=>t.ms===6000),'successful reveal clears deadline');
 vm.runInContext('booted=false;bootPreparing=false',context);
 settle(); assert.equal(shown,1,'stalled artwork stays hidden initially');
 vm.runInContext('finishBoot()',context);
 assert.equal(shown,2,'deadline reveals interface even when artwork never resolves');
 decoded(); await flush(); assert.equal(shown,2,'late artwork completion must not replay reveal');
 console.log('PASS: native readiness, scan readiness, asset decode, snapshot change and single reveal');
})().catch(e=>{console.error(e);process.exitCode=1});
