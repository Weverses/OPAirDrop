<script setup lang="ts">
import {ref,computed,watch,onMounted,onBeforeUnmount} from 'vue';
import {MiuixSnackbarHost,MiuixScrollArea,MiuixIcon,MiuixNavigationBar,MiuixTopAppBar,MiuixIconButton,MiuixButton,MiuixCard,MiuixSmallTitle,MiuixText,MiuixSwitch,MiuixBasicComponent,showSnackbar} from 'miuix-vue';
import {ScreenMirroring,Settings} from 'miuix-vue/icons';
import {API,type Status,type HistoryData} from './api';
const tab=ref(0),state=ref<Status|null>(null),busy=ref(false),error=ref(''),updated=ref('');
const history=ref<HistoryData|null>(null),historyBusy=ref(false);
const navItems=[{label:'状态'},{label:'设置'}];
const icons=[ScreenMirroring,Settings];
const settingsView=ref<'main'|'about'|'logs'>('main'),logs=ref('点击刷新读取模块日志。'),logBusy=ref(false),exportBusy=ref(false),exportedPath=ref('');
const pageTitle=computed(()=>tab.value===0?'AirDrop':settingsView.value==='about'?'关于':settingsView.value==='logs'?'日志':'设置');
const editable=computed(()=>!!state.value&&state.value.editable==='on'&&!busy.value);
const live=computed(()=>history.value?.live);
const recorderOn=computed(()=>history.value?.recorder==='on');
const recording=computed(()=>recorderOn.value&&live.value?.state==='recording');
const records=computed(()=>[...(history.value?.history??[])].reverse());
const serviceNames:Record<string,string>={running:'运行中',stopped:'未启动',idle:'未运行',not_installed:'未安装'};
function speed(value=0){return (value/1000000).toFixed(2);}
function size(value=0){return value>=1e9?(value/1e9).toFixed(2)+' GB':value>=1e6?(value/1e6).toFixed(2)+' MB':(value/1000).toFixed(1)+' KB';}
function time(epoch:number){return new Date(epoch*1000).toLocaleString('zh-CN',{hour12:false});}
async function refresh(){if(busy.value)return;busy.value=true;error.value='';try{state.value=await API.status();updated.value=new Date().toLocaleTimeString('zh-CN',{hour12:false});}catch(e){error.value=e instanceof Error?e.message:String(e);}finally{busy.value=false;}}
async function changeCoexist(on:boolean){if(!editable.value)return;busy.value=true;error.value='';try{state.value=await API.setCoexist(on);showSnackbar({message:on?'共存已开启':'共存已关闭'});}catch(e){error.value=e instanceof Error?e.message:String(e);showSnackbar({message:error.value});}finally{busy.value=false;}}
async function refreshHistory(){if(historyBusy.value)return;historyBusy.value=true;try{history.value=await API.history();}catch(e){error.value=e instanceof Error?e.message:String(e);}finally{historyBusy.value=false;}}
let timer:ReturnType<typeof setInterval>|undefined;
watch(tab,next=>{settingsView.value='main';if(next===0)refreshHistory();});
onMounted(()=>{refresh();refreshHistory();timer=setInterval(()=>{if(tab.value===0&&!document.hidden)refreshHistory();},2000);});
async function readLogs(){if(logBusy.value)return;logBusy.value=true;try{logs.value=await API.logs()||'暂无日志';}catch(e){logs.value=e instanceof Error?e.message:String(e);}finally{logBusy.value=false;}}
async function exportLogs(){if(exportBusy.value)return;exportBusy.value=true;try{exportedPath.value=await API.exportLogs();showSnackbar({message:'日志已保存到 Download/OPAirShare'});}catch(e){error.value=e instanceof Error?e.message:String(e);showSnackbar({message:error.value});}finally{exportBusy.value=false;}}
function openLogs(){settingsView.value='logs';readLogs();}
onBeforeUnmount(()=>{if(timer)clearInterval(timer);});
</script>
<template>
<div class="app">
 <MiuixScrollArea class="app-body">
  <MiuixTopAppBar :large="true" :title="pageTitle"><template #navigation><MiuixIconButton v-if="tab===1&&settingsView!=='main'" aria-label="返回设置" @click="settingsView='main'"><svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="m15 5-7 7 7 7"/></svg></MiuixIconButton></template><template #actions><MiuixIconButton aria-label="刷新" :disabled="busy||historyBusy" @click="tab===1&&settingsView==='logs'?readLogs():refresh();tab===0&&refreshHistory()"><svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M19 9a7.5 7.5 0 1 0 .4 5M19 4v5h-5"/></svg></MiuixIconButton></template></MiuixTopAppBar>
  <div v-if="error" class="banner error" role="alert">{{error}}</div>
  <Transition name="page" mode="out-in"><div :key="`${tab}-${settingsView}`" class="page">
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
    <MiuixCard v-for="record in records" :key="record.id" class="history-card"><MiuixBasicComponent :title="time(record.started_at)" ><template #end><MiuixText>{{record.duration_s.toFixed(1)}}秒</MiuixText></template></MiuixBasicComponent><div class="history-body"><p>发送 {{size(record.sent_bytes)}} · 接收 {{size(record.received_bytes)}}</p><p>峰值 ↑ {{speed(record.peak_tx_bps)}} · ↓ {{speed(record.peak_rx_bps)}} MB/s</p><p>平均 ↑ {{speed(record.average_tx_bps)}} · ↓ {{speed(record.average_rx_bps)}} MB/s</p></div></MiuixCard>

    <div class="about">最近刷新 {{updated||'—'}}</div>
   </template>
   <template v-else-if="settingsView==='main'">
    <MiuixSmallTitle text="连接"/>
    <MiuixCard><MiuixBasicComponent title="与 Wi-Fi 共存"><template #end><MiuixSwitch :model-value="state?.coexist==='on'" :disabled="!editable" aria-label="与Wi-Fi共存" @update:model-value="changeCoexist($event)"/></template></MiuixBasicComponent></MiuixCard>
    <MiuixSmallTitle text="通用"/>
    <MiuixCard><MiuixBasicComponent title="关于" clickable @click="settingsView='about'"><template #end><span class="entry-arrow">›</span></template></MiuixBasicComponent><MiuixBasicComponent title="查看日志" clickable @click="openLogs"><template #end><span class="entry-arrow">›</span></template></MiuixBasicComponent><MiuixBasicComponent title="导出日志" :summary="exportBusy?'正在导出…':undefined" :disabled="exportBusy" clickable @click="exportLogs"><template #end><span class="entry-arrow">↗</span></template></MiuixBasicComponent></MiuixCard>
    <div v-if="exportedPath" class="export-result"><b>日志已导出</b><p>{{exportedPath}}</p></div>
   </template>
   <template v-else-if="settingsView==='about'">
    <MiuixCard><div class="about-page"><h2>OPAirShare AirDrop</h2><p>{{state?.version||'版本读取中'}}</p><p>QuickShare · OShare · AWDL</p></div></MiuixCard>
    <MiuixSmallTitle text="界面"/><MiuixCard><MiuixBasicComponent title="Vue + miuix-vue"/><MiuixBasicComponent title="参考项目" summary="luyanci / hybrid-miuix-webui"/></MiuixCard>

   </template>
   <template v-else>
    <MiuixCard><MiuixBasicComponent title="模块日志"><template #end><MiuixButton :disabled="logBusy" @click="readLogs">{{logBusy?'读取中':'刷新'}}</MiuixButton></template></MiuixBasicComponent></MiuixCard>
    <MiuixCard class="log-card"><pre>{{logs}}</pre></MiuixCard>
    <div class="log-export"><MiuixButton class="full-width" :disabled="exportBusy" @click="exportLogs">{{exportBusy?'正在导出…':'导出日志到 Download'}}</MiuixButton></div>
    <div v-if="exportedPath" class="export-result"><b>日志已导出</b><p>{{exportedPath}}</p></div>
   </template>
  </div></Transition>
 </MiuixScrollArea>
 <div class="bottom"><MiuixNavigationBar v-model="tab" :items="navItems"><template #icon="{index}"><MiuixIcon :icon="icons[index]" :size="26"/></template></MiuixNavigationBar></div>
</div>
<MiuixSnackbarHost/>
</template>
