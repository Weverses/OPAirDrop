export interface LicenseProject {
 name:string;url:string;usage:string;license:string;note?:string;licenseUrl?:string;
}
export const licenseGroups:{title:string;projects:LicenseProject[]}[]=[
 {title:'参考项目',projects:[
  {name:'OnePlusOSS',url:'https://github.com/OnePlusOSS/kernel_manifest',usage:'内核、Qualcomm WLAN 与 Oplus Wonder 源码基线',license:'GPL-2.0-only · ISC',note:'按源码文件分别授权：内核与 Wonder 使用 GPLv2，相关 Qualcomm WLAN 文件使用 ISC；清单仓库本身未声明统一许可证。',licenseUrl:'https://github.com/Weverses/OPAirDrop/blob/main/THIRD_PARTY_NOTICES.md'},
  {name:'34306 / AirDropVivoCN',url:'https://github.com/34306/AirDropVivoCN',usage:'早期研究与部署方案',license:'GPL-3.0',licenseUrl:'https://github.com/34306/AirDropVivoCN/blob/main/LICENSE'},
  {name:'thelok1s / mosey-extended',url:'https://github.com/thelok1s/mosey-extended',usage:'KernelSU 模块组织、Mosey 部署与服务接入',license:'GPL-2.0',licenseUrl:'https://github.com/thelok1s/mosey-extended/blob/main/LICENSE'},
  {name:'FeelLiao / mosey-bada',url:'https://github.com/FeelLiao/mosey-bada',usage:'原生 Binder 服务接入与用户态分享架构',license:'未声明许可证',note:'上游仓库未提供统一许可证。个别文件的许可声明仅适用于对应文件。'},
  {name:'seemoo-lab / owl',url:'https://github.com/seemoo-lab/owl',usage:'AWDL 帧格式与频道字段分析',license:'GPL-3.0',licenseUrl:'https://github.com/seemoo-lab/owl#license'},
  {name:'t4t5 / owly',url:'https://github.com/t4t5/owly',usage:'原 omdrop-owl；AWDL 时间窗口、固定频道与吞吐优化',license:'MIT',licenseUrl:'https://github.com/t4t5/owly/blob/main/LICENSE'},
  {name:'UvejsGj / Windfall',url:'https://github.com/UvejsGj/Windfall',usage:'无线重传、接收响应与传输性能分析',license:'MIT',licenseUrl:'https://github.com/UvejsGj/Windfall/blob/main/LICENSE'},
  {name:'luyanci / hybrid-miuix-webui',url:'https://github.com/luyanci/hybrid-miuix-webui',usage:'WebUI 组件布局与界面组织',license:'Apache-2.0',licenseUrl:'https://github.com/luyanci/hybrid-miuix-webui/blob/main/LICENSE'},
  {name:'Unlock CN GMS v3.8',url:'https://github.com/Weverses/OPAirDrop/tree/main/third-party/unlock-cn-gms',usage:'fei-ke · 内置国行 GMS 解锁',license:'未声明许可证',note:'集成所用的原始压缩包没有附带许可证；保留原作者信息与来源记录。'},
 ]},
 {title:'组件与运行环境',projects:[
  {name:'Vue',url:'https://github.com/vuejs/core',usage:'WebUI 应用框架 · 3.5.38',license:'MIT',licenseUrl:'https://github.com/vuejs/core/blob/main/LICENSE'},
  {name:'miuix-vue',url:'https://github.com/YuKongA/miuix-vue',usage:'WebUI 组件 · 0.1.1',license:'Apache-2.0',licenseUrl:'https://github.com/YuKongA/miuix-vue/blob/main/LICENSE'},
  {name:'Motion for Vue',url:'https://github.com/motiondivision/motion-vue',usage:'界面动画 · motion-v 2.8.0',license:'MIT',licenseUrl:'https://github.com/motiondivision/motion-vue/blob/main/LICENSE'},
  {name:'Motion',url:'https://github.com/motiondivision/motion',usage:'动画运行依赖 · motion-dom / motion-utils',license:'MIT',licenseUrl:'https://github.com/motiondivision/motion/blob/main/LICENSE.md'},
  {name:'Vite',url:'https://github.com/vitejs/vite',usage:'WebUI 构建工具',license:'MIT',licenseUrl:'https://github.com/vitejs/vite/blob/main/LICENSE'},
  {name:'Gradle',url:'https://github.com/gradle/gradle',usage:'Android 桥接构建工具',license:'Apache-2.0',licenseUrl:'https://github.com/gradle/gradle/blob/master/LICENSE'},
  {name:'DexKit',url:'https://github.com/LuckyPray/DexKit',usage:'OShare 内部入口语义解析 · 2.3.0',license:'LGPL-3.0 · Apache-2.0',note:'2.3.0 的 Maven POM 列出 LGPLv3 与 Apache 2.0，具体组件沿用其原始许可声明。',licenseUrl:'https://repo.maven.apache.org/maven2/org/luckypray/dexkit/2.3.0/dexkit-2.3.0.pom'},
  {name:'libxposed API',url:'https://github.com/libxposed/api',usage:'OShare / Mosey 桥接接口',license:'Apache-2.0',licenseUrl:'https://github.com/libxposed/api#license'},
  {name:'LSPosed',url:'https://github.com/LSPosed/LSPosed',usage:'Android 桥接运行环境',license:'GPL-3.0',licenseUrl:'https://github.com/LSPosed/LSPosed/blob/master/LICENSE'},
  {name:'KernelSU',url:'https://github.com/tiann/KernelSU',usage:'模块、挂载与 WebUI 运行环境',license:'GPL-2.0-only · GPL-3.0-or-later',note:'kernel 目录使用 GPL-2.0-only，其余部分使用 GPL-3.0-or-later。',licenseUrl:'https://github.com/tiann/KernelSU#license'},
  {name:'Kotlin 标准库',url:'https://github.com/JetBrains/kotlin',usage:'Android 桥接运行依赖 · 2.2.21',license:'Apache-2.0',licenseUrl:'https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt'},
  {name:'FlatBuffers Java',url:'https://github.com/google/flatbuffers',usage:'DexKit 传递依赖 · 23.5.26',license:'Apache-2.0',licenseUrl:'https://github.com/google/flatbuffers/blob/master/LICENSE'},
 ]},
];
