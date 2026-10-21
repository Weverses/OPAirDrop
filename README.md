# OPAirShare

为 Oplus Android 设备连接 AirDrop、Quick Share 与 OShare 分享入口。

从系统分享面板选择文件后，OPAirShare 在互传界面显示 Apple 设备，并通过 AWDL 传输文件。项目使用 Mosey 分享服务，配合 Wonder、Qualcomm WLAN 驱动适配和 OShare 桥接。KernelSU 模块负责服务生命周期，WebUI 提供状态、共存设置、速度历史和日志入口。

## 功能

- **系统分享入口**：连接 OShare 与 Mosey provider，映射设备名称、设备类型、发送进度和取消事件。
- **AWDL 无线适配**：通过 Wonder、独立 provider 和 WLAN 驱动覆盖层连接协议服务与无线数据路径。
- **按界面管理会话**：驱动与原生服务驻留，分享界面打开时启动发现，界面关闭且传输结束后释放无线会话。
- **Wi-Fi 共存**：按当前 STA 频率选择同信道或跨 5 GHz 信道策略，并在会话结束后恢复参数。
- **模块 WebUI**：Vue 3 + miuix-vue，包含服务状态、共存开关、后台速度记录、日志查看和导出。

## 适配基线

| 项目 | 当前配置 |
| --- | --- |
| 设备型号 | PJZ110 |
| Android SDK | 37 |
| 内核 | `6.6.118-android15-8-g9bc34d5b0c79-abogki537459655-4k` |
| WLAN 平台 | Qualcomm `sun` / `peach-v2` |
| AWDL | 信道 149，5745 MHz，20 MHz 带宽 |
| 共存 STA 频率 | 5200 MHz（信道 40）、5745 MHz（信道 149） |
| 模块环境 | KernelSU + Magic Mount metamodule |
| OShare 桥接 | LSPosed，作用域为 OShare 与 Mosey |

设备型号、SDK 和内核匹配规则位于 `module/customize.sh` 与 `module/bin/control.sh`。其他设备的适配从内核源码、驱动 ABI 和系统分享服务版本开始。

## 项目结构

```text
OPAirShare/
├── module/           KernelSU 模块与运行脚本
├── webui/            Vue 3 + miuix-vue 界面
├── oshare/           OShare / Mosey 的 LSPosed 桥接工程
├── native/           原生库、socket 助手与 Wi-Fi 会话助手
├── mosey/            传输回调与联网发现的 APK 构建工具
├── kernel/           WLAN 覆盖层、Wonder、provider 与基础补丁
├── tools/            源码检查、原生构建和内核构建入口
├── tests/            设置、速度历史与 WebUI API 回归测试
└── docs/             构建说明与技术实现说明
```

本地依赖放在 `local-inputs/`，组件构建输出放在 `build/`，WebUI 输出放在 `webui/dist/`。这些目录通过 `.gitignore` 管理。

## 开发入口

在仓库根目录执行：

```sh
python3 tools/check.py

cd webui
npm ci
npm run check
npm run build
cd ..

python3 tests/test_settings.py
python3 tests/test_history.py
node tests/test-api.mjs
sh oshare/tests/run.sh
```

原生助手、Android 桥接、Mosey 补丁与内核的构建步骤见 [构建说明](docs/BUILD.md)。

## 使用入口

完成模块装配与安装后：

1. 在 LSPosed 中启用 OShare AirDrop 桥接，选中 `com.coloros.oshare` 和 `com.google.android.mosey`。
2. 将手机连接到上表中的 Wi-Fi 信道，在文件管理器或相册中选择文件并打开系统互传界面。
3. 在 Apple 设备上打开 AirDrop 可见性，在分享列表中选择设备并完成接收确认。
4. 从 KernelSU 的模块 WebUI 查看服务状态、速度历史和共存设置。

模块 Action 按钮切换整个服务栈的启动与停止。分享界面退出后的会话释放由后台监督器处理。

## 文档

- [构建说明](docs/BUILD.md)：环境、依赖、组件构建、模块装配与验收流程。
- [技术实现说明](docs/TECHNICAL.md)：服务架构、发现和传输链路、无线适配、会话状态与 WebUI API。
- [SM8750 AWDL 实现](docs/SM8750-AWDL.md)：原厂固件缺少原生 PAS 支持时，如何组合 NDI、WMI RAW TX、monitor 与 native RX 实现 AWDL。
- [平台迁移指南](docs/PORTING.md)：新设备的信息收集、无线后端选择、驱动适配与验证步骤。
- [内核目录说明](kernel/README.md)：源码覆盖层、构建路径与上游基线。
- [OShare 工程说明](oshare/README.md)：Android 桥接构建与测试入口。

