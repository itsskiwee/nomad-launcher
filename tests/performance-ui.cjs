const vm = require('node:vm');
const fs = require('node:fs');
const assert = require('node:assert/strict');
const elements = new Map();
const element = id => {
  if (!elements.has(id)) elements.set(id, { textContent: '', disabled: false, hidden: true, style: {setProperty(){}}, classList: {toggle(){}}, setAttribute(){}, focus(){} });
  return elements.get(id);
};
const cards = ['saver','balanced','performance','turbo'].map(mode => ({...element(mode), dataset:{mode}}));
const calls = [];
const sandbox = { $, notify(){}, icon:()=>'', document: { querySelector:()=>element('panel'), querySelectorAll:selector=>selector === '[data-icon]' ? [] : cards, addEventListener(){} }, Performance: {status:()=>calls.push('status'), apply:mode=>calls.push(mode)}, setTimeout:()=>1, clearTimeout(){}, setInterval:()=>1, clearInterval(){}, console };
function $(id) { return element(id); }
sandbox.window = sandbox;
vm.createContext(sandbox);
const source = fs.readFileSync('app/assets/app.js','utf8').split('// Selection is separate from the verified device state.')[1];
vm.runInContext(source, sandbox);
const run = code => vm.runInContext(code, sandbox);
const state = (active, request='status') => sandbox.receivePerformance({available:true,active,request,cpu0:'500000',cpu1:'774000',gpu:'270000',gpuMax:'806000',cpu0max:'2000000',cpu1max:'2050000',temperature:'350'});
run('requestPerformance()'); state('balanced');
run("previewPerformance('turbo')");
assert.equal(calls.at(-1), 'status', 'selection alone must not apply');
assert.equal(element('activeModeBadge').textContent, 'Selected');
// A press during a live refresh must queue, never be dropped.
run('requestPerformance()');
run("requestPerformance('turbo')");
assert.equal(calls.at(-1), 'status');
state('balanced');
assert.equal(calls.at(-1), 'turbo');
assert.equal(element('applyPerformance').textContent, 'Applying…');
state('turbo','turbo');
assert.equal(element('activeModeBadge').textContent, 'Active');
assert.equal(element('applyPerformance').textContent, 'Applied');
run("requestPerformance('balanced')");
sandbox.receivePerformance({available:false,error:'Denied',request:'balanced'});
assert.equal(element('activeModeBadge').textContent,'Unverified');
assert.equal(element('retryPerformance').hidden,false);
assert.equal(element('applyPerformance').disabled,true);
assert.equal(element('liveCpu').textContent,'—');
console.log('PASS: selection vs apply, queued apply during refresh, verified success, root failure and retry state');
