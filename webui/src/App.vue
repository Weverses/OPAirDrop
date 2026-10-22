<script setup lang="ts">
import {ref,computed,watch,onMounted,onBeforeUnmount} from 'vue';
import {MiuixSnackbarHost,MiuixScrollArea,MiuixIcon,MiuixNavigationBar,MiuixTopAppBar,MiuixIconButton,MiuixButton,MiuixCard,MiuixSmallTitle,MiuixText,MiuixInput,MiuixBasicComponent,MiuixRadioButtonPreference,showSnackbar} from 'miuix-vue';
import {ScreenMirroring,Settings} from 'miuix-vue/icons';
import {API,type Status,type HistoryData,type SpeedRecord,type ShareMode} from './api';
import AboutView from './components/AboutView.vue';
import LicensesView from './components/LicensesView.vue';
const tab=ref(0),state=ref<Status|null>(null),busy=ref(false),error=ref(''),updated=ref('');
let statusEpoch=0,lastStatusAt=0;
let statusRequest:Promise<boolean>|null=null,historyRequest:Promise<boolean>|null=null;
const manualBusy=ref(false);
const history=ref<HistoryData|null>(null);
const navItems=[{label:'状态'},{label:'设置'}];
const icons=[ScreenMirroring,Settings];
const settingsView=ref<'main'|'about'|'logs'|'name'|'directory'|'sharing'|'licenses'>('main'),logs=ref('点击刷新读取模块日志。'),logBusy=ref(false),exportBusy=ref(false),exportedPath=ref('');
const shareModeNames:Record<ShareMode,string>={quickshare:'Quick Share',oshare:'OShare',both:'二者共存'};
const shareModeOptions:{value:ShareMode;title:string;summary:string}[]=[
 {value:'quickshare',title:'Quick Share',summary:'使用 Quick Share 与 Apple 收发，关闭 OShare 的 AirDrop 桥接和后台接收'},
 {value:'oshare',title:'OShare',summary:'使用系统互传与 Apple 收发，关闭 Quick Share 的 AirDrop 入口'},
 {value:'both',title:'二者共存',summary:'两个入口均可发送，AirDrop 后台接收由 OShare 展示'},
];
const oshareEnabled=computed(()=>state.value?.share_mode_active!=='quickshare');
const shareModeSummary=computed(()=>!state.value?'读取中':state.value.share_mode===state.value.share_mode_active?shareModeNames[state.value.share_mode]:`${shareModeNames[state.value.share_mode]}，等待当前任务结束后切换（当前：${shareModeNames[state.value.share_mode_active]}）`);
const nameDraft=ref('');
const directoryDraft=ref('');
const nameLength=computed(()=>[...nameDraft.value.trim()].length);
const pageTitle=computed(()=>tab.value===0?'AirDrop':settingsView.value==='about'?'关于':settingsView.value==='licenses'?'开放源代码许可':settingsView.value==='logs'?'日志':settingsView.value==='name'?'AirDrop 名称':settingsView.value==='directory'?'接收文件保存位置':settingsView.value==='sharing'?'AirDrop 分享入口':'设置');
const live=computed(()=>history.value?.live);
const recorderOn=computed(()=>history.value?.recorder==='on');
const recording=computed(()=>recorderOn.value&&live.value?.state==='recording');
const records=computed(()=>{
 const completed=[...(history.value?.history??[])].reverse(),current=live.value;
 if(current?.state==='recording'&&current.id&&!completed.some(r=>r.id===current.id)&&(current.kind==='transfer'||(current.sent_bytes??0)+(current.received_bytes??0)>=65536))return [current as SpeedRecord,...completed];
 return completed;
});
const serviceNames:Record<string,string>={running:'运行中',stopped:'未启动',idle:'未运行',not_installed:'未安装'};
function speed(value=0){return (value/1000000).toFixed(2);}
function peak(value:number,average:number){return value<average?'≥ '+speed(average):speed(value);}
function size(value=0){return value>=1e9?(value/1e9).toFixed(2)+' GB':value>=1e6?(value/1e6).toFixed(2)+' MB':(value/1000).toFixed(1)+' KB';}
function time(epoch:number){return new Date(epoch*1000).toLocaleString('zh-CN',{hour12:false});}
function acceptStatus(value:Status){state.value=value;lastStatusAt=Date.now();updated.value=new Date(lastStatusAt).toLocaleTimeString('zh-CN',{hour12:false});}
function refresh(force=true):Promise<boolean>{
 if(statusRequest)return statusRequest;
 if(busy.value||(!force&&Date.now()-lastStatusAt<2000))return Promise.resolve(false);
 const epoch=statusEpoch;
 statusRequest=(async()=>{try{const value=await API.status();if(epoch!==statusEpoch)return false;acceptStatus(value);return true;}catch(e){if(epoch===statusEpoch)error.value=e instanceof Error?e.message:String(e);return false;}finally{statusRequest=null;}})();
 return statusRequest;
}
function refreshHistory():Promise<boolean>{
 if(historyRequest)return historyRequest;
 historyRequest=(async()=>{try{history.value=await API.history();return true;}catch(e){error.value=e instanceof Error?e.message:String(e);return false;}finally{historyRequest=null;}})();
 return historyRequest;
}
async function manualRefresh(){
 if(busy.value||manualBusy.value)return;
 manualBusy.value=true;error.value='';
 try{
  if(tab.value===1&&settingsView.value==='logs'){const ok=await readLogs();showSnackbar({message:ok?'日志已刷新':'日志刷新失败'});return;}
  // Finish any automatic reads first, then explicitly fetch a fresh snapshot.
  await Promise.all([statusRequest,historyRequest]);error.value='';
  const results=await Promise.all(tab.value===0?[refresh(),refreshHistory()]:[refresh()]);
  showSnackbar({message:results.every(Boolean)?'已刷新':error.value||'刷新失败，请重试'});
 }finally{manualBusy.value=false;}
}
let timer:ReturnType<typeof setInterval>|undefined;
watch(tab,next=>{settingsView.value='main';refresh(false);if(next===0)refreshHistory();});
function poll(){if(document.hidden||manualBusy.value)return;if(tab.value===0)refreshHistory();if(tab.value===0||settingsView.value==='main'||settingsView.value==='sharing')refresh(false);}
onMounted(()=>{poll();document.addEventListener('visibilitychange',poll);timer=setInterval(poll,500);});
async function readLogs(){if(logBusy.value)return false;logBusy.value=true;try{logs.value=await API.logs()||'暂无日志';return true;}catch(e){logs.value=e instanceof Error?e.message:String(e);return false;}finally{logBusy.value=false;}}
async function exportLogs(){if(exportBusy.value)return;exportBusy.value=true;try{exportedPath.value=await API.exportLogs();showSnackbar({message:'日志已保存到 Download/OPAirShare'});}catch(e){error.value=e instanceof Error?e.message:String(e);showSnackbar({message:error.value});}finally{exportBusy.value=false;}}
function editName(){if(!state.value||busy.value)return;nameDraft.value=state.value.airdrop_name_custom;settingsView.value='name';}
function editDirectory(){if(!state.value||busy.value)return;directoryDraft.value=state.value.receive_directory;settingsView.value='directory';}
async function changeShareMode(mode:ShareMode){if(busy.value||!state.value||mode===state.value.share_mode)return;busy.value=true;statusEpoch++;error.value='';try{acceptStatus(await API.saveShareMode(mode));showSnackbar({message:state.value!.share_mode===state.value!.share_mode_active?'分享入口已切换':'已保存，正在等待当前任务结束后切换'});}catch(e){error.value=e instanceof Error?e.message:String(e);showSnackbar({message:error.value});}finally{busy.value=false;}}
async function saveDirectory(reset=false){if(busy.value||!state.value)return;busy.value=true;statusEpoch++;error.value='';try{acceptStatus(await API.saveDirectory(reset?'':directoryDraft.value));directoryDraft.value=state.value!.receive_directory;showSnackbar({message:reset?'已恢复默认保存位置':'保存位置已更新，下一次保存时生效'});}catch(e){error.value=e instanceof Error?e.message:String(e);showSnackbar({message:error.value});}finally{busy.value=false;}}
async function saveName(reset=false){if(busy.value||!state.value)return;busy.value=true;statusEpoch++;error.value='';try{acceptStatus(await API.saveName(reset?'':nameDraft.value));nameDraft.value=state.value!.airdrop_name_custom;showSnackbar({message:reset?'已恢复默认名称':'名称已保存'});}catch(e){error.value=e instanceof Error?e.message:String(e);showSnackbar({message:error.value});}finally{busy.value=false;}}
function openLogs(){settingsView.value='logs';readLogs();}
onBeforeUnmount(()=>{if(timer)clearInterval(timer);document.removeEventListener('visibilitychange',poll);});
</script>
<template>
<div class="app">
 <MiuixScrollArea class="app-body">
  <MiuixTopAppBar :large="true" :title="pageTitle"><template #navigation><MiuixIconButton v-if="tab===1&&settingsView!=='main'" :aria-label="settingsView==='licenses'?'返回关于':'返回设置'" @click="settingsView=settingsView==='licenses'?'about':'main'"><svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="m15 5-7 7 7 7"/></svg></MiuixIconButton></template><template #actions><MiuixIconButton v-if="settingsView!=='about'&&settingsView!=='licenses'" aria-label="刷新" :aria-busy="manualBusy" :disabled="busy||manualBusy" @click="manualRefresh"><svg :class="{'refresh-spinning':manualBusy}" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M19 9a7.5 7.5 0 1 0 .4 5M19 4v5h-5"/></svg></MiuixIconButton></template></MiuixTopAppBar>
  <div v-if="error" class="banner error" role="alert">{{error}}</div>
  <div :key="`${tab}-${settingsView}`" class="page">
   <template v-if="tab===0">
    <MiuixSmallTitle text="服务状态"/>
    <MiuixCard>
     <MiuixBasicComponent title="AWDL 服务"><template #end><MiuixText>{{state?serviceNames[state.awdl]||'未知':'读取中'}}</MiuixText></template></MiuixBasicComponent>
     <MiuixBasicComponent title="QuickShare"><template #end><MiuixText>{{state?serviceNames[state.quickshare]||'未知':'读取中'}}</MiuixText></template></MiuixBasicComponent>
     <MiuixBasicComponent title="OShare"><template #end><MiuixText>{{state?serviceNames[state.oshare]||'未知':'读取中'}}</MiuixText></template></MiuixBasicComponent>
    </MiuixCard>
    <MiuixSmallTitle text="速度记录"/>
    <MiuixCard><div class="speed-panel">
     <div class="record-heading"><b>实时传输</b><span class="record-badge" :class="{active:recording}">{{recording?'正在记录':recorderOn?'等待传输':'等待服务'}}</span></div>
     <div class="speed-values"><div><span class="direction">↑ 发送</span><div class="speed-number">{{recorderOn?speed(live?.tx_bps):'—'}}</div><span class="speed-unit">MB/s</span></div><div><span class="direction">↓ 接收</span><div class="speed-number">{{recorderOn?speed(live?.rx_bps):'—'}}</div><span class="speed-unit">MB/s</span></div></div>

    </div></MiuixCard>
    <div class="history-heading"><span>传输历史</span><small>{{records.length}}条</small></div>
    <MiuixCard v-if="!records.length"><div class="empty-history">暂无记录</div></MiuixCard>
    <MiuixCard v-for="record in records" :key="record.id" class="history-card"><MiuixBasicComponent :title="time(record.started_at)" ><template #end><MiuixText>{{record.state==='recording'?'进行中 · ':''}}{{record.duration_s.toFixed(1)}}秒</MiuixText></template></MiuixBasicComponent><div class="history-body"><p>发送 {{size(record.sent_bytes)}} · 接收 {{size(record.received_bytes)}}</p><p>峰值 ↑ {{peak(record.peak_tx_bps,record.average_tx_bps)}} · ↓ {{peak(record.peak_rx_bps,record.average_rx_bps)}} MB/s</p><p>平均 ↑ {{speed(record.average_tx_bps)}} · ↓ {{speed(record.average_rx_bps)}} MB/s</p><p v-if="record.peak_tx_bps<record.average_tx_bps||record.peak_rx_bps<record.average_rx_bps">旧记录峰值统计异常，显示已知下限。</p></div></MiuixCard>

    <div class="about">最近刷新 {{updated||'—'}}</div>
   </template>
   <template v-else-if="settingsView==='main'">
    <MiuixSmallTitle text="分享"/>
    <MiuixCard><MiuixBasicComponent title="AirDrop 分享入口" :summary="shareModeSummary" :disabled="busy||!state" clickable @click="settingsView='sharing'"><template #end><span class="entry-arrow">›</span></template></MiuixBasicComponent></MiuixCard>
    <MiuixSmallTitle text="设备"/>
    <MiuixCard><MiuixBasicComponent title="AirDrop 名称" :summary="oshareEnabled?(state?.airdrop_name||'读取中'):'使用 Quick Share 中的设备名称'" :disabled="busy||!state||!oshareEnabled" clickable @click="editName"><template #end><span class="entry-arrow">›</span></template></MiuixBasicComponent></MiuixCard>
    <MiuixSmallTitle text="文件接收"/>
    <MiuixCard><MiuixBasicComponent title="保存位置" :summary="oshareEnabled?(state?(state.receive_directory||'按文件类型保存到默认目录'):'读取中'):'由 Quick Share 管理接收文件'" :disabled="busy||!state||!oshareEnabled" clickable @click="editDirectory"><template #end><span class="entry-arrow">›</span></template></MiuixBasicComponent></MiuixCard>
    <MiuixSmallTitle text="通用"/>
    <MiuixCard><MiuixBasicComponent title="关于" clickable @click="settingsView='about'"><template #end><span class="entry-arrow">›</span></template></MiuixBasicComponent><MiuixBasicComponent title="查看日志" clickable @click="openLogs"><template #end><span class="entry-arrow">›</span></template></MiuixBasicComponent><MiuixBasicComponent title="导出日志" :summary="exportBusy?'正在导出…':undefined" :disabled="exportBusy" clickable @click="exportLogs"><template #end><span class="entry-arrow">↗</span></template></MiuixBasicComponent></MiuixCard>
    <div v-if="exportedPath" class="export-result"><b>日志已导出</b><p>{{exportedPath}}</p></div>
   </template>
   <template v-else-if="settingsView==='sharing'">
    <MiuixSmallTitle text="与 Apple 设备收发"/>
    <MiuixCard><MiuixRadioButtonPreference v-for="option in shareModeOptions" :key="option.value" :title="option.title" :summary="option.summary" :model-value="state?.share_mode===option.value" :disabled="busy||!state" @select="changeShareMode(option.value)"/></MiuixCard>
   </template>
   <template v-else-if="settingsView==='name'">
    <MiuixSmallTitle text="附近设备看到的名称"/>
    <MiuixCard><form class="name-form" @submit.prevent="saveName()">
     <MiuixInput v-model="nameDraft" label="显示名称" :placeholder="state?.airdrop_name_default" :single-line="true" :disabled="busy"/>
     <div class="name-caption"><span>留空使用默认名称：{{state?.airdrop_name_default}}</span><span :class="{'name-limit':nameLength>64}">{{nameLength}}/64</span></div>
     <p class="name-hint">保存后更新附近 Apple 设备看到的名称；传输中将在结束后更新。</p>
     <div class="name-actions"><MiuixButton :disabled="busy||!state||(!nameDraft&&!state.airdrop_name_custom)" @click="saveName(true)">恢复默认</MiuixButton><MiuixButton :disabled="busy||!state||nameLength>64" @click="saveName()">{{busy?'正在保存…':'保存'}}</MiuixButton></div>
    </form></MiuixCard>
   </template>
   <template v-else-if="settingsView==='directory'">
    <MiuixSmallTitle text="AirDrop 接收文件"/>
    <MiuixCard><form class="name-form" @submit.prevent="saveDirectory()">
     <MiuixInput v-model="directoryDraft" label="保存目录" placeholder="Download/OPAirShare" :single-line="true" :disabled="busy"/>
     <p class="name-hint">填写手机内部存储中 Download 或 Documents 下的目录，例如 Download/AirDrop。设置后所有接收文件统一保存到此处，下一次保存时生效。</p>
     <p class="name-hint">留空恢复默认：图片保存在 Pictures/OPAirShare，视频保存在 Movies/OPAirShare，其他文件保存在 Download/OPAirShare。已有文件不会移动。</p>
     <div class="name-actions"><MiuixButton :disabled="busy||!state||(!directoryDraft&&!state.receive_directory)" @click="saveDirectory(true)">恢复默认</MiuixButton><MiuixButton :disabled="busy||!state" @click="saveDirectory()">{{busy?'正在保存…':'保存'}}</MiuixButton></div>
    </form></MiuixCard>
   </template>
   <template v-else-if="settingsView==='about'">
    <AboutView :version="state?.version" @licenses="settingsView='licenses'"/>
   </template>
   <template v-else-if="settingsView==='licenses'">
    <LicensesView/>
   </template>
   <template v-else>
    <MiuixCard><MiuixBasicComponent title="模块日志"><template #end><MiuixButton :disabled="logBusy" @click="readLogs">{{logBusy?'读取中':'刷新'}}</MiuixButton></template></MiuixBasicComponent></MiuixCard>
    <MiuixCard class="log-card"><pre>{{logs}}</pre></MiuixCard>
    <div class="log-export"><MiuixButton class="full-width" :disabled="exportBusy" @click="exportLogs">{{exportBusy?'正在导出…':'导出日志到 Download'}}</MiuixButton></div>
    <div v-if="exportedPath" class="export-result"><b>日志已导出</b><p>{{exportedPath}}</p></div>
   </template>
  </div>
 </MiuixScrollArea>
 <div v-if="settingsView!=='about'&&settingsView!=='licenses'" class="bottom"><MiuixNavigationBar v-model="tab" :items="navItems"><template #icon="{index}"><MiuixIcon :icon="icons[index]" :size="26"/></template></MiuixNavigationBar></div>
</div>
<MiuixSnackbarHost/>
</template>
