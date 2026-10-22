import ts from '../webui/node_modules/typescript/lib/typescript.js';
import {ref,computed} from '../webui/node_modules/vue/dist/vue.cjs.js';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';

// Exercise the actual setup functions with delayed bridge replies and a fake clock.
const source=readFileSync(new URL('../webui/src/App.vue',import.meta.url),'utf8').match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1].replace(/^import .*;\n/gm,'');
const pending={status:[],history:[]},mounted=[],messages=[];
let now=10000,interval;
const context={ref,computed,watch(){},onMounted(fn){mounted.push(fn);},onBeforeUnmount(){},ScreenMirroring:{},Settings:{},Error,
 Date:class extends Date{static now(){return now;}},
 document:{hidden:false,addEventListener(){},removeEventListener(){}},setInterval(fn,ms){interval=ms;return 1;},clearInterval(){},
 showSnackbar(value){messages.push(value.message);},
 API:{status(){return new Promise((resolve,reject)=>pending.status.push({resolve,reject}));},history(){return new Promise((resolve,reject)=>pending.history.push({resolve,reject}));},async logs(){return 'new log';}}
};
const js=ts.transpileModule(source+'\nglobalThis.app={poll,manualRefresh,history,records,manualBusy,tab,settingsView,logs,peak};',{compilerOptions:{target:ts.ScriptTarget.ES2020,module:ts.ModuleKind.None}}).outputText;
vm.runInNewContext(js,context);
const app=context.app,tick=()=>new Promise(resolve=>setImmediate(resolve));
const record={id:'same',started_at:1,duration_s:1,sent_bytes:1000000,received_bytes:0,peak_tx_bps:1000000,peak_rx_bps:0,average_tx_bps:1000000,average_rx_bps:0,state:'complete',kind:'transfer'};
const snapshot=(records=[],live={state:'waiting'})=>({recorder:'on',live,history:records});
mounted.forEach(fn=>fn());assert.equal(interval,500);
assert.equal(pending.status.length,1);assert.equal(pending.history.length,1);
const manual=app.manualRefresh();assert.equal(app.manualBusy.value,true);
app.poll();assert.equal(pending.status.length,1);assert.equal(pending.history.length,1);
pending.status.shift().resolve({});pending.history.shift().resolve(snapshot([record]));await tick();
// A click during polling must issue another read after the earlier replies.
assert.equal(pending.status.length,1);assert.equal(pending.history.length,1);
pending.status.shift().resolve({});pending.history.shift().resolve(snapshot([{...record,sent_bytes:2000000}]));await manual;
assert.equal(app.manualBusy.value,false);assert.equal(app.history.value.history[0].sent_bytes,2000000);assert.equal(messages.at(-1),'已刷新');
app.poll();app.poll();assert.equal(pending.history.length,1);assert.equal(pending.status.length,0);
pending.history.shift().resolve(snapshot([record],{...record,id:'active',state:'recording'}));await tick();
assert.equal(app.records.value.length,2);assert.equal(app.records.value[0].id,'active');
app.poll();pending.history.shift().resolve(snapshot([record],{...record,state:'recording'}));await tick();assert.equal(app.records.value.length,1);
context.document.hidden=true;app.poll();assert.equal(pending.history.length,0);context.document.hidden=false;
now+=2000;const failed=app.manualRefresh();await tick();pending.status.shift().reject(new Error('bridge failed'));pending.history.shift().reject(new Error('history failed'));await failed;
assert.equal(app.manualBusy.value,false);assert.match(messages.at(-1),/failed/);
app.tab.value=1;app.settingsView.value='logs';await app.manualRefresh();assert.equal(app.logs.value,'new log');assert.equal(messages.at(-1),'日志已刷新');
assert.equal(app.peak(1791381,3363767),'≥ 3.36');assert.equal(app.peak(5000000,3000000),'5.00');
console.log('WebUI refresh passed: cadence, queued manual reads, in-flight deduplication, same-ID updates, active history, visibility, errors, logs and legacy peak bounds.');
