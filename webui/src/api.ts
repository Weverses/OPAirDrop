export type Toggle='on'|'off';
export type Scope='auto'|'same149';
export type Profile='fast'|'vht'|'on'|'off';
export interface Config {coexist:Toggle;scope:Scope;profile:Profile;autostart:Toggle}
export interface Status extends Config {version:string;ready:Toggle;editable:Toggle;session:Toggle;frequency:string;channel:string;network:string;radio:string;matcher:string;pending:string;phy:string;mcs:string;nss:string;batch:string;collect:string;callback:Toggle;awdl:string;quickshare:string;oshare:string}
const allowed={coexist:['on','off'],scope:['auto','same149'],profile:['fast','vht','on','off'],autostart:['on','off']};
export class ApiError extends Error {constructor(public code:number,message:string){super(message)}}
const command='/system/bin/sh /data/adb/modules/opairshare/bin/settings.sh';
const historyCommand='/system/bin/sh /data/adb/modules/opairshare/bin/speed-history.sh status';
let serial=0;
export function validate(config:Config):void {for(const k of Object.keys(allowed) as (keyof Config)[]) if(!allowed[k].includes(config[k]))throw new ApiError(2,'设置值超出允许范围');}
export function configOf(s:Config):Config{return {coexist:s.coexist,scope:s.scope,profile:s.profile,autostart:s.autostart};}
export function equal(a:Config,b:Config):boolean{return Object.keys(allowed).every(k=>a[k as keyof Config]===b[k as keyof Config]);}
async function exec(args:string):Promise<string>{
 const bridge=(window as any).ksu;
 if(typeof bridge?.exec!=='function')throw new ApiError(-1,'请从 KernelSU → 模块 → 本模块 WebUI 打开');
 return new Promise((resolve,reject)=>{
  const key=`opairshare_miuix_${Date.now()}_${serial++}`;
  const timer=setTimeout(()=>{delete (window as any)[key];reject(new ApiError(-2,'管理器响应超时，保存结果未确认，请刷新后核对。'));},20000);
  (window as any)[key]=(errno:number,stdout:string,stderr:string)=>{clearTimeout(timer);delete (window as any)[key];if(errno!==0)reject(new ApiError(errno,stderr||stdout||'模块操作失败'));else resolve(stdout);};
  try{bridge.exec(args==='history'?historyCommand:`${command} ${args}`,'{}',key);}catch(e){clearTimeout(timer);delete (window as any)[key];reject(e);}
 });
}
export const API={
 async status():Promise<Status>{let data;const text=await exec('status');try{data=JSON.parse(text)}catch{throw new ApiError(-3,'模块状态无法解析，请确认配套后端版本')};validate(data);return data;},
 async save(config:Config):Promise<Status>{validate(config);await exec(`apply ${config.coexist} ${config.scope} ${config.profile} ${config.autostart}`);const actual=await API.status();if(!equal(actual,config))throw new ApiError(-4,'读回设置与保存值不一致，请核对');return actual;},
 async setCoexist(on:boolean):Promise<Status>{const current=await API.status();return API.save({...configOf(current),coexist:on?'on':'off'});},
 async history():Promise<HistoryData>{const value=JSON.parse(await exec('history'));if(!Array.isArray(value.history)||!value.live)throw new ApiError(-5,'速度记录无法解析');return value;},
 async exportLogs():Promise<string>{const data=JSON.parse(await exec('export-logs'));if(typeof data.path!=='string'||!data.path.startsWith('/sdcard/Download/OPAirShare/'))throw new ApiError(-6,'日志导出路径无效');return data.path;},
 logs():Promise<string>{return exec('logs');}
};

export interface SpeedRecord {id:string;started_at:number;duration_s:number;sent_bytes:number;received_bytes:number;peak_tx_bps:number;peak_rx_bps:number;average_tx_bps:number;average_rx_bps:number;tx_bps:number;rx_bps:number;state:string;kind:string}
export interface HistoryData {recorder:Toggle;live:Partial<SpeedRecord>;history:SpeedRecord[]}
