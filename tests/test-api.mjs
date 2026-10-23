import ts from '../webui/node_modules/typescript/lib/typescript.js';
import {readFileSync} from 'node:fs';
import assert from 'node:assert/strict';
const source=readFileSync(new URL('../webui/src/api.ts',import.meta.url),'utf8');
const js=ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2020,module:ts.ModuleKind.ES2020}}).outputText;
const api=await import('data:text/javascript;base64,'+Buffer.from(js).toString('base64'));
const state={share_mode:'both',share_mode_active:'both',coexist:'on',scope:'auto',profile:'fast',autostart:'on',ready:'off',editable:'on',airdrop_name:'OnePlus 13',airdrop_name_default:'OnePlus 13',airdrop_name_custom:'',receive_directory:''};
let calls=[];globalThis.window={ksu:{exec(command,options,callback){calls.push(command);queueMicrotask(()=>window[callback](0,JSON.stringify(state),''));}}};
assert.equal((await api.API.status()).scope,'auto');
let count=calls.length;await assert.rejects(()=>api.API.save({...state,profile:'fast; echo wrong'}),/允许范围/);assert.equal(calls.length,count);
count=calls.length;await api.API.save(state);assert.match(calls.at(-1),/apply on auto fast on$/);assert.equal(calls.length-count,1);
window.ksu.exec=(command,options,callback)=>queueMicrotask(()=>window[callback](8,'','活动会话禁止修改'));
await assert.rejects(()=>api.API.save(state),e=>e.code===8&&e.message.includes('活动会话'));
window.ksu.exec=(command,options,callback)=>queueMicrotask(()=>window[callback](0,JSON.stringify({...state,profile:'vht'}),''));
await assert.rejects(()=>api.API.save(state),/不一致/);
window.ksu.exec=(command,options,callback)=>queueMicrotask(()=>window[callback](0,'not json',''));
await assert.rejects(()=>api.API.status(),/无法解析/);
assert.equal(Object.keys(window).filter(k=>k.startsWith('opairshare_miuix_')).length,0);
console.log('API adapter passed: status, allowlist, save readback, errno, malformed response, callback cleanup.');
window.ksu.exec=(command,options,callback)=>{calls.push(command);queueMicrotask(()=>window[callback](0,JSON.stringify(state),''));};
window.ksu.exec=(command,options,callback)=>{calls.push(command);queueMicrotask(()=>window[callback](0,JSON.stringify({path:'/sdcard/Download/OPAirShare/airdrop-test.txt'}),''));};
assert.equal(await api.API.exportLogs(),'/sdcard/Download/OPAirShare/airdrop-test.txt');assert.ok(calls.at(-1).endsWith('export-logs'));
window.ksu.exec=(command,options,callback)=>queueMicrotask(()=>window[callback](0,JSON.stringify({path:'/wrong/path'}),''));
await assert.rejects(()=>api.API.exportLogs(),/路径无效/);
console.log('Log export command and path validation passed.');

// The public adapter no longer offers a coexistence-off operation.
assert.equal(api.API.setCoexist,undefined);
for(const config of [{...state,coexist:'off'},{...state,scope:'same149'}]){
 count=calls.length;await assert.rejects(()=>api.API.save(config),/允许范围/);assert.equal(calls.length,count);
}

// Names cross the root shell as validated hex, including quotes and shell punctuation.
const name="我的 OnePlus 📱 ' $(touch nope); `id`";
assert.equal(api.nameHex(name),Buffer.from(name).toString('hex'));
for(const value of ['x'.repeat(65),'📱'.repeat(65),'a\nb','\u0000','\u0080','\u2028','\ud800'])assert.throws(()=>api.nameHex(value));
assert.equal(api.nameHex('📱'.repeat(64)).length,512);
window.ksu.exec=(command,options,callback)=>{calls.push(command);if(command.includes('name-set')){state.airdrop_name_custom=name;state.airdrop_name=name;}if(command.endsWith('name-reset')){state.airdrop_name_custom='';state.airdrop_name=state.airdrop_name_default;}queueMicrotask(()=>window[callback](0,JSON.stringify(state),''));};
count=calls.length;await api.API.saveName(name);assert.ok(calls.at(-1).endsWith('name-set '+Buffer.from(name).toString('hex')));assert.equal(calls.length-count,1);
await api.API.saveName('  ');assert.ok(calls.some(c=>c.endsWith('name-reset')));
assert.equal(state.airdrop_name,'OnePlus 13');
count=calls.length;await assert.rejects(()=>api.API.saveName('a\nb'));assert.equal(calls.length,count);
window.ksu.exec=(command,options,callback)=>queueMicrotask(()=>window[callback](0,JSON.stringify(state),''));
await assert.rejects(()=>api.API.saveName('other'),/不一致/);
console.log('AirDrop name: Unicode, shell-safe encoding, limits, reset and readback passed.');

const path='Download/接收 📱/$(touch nope); `id`';
assert.equal(api.directoryHex(path),Buffer.from(path).toString('hex'));
for(const value of ['/sdcard/Download','Android/data','Download/../x','Download/.hidden','Download//x','Download/x/','Download/a\\b','Download/a\nb','Download/\ud800','Download/'+'a'.repeat(201)])assert.throws(()=>api.directoryHex(value));
window.ksu.exec=(command,options,callback)=>{calls.push(command);state.receive_directory=command.endsWith('receive-directory-reset')?'':path;queueMicrotask(()=>window[callback](0,JSON.stringify(state),''));};
await api.API.saveDirectory(path);assert.ok(calls.at(-1).endsWith('receive-directory-set '+Buffer.from(path).toString('hex')));
await api.API.saveDirectory('');assert.ok(calls.at(-1).endsWith('receive-directory-reset'));
count=calls.length;await assert.rejects(()=>api.API.saveDirectory('Download/../x'));assert.equal(calls.length,count);
window.ksu.exec=(command,options,callback)=>queueMicrotask(()=>window[callback](0,JSON.stringify(state),''));
await assert.rejects(()=>api.API.saveDirectory('Documents/Other'),/不一致/);
console.log('Receive directory: validation, shell-safe Unicode, reset and readback passed.');

window.ksu.exec=(command,options,callback)=>{calls.push(command);state.share_mode=command.split(' ').at(-1);queueMicrotask(()=>window[callback](0,JSON.stringify(state),''));};
for(const mode of ['quickshare','oshare','both']){const actual=await api.API.saveShareMode(mode);assert.equal(actual.share_mode,mode);assert.equal(actual.share_mode_active,'both');assert.ok(calls.at(-1).endsWith('share-mode '+mode));}
count=calls.length;await assert.rejects(()=>api.API.saveShareMode('quickshare;id'));assert.equal(calls.length,count);
window.ksu.exec=(command,options,callback)=>queueMicrotask(()=>window[callback](0,JSON.stringify(state),''));
await assert.rejects(()=>api.API.saveShareMode('oshare'),/不一致/);
window.ksu.exec=(command,options,callback)=>queueMicrotask(()=>window[callback](0,JSON.stringify({...state,share_mode_active:'bad'}),''));
await assert.rejects(()=>api.API.status(),/分享入口无法解析/);
console.log('Share mode: allowlist, pending readback, active mode and malformed status passed.');

delete window.ksu;
await assert.rejects(()=>api.API.status(),e=>e.code===-1&&e.message.includes('WebUI 宿主'));
assert.equal(Object.keys(window).filter(k=>k.startsWith('opairshare_miuix_')).length,0);
console.log('Missing WebUI bridge reports a manager-independent host requirement.');
