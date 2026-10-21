# OPAirDrop

作者：TimelessU

为 Oplus Android 设备连接 AirDrop、Quick Share 与 OShare 分享入口。

从系统分享面板选择文件后，OPAirDrop 在互传界面显示 Apple 设备，并通过 AWDL 传输文件。项目使用 Mosey 分享服务，配合 Wonder、Qualcomm WLAN 驱动适配和 OShare 桥接。KernelSU 模块负责服务生命周期，WebUI 提供状态、共存设置、速度历史和日志入口。

## 功能

- **系统分享入口**：连接 OShare 与 Mosey provider，映射设备名称、设备类型、发送进度和取消事件。
- **接收 AirDrop**：OShare 原生 COUI 邀请、进度、拒绝 / 取消与完成文件入口；模块与共存开启后持续后台可见，不依赖打开分享页面或 OShare 的可见性时限，每次接收手动确认。
- **AWDL 无线适配**：通过 Wonder、独立 provider 和 WLAN 驱动覆盖层连接协议服务与无线数据路径。
- **会话管理**：驱动与原生服务驻留，共存开启时自动保持后台接收；关闭共存后按分享界面与活动传输管理无线会话。
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

1. OPAirDrop 安装时会检测 LSPosed，自动安装、启用随包附带的 OShare 桥接，并为主用户添加 `com.coloros.oshare` 和 `com.google.android.mosey` 作用域。安装后重启一次。未检测到 LSPosed 时跳过桥接安装；安装 LSPosed 后重新安装本模块。
2. 将手机连接到上表中的 Wi-Fi 信道，在文件管理器或相册中选择文件并打开系统互传界面。
3. 在 Apple 设备上打开 AirDrop 可见性，在分享列表中选择设备并完成接收确认。
4. 从 KernelSU 的模块 WebUI 查看服务状态、速度历史和共存设置。

模块服务开机自动启动，分享界面退出后的会话释放由后台监督器处理。

恢复环境安装或 LSPosed 配置尚未初始化时，开机后自动重试，最多三次；若开机后才完成激活，需要再次重启。安装日志为 `/data/adb/opairshare/oshare-install.log`，配置前的数据库备份保存在同一目录。遇到不支持的 LSPosed 数据库结构时，保留原配置并输出错误，可在 LSPosed 中手动启用桥接及两个作用域。

安装器检测到早期使用不同模块 ID 的同项目模块时，会将其停用，重启后由 OPAirDrop 接管，避免同时加载两套驱动。

### AirDrop 显示名称（1.0.6）

WebUI → 设置 → AirDrop 名称可保存自定义名称或恢复默认。默认读取 `ro.vendor.oplus.market.enname`，属性为空时回退到 `ro.product.model`；名称支持最多 64 个字符，保存在系统全局设置中，重启和模块升级后保留。留空或选择“恢复默认”会重新跟随默认属性值。

接收广播与发送身份使用同一名称。没有待确认邀请或活动传输时更新广播；传输中修改将在当前任务结束后应用，Apple 端列表可能需要等待缓存刷新。

### 接收 AirDrop（1.0.11）

开启模块服务及 Wi-Fi 共存，连接受支持的 40 / 149 信道后，AirDrop 会自动保持后台可见，无需打开 OShare / Quick Share；独立于系统互传的接收开关和十分钟时限。Apple 设备选中手机后，在 OShare 弹窗中手动接受。关闭模块或 Wi-Fi 共存后停止新邀请，正在传输或保存的任务继续完成；空闲后台会话可直接在 WebUI 关闭共存。

此模式不校验 Apple 联系人身份，附近的 Apple 设备均可发起邀请。普通文件保存到 `Download/OPAirShare`，图片和视频分别发布到 `Pictures/OPAirShare`、`Movies/OPAirShare`。当前已在 PJZ110 上验证 Mac 发送文本文件、OShare 接收确认、保存与 SHA256 一致；其他类型、多文件、大文件、锁屏和异常恢复尚待真机覆盖。

### 内置国行 GMS 解锁

从 OPAirDrop v1.0.1 起内置 fei-ke 的 Unlock CN GMS v3.8 功能。安装时检测支持的系统、厂商和 Oplus 自定义分区权限配置，生成配置副本，移除 `cn.google.services` 与 `com.google.android.feature.services_updater` 两项限制声明，并在开机早期只读挂载。其他 XML 配置及分区中的原文件保持完整；停用或卸载 OPAirDrop 并重启后恢复原配置。

检测到独立的 `com.fei_ke.unlockcngms` 模块时，安装器在生成内置配置后将其停用。解锁日志为 `/data/adb/opairshare/gms-unlock.log`。Google 服务仍需在系统设置中开启。

### KernelSU 在线更新

从 v1.0.2 起，在 KernelSU 管理器中支持模块更新检查、更新日志查看、下载及覆盖安装。首次从旧版升级需要手动安装 v1.0.2 并重启，之后可在管理器中更新。

更新源为 [GitHub 正式 Releases](https://github.com/Weverses/OPAirDrop/releases)，入口固定为 `https://github.com/Weverses/OPAirDrop/releases/latest/download/update.json`。每个正式版本需附带模块 ZIP、`update.json` 和 `changelog.md`；在线检查按 `versionCode` 比较版本，安装后重启生效。首次正式 Release 上传前，此地址尚不可用。

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
| 项目名称 | `OPAirDrop` |
| Java 类前缀 | `OPAirShare` |
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
| [DexKit](https://github.com/LuckyPray/DexKit) | 按语义特征解析 OShare 的内部混淆入口 |
| [libxposed API](https://github.com/libxposed/api) | OShare / Mosey 桥接使用的 Xposed 接口 |
| [LSPosed](https://github.com/LSPosed/LSPosed) | Android 桥接运行环境 |
| [KernelSU](https://github.com/tiann/KernelSU) | 模块启动、挂载与 WebUI 运行环境 |

各项目的贡献归原作者所有。引用代码和依赖的许可文件随仓库保留，详细记录见 [第三方声明](THIRD_PARTY_NOTICES.md)。

## 独立项目说明

OPAirDrop 是独立社区项目，与 **Google、Apple、Oplus（包括 OPPO、OnePlus）不存在隶属、官方合作或背书关系**。

文中涉及的品牌、产品名称和商标归各自权利人所有，用于说明兼容对象、技术来源和运行环境。项目代码的开源许可与这些品牌、产品及商标的权属分别适用。

源码提取与迁移记录位于 `docs/migration.json`，本地构建和回归记录位于 `docs/verification.json`。
