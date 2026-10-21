# 平台迁移指南

迁移前先读 [SM8750 实现记录](SM8750-AWDL.md)。其中最值得复用的是 PAS / NDI 会话、WMI RAW TX、monitor 接收和 native RX 的接线方式。目标设备使用哪条路径，要根据它的 WLAN 驱动和固件能力选择。

同一 SoC 的设备也可能使用不同的 WLAN 固件、内核分支和系统分享服务版本。开发时以目标设备的源码和运行环境为起点。

## 1. 收集设备信息

先保存设备型号、系统版本、SoC、内核版本和模块列表：

```sh
adb shell getprop ro.product.model
adb shell getprop ro.soc.model
adb shell getprop ro.board.platform
adb shell getprop ro.build.version.sdk
adb shell uname -r
adb shell cat /proc/modules
adb shell cmd wifi status
adb shell pm path com.google.android.mosey
adb shell pm path com.coloros.oshare
```

另外整理以下材料：

| 材料 | 用途 |
| --- | --- |
| 对应系统版本的 common、msm-kernel、厂商模块源码 | 编译目标设备的 WLAN 与 mac80211 |
| WLAN 型号、固件版本和驱动分支 | 确定 vdev、WMI 和数据通路的实现 |
| 目标设备的内核配置和编译工具链 | 对齐页大小、编译选项、KCFI 与模块接口 |
| 原厂 WLAN、mac80211、CNSS 模块与设备日志 | 对照加载、联网和恢复行为 |
| 同次构建的 `Module.symvers` | 检查模块间接口 |
| Mosey APK、原生服务、FFI 和权限配置 | 对齐用户态协议栈 |
| OShare APK 版本 | 调整系统分享桥接 |

工作目录可按设备组织：

```text
local-inputs/<device>/
├── stock-modules/
├── mosey/
├── firmware-info/
└── logs/

build/<device>/
├── kernel/
├── native/
└── module/
```

## 2. 先构建原厂 WLAN 基线

使用设备厂商提供的源码与工具链，先构建原有 WLAN 功能。核对模块依赖、符号接口、内核配置与原厂加载过程，再检查普通 Wi-Fi 的连接和断开。

SM8750 构建入口位于 `tools/kernel/`。迁移到其他平台时，调整源码 manifest、目标名、defconfig、厂商模块路径和输出位置。

| 当前配置 | 迁移时对应的内容 |
| --- | --- |
| `sun perf` | 目标平台的 build target 与 variant |
| `peach-v2` | 目标 WLAN 产品配置 |
| `qca_cld3_peach_v2.ko` | 目标设备实际 WLAN 模块名 |
| `kernel_platform/` 与 `vendor/` | 目标平台源码布局 |
| 原有 `mac80211` 构建目标 | 与目标内核一致的 mac80211 / cfg80211 |

基线通过后，再往同一棵源码树中合入 AWDL 适配，方便逐次比较新增改动的影响。

## 3. 选择无线后端

先在目标源码中查找这些接口：

```sh
rg -n 'QDF_PASSTHRU_MODE|WMI_VDEV_TYPE_WIFI_PASSTHRU' vendor/
rg -n 'WMI_VDEV_TYPE_NDI|NAN_DATA' vendor/
rg -n 'wmi_unified_set_mac_addr_rx_filter|mgmt_tx_wmi' vendor/
rg -n 'rx_decap_type|htt_cmn_pkt_type_raw|osif_rx_mon' vendor/
```

源码中的定义用于查找实现位置；运行时还需要确认固件的能力宣告、命令响应和实际收发结果。

| 目标平台情况 | 适配方向 |
| --- | --- |
| 已有完整 PAS / WonderTap 支持 | 使用平台原生 PAS，接入 Wonder 与 Mosey |
| PAS 缺失，NDI、monitor 和 WMI RAW TX 可用 | 参考 SM8750 的 PAS 主机接口 + NDI 固件后端 |
| 有其他可用的 RAW 802.11 收发接口 | 为该接口实现 Wonder 需要的初始化、设频、收发和停止操作 |
| 使用其他 WLAN 厂商驱动 | 对照厂商自己的 RAW 接口实现无线后端，替换 Qualcomm WMA / DP 相关代码 |

