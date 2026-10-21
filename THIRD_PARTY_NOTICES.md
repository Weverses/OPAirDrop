# 第三方声明

本文件列出仓库包含或构建时使用的主要第三方代码。原文件中的版权和许可声明随代码保留。

## 研究与部署参考

| 项目 | 参考内容 |
| --- | --- |
| [34306/AirDropVivoCN](https://github.com/34306/AirDropVivoCN) | 初期研究工程与 Android AirDrop 部署方案 |
| [thelok1s/mosey-extended](https://github.com/thelok1s/mosey-extended) | 模块组织、Mosey 部署与服务接入 |
| [FeelLiao/mosey-bada](https://github.com/FeelLiao/mosey-bada) | Binder 桥接与用户态分享架构 |
| [seemoo-lab/owl](https://github.com/seemoo-lab/owl) | AWDL 帧格式、频道字段与协议研究 |
| [t4t5/omdrop-owl](https://github.com/t4t5/omdrop-owl) | 固定频道下的时间窗口与吞吐分析 |
| [UvejsGj/Windfall](https://github.com/UvejsGj/Windfall) | 无线重传和接收响应分析 |

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

## 其他构建接口与运行环境

- [Vite](https://github.com/vitejs/vite)：WebUI 构建工具。
- [libxposed API](https://github.com/libxposed/api)：桥接工程使用的 Xposed 接口。
- [LSPosed](https://github.com/LSPosed/LSPosed)：桥接运行环境。
- [KernelSU](https://github.com/tiann/KernelSU)：模块、挂载和 WebUI 运行环境。

完整的使用与参考项目列表同时列在 [README](README.md#使用与参考项目)。
