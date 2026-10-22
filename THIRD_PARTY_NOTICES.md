# 第三方声明

本文件列出仓库包含或构建时使用的主要第三方代码。原文件中的版权和许可声明随代码保留。

## 研究与部署参考

| 项目 | 参考内容 | 上游许可证 |
| --- | --- | --- |
| [34306/AirDropVivoCN](https://github.com/34306/AirDropVivoCN) | 初期研究工程与 Android AirDrop 部署方案 | GPL-3.0 |
| [thelok1s/mosey-extended](https://github.com/thelok1s/mosey-extended) | 模块组织、Mosey 部署与服务接入 | GPL-2.0 |
| [FeelLiao/mosey-bada](https://github.com/FeelLiao/mosey-bada) | Binder 桥接与用户态分享架构 | 未声明统一许可证，按具体文件声明 |
| [seemoo-lab/owl](https://github.com/seemoo-lab/owl) | AWDL 帧格式、频道字段与协议研究 | GPL-3.0 |
| [t4t5/owly](https://github.com/t4t5/owly)（原 omdrop-owl） | 固定频道下的时间窗口与吞吐分析 | MIT |
| [UvejsGj/Windfall](https://github.com/UvejsGj/Windfall) | 无线重传和接收响应分析 | MIT |

上述项目按各自的上游许可证提供，引用源码的版权与许可声明随相应文件保留。

## Qualcomm WLAN 与 Linux Foundation 代码

- 位置：`kernel/driver/` 和 `kernel/patches/` 中的厂商驱动代码。
- 来源：[OnePlusOSS](https://github.com/OnePlusOSS/kernel_manifest) 公布的平台内核与厂商模块源码。
- 原声明中的权利人包括 The Linux Foundation、Qualcomm Innovation Center, Inc.、Qualcomm Technologies, Inc. 及其关联公司。
- 协议：相关驱动文件使用 ISC，项目添加的内核适配代码使用 GPLv2；具体以源码声明为准。
- 项目修改：PAS / NDI 生命周期、WMI RAW 发送、接收地址匹配、peer、BA、native RX 与 Wi-Fi 共存适配。

[ISC 条款](LICENSES/ISC.txt) · [GPLv2 条款](LICENSES/GPL-2.0-only.txt)

## Wonder

- 位置：`kernel/wonder/`，以及 `kernel/provider/` 中沿用的接口定义。
- 来源：Oplus 平台 Wonder / WonderTap 代码。
- 协议：GPLv2，文件内保留原 SPDX 标识。
- 项目修改：目标平台接入、原始帧收发接口与相关接收元数据处理。

[GPLv2 条款](LICENSES/GPL-2.0-only.txt)

## WebUI 参考项目

- 项目：[luyanci/hybrid-miuix-webui](https://github.com/luyanci/hybrid-miuix-webui)。
- 协议：Apache-2.0。
- 项目修改：采用 Vue 组件布局，接入 OPAirShare 的状态、共存设置、速度历史与日志 API。
- 来源记录：`webui/reference-info.json`。
- 原许可文件：`webui/LICENSE-REFERENCE`。

[Apache 2.0 条款](LICENSES/Apache-2.0.txt)

## WebUI 运行依赖

| 组件 | 版本 | 协议 | 原许可文件 |
| --- | --- | --- | --- |
| Vue | 3.5.38 | MIT | [vue.txt](LICENSES/third-party/vue.txt) |
| @vue/shared | 3.5.38 | MIT | [vue-shared.txt](LICENSES/third-party/vue-shared.txt) |
| @vue/reactivity | 3.5.38 | MIT | [vue-reactivity.txt](LICENSES/third-party/vue-reactivity.txt) |
| @vue/runtime-core | 3.5.38 | MIT | [vue-runtime-core.txt](LICENSES/third-party/vue-runtime-core.txt) |
| @vue/runtime-dom | 3.5.38 | MIT | [vue-runtime-dom.txt](LICENSES/third-party/vue-runtime-dom.txt) |
| miuix-vue | 0.1.1 | Apache-2.0 | [miuix-vue.txt](LICENSES/third-party/miuix-vue.txt) |
| motion-v | 2.8.0 | MIT | [motion-v.txt](LICENSES/third-party/motion-v.txt) |
| motion-dom | 14.3.1 | MIT | [motion-dom.txt](LICENSES/third-party/motion-dom.txt) |
| motion-utils | 14.1.1 | MIT | [motion-utils.txt](LICENSES/third-party/motion-utils.txt) |

这些文件从对应版本的软件包中原样保留。依赖版本由 `webui/package-lock.json` 管理。

## Gradle Wrapper

- 位置：`oshare/gradlew`、`oshare/gradlew.bat` 与 `oshare/gradle/wrapper/`。
- 来源：[Gradle](https://github.com/gradle/gradle)。
- 协议：Apache-2.0。
- 项目配置：Gradle 分发版本设为 8.13。

[Apache 2.0 条款](LICENSES/Apache-2.0.txt)

其他 npm 和 Gradle 构建依赖按各软件包随附的许可声明提供。

## Unlock CN GMS

- 作者：fei-ke；版本：v3.8。
- 来源：用户提供的 `unlock-cn-gms-v3.8.zip` 与 `com.fei_ke.unlockcngms.zip`。
- 作用：移除 `cn.google.services` 与 `com.google.android.feature.services_updater` 两项国行 GMS 限制声明。
- 集成修改：在安装时生成独立配置副本，保留其他 XML 内容；由 KernelSU 的 `post-fs-data.sh` 在开机早期只读挂载，统一记录日志并停用同 ID 的独立旧模块。
- 原说明、原安装脚本及原模块信息保存在 `third-party/unlock-cn-gms/`，两个输入包的 SHA256 见其中的 `provenance.json`。
- 用户提供的压缩包没有附带许可证或许可证声明；不将这些原始材料重新标注为本项目 GPLv3。

## 其他构建接口与运行环境

- [Vite](https://github.com/vitejs/vite)：WebUI 构建工具。
- [libxposed API](https://github.com/libxposed/api)：桥接工程使用的 Xposed 接口。
- [LSPosed](https://github.com/LSPosed/LSPosed)：桥接运行环境。
- [KernelSU](https://github.com/tiann/KernelSU)：模块、挂载和 WebUI 运行环境。

libxposed API 使用 Apache-2.0，LSPosed 使用 GPL-3.0。KernelSU 的 kernel 目录使用 GPL-2.0-only，其余部分使用 GPL-3.0-or-later；Vite 使用 MIT。WebUI 的“开放源代码许可”页面列出各参考项目、组件的许可名称及原文来源，未声明统一许可的材料明确标记。

完整的使用与参考项目列表同时列在 [README](README.md#使用与参考项目)。

## DexKit 与桥接运行依赖

- [DexKit](https://github.com/LuckyPray/DexKit)，Maven 坐标 `org.luckypray:dexkit:2.3.0`，用于 OShare DEX 特征定位。未修改依赖；AAR 随桥接 APK 打包。该版本 POM 声明 LGPL-3.0 与 Apache-2.0，来源：[Maven Central](https://repo.maven.apache.org/maven2/org/luckypray/dexkit/2.3.0/)。
- Kotlin 标准库 `org.jetbrains.kotlin:kotlin-stdlib:2.2.21`，Apache-2.0。
- DexKit 的传递依赖包括 FlatBuffers Java `23.5.26`（Apache-2.0）与 Rikka NDK cxx `1.2.0`（对应依赖的 LLVM / libc++ 许可适用）。具体构建依赖以 Gradle 解析结果为准。

[LGPLv3 条款](LICENSES/LGPL-3.0.txt) · [Apache 2.0 条款](LICENSES/Apache-2.0.txt)。DexKit 对应版本源码与原有版权声明由上游项目提供；项目未把厂商 APK 或分析样本纳入分发。