vdev 类型值、WMI 参数布局、HTT 描述符和能力位使用目标平台的定义。SM8750 的 `type=7` 与参数组合对应其原厂固件，迁移时重新确认目标类型及其生命周期。

## 4. 接通 vdev 和 Wonder

### 4.1 合入主机接口

以目标平台代码为主体，按函数合入所需改动。`kernel/source-map.json` 可帮助定位 SM8750 覆盖层原本所在的位置。

主要涉及：

- QDF 与 WLAN mode 枚举，以及对应的构建开关。
- HDD 的 init、stop、set_freq 和 PAS TX 入口。
- DP 接收回调和目标 netdev 注册。
- Wonder 操作表与 component 绑定。
- START、RESTART、STOP、DELETE 的事件分派。

目标平台的结构体、锁、引用计数与对象管理 API 继续沿用该平台实现。

### 4.2 验证兼容会话

采用 NDI 后端时，主机保持 PAS 模式，固件创建目标固件认识的 NDI 类型。随后检查 self-peer 创建、peer-map、START 响应和主机 UP 转换。

SM8750 的适配位于以下位置：

| 改动 | 对照源码 |
| --- | --- |
| 主机 UP 行为 | `kernel/driver/wma_utils.c` |
| START 响应分派 | `kernel/driver/wma_dev_if.c` |
| START / RESTART 和 DELETE 事件 | `kernel/driver/target_if_vdev_mgr_rx_ops.c` |
| HDD 生命周期 | `kernel/driver/wlan_hdd_wondertap.c` |
| Wonder component 绑定 | `kernel/provider/provider.c` |

先完成多轮 CREATE → START → STOP → DELETE，观察 peer、vdev、定时器与接口是否随会话释放，再接入协议流量。

### 4.3 接入 Wonder

将 `kernel/wonder/` 合入平台模块构建图，配置目标 `mac80211`、`cfg80211` 和导出接口。独立 provider 的调用约定与目标 PLD / WLAN 操作表保持一致。

这一阶段的目标是让 Wonder 能正常绑定物理接口，创建自己的无线接口，并调用底层初始化与设频操作。

## 5. 验证 RAW 收发

先固定一个双方都可用的频道，收发生成的诊断帧。独立监听设备记录频率、帧类型、地址、内容和发送完成时间。

### 5.1 发送

按广播数据、管理帧、单播 AWDL 数据的顺序接通 TX。

使用 WMI RAW TX 时，对照目标驱动确认：

- 完整 802.11 帧从哪个 netdev 入口进入。
- radiotap 在哪一层剥离，提交时帧长度如何计算。
- DATA / MGMT 类型、速率、带宽与重试参数的编码。
- 提交需要的 self-peer 和 vdev 归属。
- descriptor、DMA buffer 与完成回调的释放责任。

SM8750 的入口为 `opairshare_pas_mgmt_xmit()`，实现位于 `kernel/driver/wma_opairshare_pas_mgmt.h`。

### 5.2 接收

先接通 monitor 的原始帧回调，确认帧能经过 PAS netdev 进入 Wonder。随后检查本机 AWDL 地址的接收匹配，再建立目标 peer 与 RAW decap。

目标平台接收路径可能需要分别配置：

| 接收环节 | 检查内容 |
| --- | --- |
| monitor | buffer ring、status ring、频道映射、radiotap 和回调 |
| 地址匹配 | 当前 AWDL MAC、vdev、频率与固件响应 |
| RAW peer | 目标地址、peer-map、授权与接收格式 |
| BA | ADDBA 请求、响应、实际 TX 完成与 native BA 状态 |
| 数据 ring | RXDMA / HOST_BUF / REO 配置与最终投递 |

接收配置按目标平台的原生 API 调整。不同硬件代际的 ring、TLV 和 HAL 定义需要在各自驱动中对应实现。

## 6. 接入 Mosey 与系统分享

无线帧收发通过后，准备与目标系统兼容的 Mosey APK、`mosey_server`、FFI 和 Android 权限配置。

