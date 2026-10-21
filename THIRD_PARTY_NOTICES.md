# 第三方声明

本文件列出仓库包含或构建时使用的主要第三方代码。原文件中的版权和许可声明随代码保留。

## Qualcomm WLAN 与 Linux Foundation 代码

- 位置：`kernel/driver/` 和 `kernel/patches/` 中的厂商驱动代码。
- 来源：OnePlusOSS 公布的平台内核与厂商模块源码。
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