## 项目标识

| 用途 | 标识 |
| --- | --- |
| 项目名称与 Java 类前缀 | `OPAirShare` |
| C 符号与文件名前缀 | `opairshare` |
| 宏与环境变量前缀 | `OPAIRSHARE` |
| KernelSU 模块 ID | `opairshare` |
| 运行目录 | `/data/adb/modules/opairshare` |
| 状态目录 | `/data/adb/opairshare` |
| 日志导出目录 | `/sdcard/Download/OPAirShare` |
| 桥接应用 ID | `com.tlsu.oshareairdrop` |

## 开源许可

项目默认采用 **GPLv3**，允许使用、修改和商业分发。分发项目代码或修改版时，保留版权与许可声明，并按协议提供对应源码。

内核适配、部分原生助手和第三方代码使用各自的协议，具体范围见 [开源许可说明](LICENSING.md)。协议全文位于 [LICENSE](LICENSE) 和 [LICENSES](LICENSES/README.md)，组件来源与许可记录见 [第三方声明](THIRD_PARTY_NOTICES.md)。

## 使用与参考项目

### 源码与研究参考

| 项目 | 在本项目中的用途 |
| --- | --- |
| [OnePlusOSS](https://github.com/OnePlusOSS/kernel_manifest) | SM8750 平台内核、Qualcomm WLAN 与 Oplus Wonder 源码基线 |
| [34306/AirDropVivoCN](https://github.com/34306/AirDropVivoCN) | 项目早期研究与部署方案的起点 |
| [thelok1s/mosey-extended](https://github.com/thelok1s/mosey-extended) | KernelSU 模块组织、Mosey 部署与服务接入参考 |
| [FeelLiao/mosey-bada](https://github.com/FeelLiao/mosey-bada) | 原生 Binder 服务接入、桥接与用户态分享架构参考 |
| [seemoo-lab/owl](https://github.com/seemoo-lab/owl) | AWDL 帧格式与频道字段分析参考 |
| [t4t5/omdrop-owl](https://github.com/t4t5/omdrop-owl) | AWDL 时间窗口、固定频道与吞吐优化研究参考 |
| [UvejsGj/Windfall](https://github.com/UvejsGj/Windfall) | 无线重传、接收响应与传输性能分析参考 |
| [luyanci/hybrid-miuix-webui](https://github.com/luyanci/hybrid-miuix-webui) | WebUI 组件布局与界面组织参考 |

### 组件、构建工具与运行环境

| 项目 | 用途 |
| --- | --- |
| [Vue](https://github.com/vuejs/core) | WebUI 应用框架 |
| [miuix-vue](https://github.com/YuKongA/miuix-vue) | WebUI 组件 |
| [Motion for Vue](https://github.com/motiondivision/motion-vue) / [Motion](https://github.com/motiondivision/motion) | miuix-vue 使用的动画依赖 |
| [Vite](https://github.com/vitejs/vite) | WebUI 构建 |
| [Gradle](https://github.com/gradle/gradle) | Android 桥接工程构建 |
| [libxposed API](https://github.com/libxposed/api) | OShare / Mosey 桥接使用的 Xposed 接口 |
| [LSPosed](https://github.com/LSPosed/LSPosed) | Android 桥接运行环境 |
| [KernelSU](https://github.com/tiann/KernelSU) | 模块启动、挂载与 WebUI 运行环境 |

各项目的贡献归原作者所有。引用代码和依赖的许可文件随仓库保留，详细记录见 [第三方声明](THIRD_PARTY_NOTICES.md)。

## 独立项目说明

OPAirShare 是独立社区项目，与 **Google、Apple、Oplus（包括 OPPO、OnePlus）不存在隶属、官方合作或背书关系**。

文中涉及的品牌、产品名称和商标归各自权利人所有，用于说明兼容对象、技术来源和运行环境。项目代码的开源许可与这些品牌、产品及商标的权属分别适用。

源码提取与迁移记录位于 `docs/migration.json`，本地构建和回归记录位于 `docs/verification.json`。