先检查 Binder 服务注册、FFI 符号、接口创建和原生发现，再测试 Java provider 的设备发现与文件请求。`native/capability.c` 和 `native/config.c` 中的适配针对当前 Binder / FFI 版本；目标版本变化时，对照其接口重新调整。

Mosey APK 的混淆类名、方法签名与 coroutine 布局随版本变化。迁移 callback 和 discovery 补丁时，按方法行为查找对应调用点：

- 当前传输对象如何保存 callback。
- 异步挂起和恢复时 callback 放在哪里。
- 接收确认、发送请求与传输终态在哪里分派。
- 开始发现时如何处理 Wi-Fi 和频道列表。

OShare 桥接还需要对应宿主 provider 的类型、方法、回调和设备模型，主要修改 `Entry`、`ProviderApi` 与 `OShareBridge`。桌面诊断入口可用于先验证 provider，再接系统分享界面。

## 7. 调整运行脚本

新设备完成底层收发后，再更新模块模板中的设备条件和运行配置。

| 位置 | 需要对应目标设备的内容 |
| --- | --- |
| `module/customize.sh` | 型号、SDK、内核版本、模块环境、文件权限 |
| `module/bin/control.sh` | 加载顺序、模块名、`/proc/modules` 条件、HAL 服务和恢复路径 |
| `module/bin/common.sh` | 模块参数、服务名与状态检查 |
| `module/bin/kernel-identity.sh` | 本次构建与原厂恢复模块的构建标识 |
| `module/bin/callback.sh` | 系统与活动 Mosey APK 的路径和挂载方式 |
| `module/system/` | 权限文件、VINTF 与系统目录布局 |
| `tools/build-native.py` | ABI、NDK 与目标 Android API |

`control.sh` 中还包含当前平台的模块大小条件、参数集合与 HAL 名称。更新设备条件时，一起核对这些实际加载条件。

## 8. 测试共存，再调性能

先在固定 AWDL 频道下完成双向传输、取消、重新发现与连续开关会话。之后再加入普通 Wi-Fi，分别观察同信道与异信道行为。

当前共存配置跨越几个位置：

- `module/bin/coex-session.sh`：STA 频率、会话参数快照与恢复。
- `mosey/discovery/DiscoveryPolicy.java`：允许的频率、连接状态与传输归属。
- `oshare/.../DiscoveryChannels.java`：发现请求的频道选择。
- `module/bin/settings.sh`：状态中的频率和频道映射。
- WLAN policy manager：平台能力、并发表和 MAC 分配。

新增频率时一起调整这些位置，并检查 STA、PAS、monitor 的实际 MAC / 频道分配。当前 SM8750 的 5200 MHz 与 5745 MHz 配置可作为阅读示例。

性能测试从单 peer、单空间流和较小聚合开始。依次调整 PHY、MCS、NSS、A-MSDU 大小和收集窗口，每轮记录端点吞吐、队列状态、重传和接收 ring 的变化。

## 9. 常见调试位置

| 现象 | 优先查看 |
| --- | --- |
| CREATE 时固件断言 | vdev 类型与参数布局、目标固件能力 |
| START 成功后立即异常 | 响应分派、UP 命令和主机状态机 |
| monitor 能看到帧，Wonder 收不到 | radiotap、回调注册、目标 netdev 与频道映射 |
| 广播能发，单播停在提交阶段 | peer / AST、固件 TX 路径、DATA 参数与完成事件 |
| 发现正常，TCP 握手反复重传 | 本机地址匹配、接收响应、目标 peer 与 RAW decap |
| 小包正常，大流接收速度很低 | ADDBA、native RX 配置、REO 与 monitor 投递差异 |
| 传输一段时间后由 App 取消 | per-transfer callback、进度更新与 provider 生命周期 |
| 第一次可用，关闭重进后失败 | matcher clear、真实 DELETE、peer 释放与下一会话重新初始化 |
| 开启共存后普通 Wi-Fi 或 AWDL 丢包 | policy manager、实际 LMAC、频道与 ring 配置 |

完成迁移后，按 [构建说明](BUILD.md#9-检查与验收) 的顺序记录结果，并把设备信息、构建目标、运行参数与已测频道写入对应设备说明。
