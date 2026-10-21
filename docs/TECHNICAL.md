# 技术实现说明

Mosey 负责发现与传输协议，OShare 桥接把它接入系统分享界面。Wonder 和 WLAN 驱动负责收发无线帧，KernelSU 脚本管理服务启动、分享会话和退出恢复。

## 1. 总体架构

```mermaid
flowchart TD
    Share[Android 系统分享] --> OShare[OShare 界面与桥接]
    OShare -->|Messenger / Binder| Provider[Mosey Java provider]
    Provider -->|Binder| Server[mosey_server]
    Server --> FFI[libmosey_daemon_ffi.so]
    FFI --> Wonder[Wonder / mac80211]
    Wonder --> Bridge[独立 provider / WLAN passthru]
    Bridge --> Radio[Qualcomm WLAN / 固件]
    Radio <-->|AWDL| Apple[Apple 设备]

    WebUI[KernelSU WebUI] -->|ksu.exec| Settings[settings.sh / speed-history.sh]
    Settings --> State[配置、状态与历史]
    Supervisor[ui-lifecycle.sh] --> Provider
    Supervisor --> Coex[coex-session.sh]
    Coex --> Bridge
```

| 层 | 实现位置 | 职责 |
| --- | --- | --- |
| 分享入口 | `oshare/` | 设备列表、文件 URI、发送、进度、取消与设备展示 |
| Java 服务适配 | `mosey/` | 每次传输的回调归属、联网发现策略和传输状态跟踪 |
| 原生适配 | `native/` | Binder capability、FFI 配置、socket 缓冲和 Wi-Fi 会话元数据 |
| 无线适配 | `kernel/` | Wonder 绑定、passthru 会话、peer、管理帧 TX 与 native RX |
| 生命周期 | `module/bin/` | 驱动加载、provider 激活、共存参数、会话释放与配置持久化 |
| 管理界面 | `webui/` | 服务状态、共存开关、速度历史与日志 |

## 2. 启动与会话生命周期

### 2.1 服务栈启动

`module/service.sh` 等待 Android 开机完成，然后根据持久化的 `autostart` 标记调用 `control.sh start`。

启动流程：

1. 读取设备型号、SDK、内核版本、运行文件与原版 APK 版本。
2. 获取 `stack.flock`，记录本次启动的 boot ID 与 Wi-Fi 状态。
3. 暂停 Wi-Fi HAL，切换 WLAN 模块并加载独立 provider。
4. 恢复 HAL 与 Wi-Fi 状态，加载匹配的 `mac80211` 和 Wonder。
5. 通过 `LD_PRELOAD` 启动 `mosey_server`，等待 Binder 服务注册。
6. 完成原版 provider 认证与 callback APK 挂载。
7. 启动 `ui-lifecycle.sh` 与速度记录进程，写入监督器就绪状态。

Binder 服务名为：

```text
com.google.android.moseyservice.IMoseyService/default
```

启动完成后，驱动和原生服务进入驻留状态。分享界面的打开与关闭决定 provider 及无线会话的使用周期。

### 2.2 分享会话

`ui-lifecycle.sh` 从 `dumpsys activity activities` 识别前台 Activity。它跟踪 Quick Share、OShare 互传界面和桥接应用的诊断界面，并将同一分享任务中的文件选择器纳入会话。

```mermaid
stateDiagram-v2
    [*] --> Resident: 服务栈启动
    Resident --> Preparing: 分享界面打开
    Preparing --> Sharing: 参数快照完成，provider 就绪
    Sharing --> Sharing: 发现设备或传输文件
    Sharing --> Closing: 界面连续关闭，传输结束
    Closing --> Resident: Stop 完成，参数恢复
    Resident --> Stock: Action 停止服务栈
    Stock --> Resident: Action 启动服务栈
```

会话开始时，监督器取得 `settings.flock`，准备共存快照，再调用 `provider.sh ensure`。会话持续期间按周期确认 provider，并应用 Mosey socket 的缓冲配置。

界面连续三次被识别为关闭后，监督器检查 App 的传输状态，释放 Java provider，调用原生 Stop，并等待以下状态：

```text
opairshare_bridge_pending = 0
opairshare_channel_state: accepting=0 vdev=255 freq=0
opairshare_awdl_rx_mac_filter: used=0 armed=0 pending=0 active=0 fault=0
```

随后恢复 Wi-Fi 会话元数据和共存参数，删除 `ui-session`，回到驻留状态。界面关闭时仍有传输，监督器继续持有会话，直到传输终态出现。

### 2.3 服务栈停止

`control.sh stop` 按顺序停止监督器、Java provider 和原生服务，释放会话状态，卸载本次加载的模块，再加载设备原厂 WLAN 与 `mac80211`，恢复 Wi-Fi 状态。

模块 Action 根据当前 boot 的栈归属在 `start` 与 `stop` 间切换。`uninstall.sh` 调用相同的停止流程，并恢复 callback APK 挂载。

## 3. OShare 分享桥接

LSPosed 模块的入口为 `Entry`，分别在两个进程中安装适配：

- `com.coloros.oshare`：安装 `OShareBridge`，连接系统互传界面。
- `com.google.android.mosey`：获取 provider 的 ClassLoader，安装服务、发现频道和设备类型相关的 hook。

### 3.1 命令与事件

主分享链路使用 `BridgeMessenger`。OShare 端传入客户端 `Messenger`，Mosey 端返回命令通道；命令调用 `ProviderApi`，发现与传输事件以 JSON 回送到界面。

源码中还保留了 `BridgeServer` 的本地 socket 通道实现，socket 名为 `oshare_airdrop_bridge`。当前 OShare 与诊断 Activity 的入口均使用 `BridgeMessenger`。

| 类 | 职责 |
| --- | --- |
| `ProviderHolder` | 保存 Mosey provider 使用的 ClassLoader |
| `ProviderApi` | 通过宿主类型和方法调用发现、广告、发送与取消接口 |
| `CallbackFactory` | 创建宿主回调接口的动态代理 |
| `BridgeMessenger` | 跨进程命令与事件通道 |
| `OShareBridge` | 系统分享 Intent、设备列表和传输交互映射 |
| `OutgoingTransfers` | 按目标设备管理发送任务与资源归属 |
| `AirDropPresentation` | 映射设备类型、图标和传输状态 |

### 3.2 文件与任务归属

OShare 桥接从 `ACTION_SEND`、`ACTION_SEND_MULTIPLE` 读取共享 URI、文本和文件显示名称，再向 Mosey 端提交发送请求。

`OutgoingTransfers` 为每次发送建立独立的 `Lease`。目标设备 ID 对应当前任务，任务终态按该 Lease 清理资源。这样同一设备在完成、取消和重试后的任务各自持有自己的回调与文件资源。

### 3.3 发现频道归一化

`DiscoveryChannels.select()` 根据会话 property、STA 连接状态和频率选择请求频道：

| 会话策略 | STA 频率 | AWDL 请求 |
| --- | --- | --- |
| `same149` | 5745 MHz | `[149]` |
| `cross5g` | 5200 MHz | `[149]` |

该逻辑将宿主国家码查询或频道回退结果与固定 149 的无线服务配置对齐，再交给 Mosey 的发现策略处理。

## 4. Mosey APK 适配

### 4.1 每次传输的 callback

callback 构建器修改 `classes2.dex` 中的四个类型：

| 类型 | 改动 |
| --- | --- |
| `dly` | 使用原有的 per-transfer callback 分支 |
| `ccu` | 提供 `opairshareTransferCallback()`，读取该传输对象保存的 callback |
| `cee` | 在 coroutine continuation 中保存 callback |
| `cen` | 在终态重建与异步恢复中使用保存的 callback |

callback 跟随具体传输对象与 continuation，进度、取消和终态更新由同一任务的 callback 接收。

### 4.2 联网发现策略

发现构建器在 `byt` 的 Wi-Fi 策略调用点引入 `DiscoveryPolicy.keepWifi()`。策略读取 `debug.opairshare.discovery`，检查 Wi-Fi 启用状态、`COMPLETED` 连接状态、当前频率及单频道 `[149]` 请求。

验证后的连接信息记录为本次发现会话的 network ID 与频率。network ID 可见时用于比对网络身份，频率和连接状态用于后续发送与接收检查。

`--transfers` 构建选项还会集成以下调用点：

- 原接收请求完成 Accept 校验后，调用 `beginTransfer()`。
- 显式发送前调用 `beginTransfer()`，发送调用返回后记录 `sendResult()`。
- 传输 metadata 更新时调用 `onMetadata()`，在终态释放该目标的传输归属。

`DiscoveryPolicy` 用目标 ID 集合记录活动传输，支持多个目标同时持有状态。`--standby` 选项对应源码中的广告可见性扩展，由独立 property 控制。

### 4.3 原版认证与延迟挂载

`callback-bootstrap.sh` 先恢复原版 APK 视图，激活原版 provider，并等待 GMS 的服务记录出现 Mosey 注册条目。认证完成后，释放 provider，再由 `callback.sh` 挂载构建后的 APK。

callback 管理在系统 mount namespace 中执行，并记录系统 APK 与当前活动 APK 的挂载路径和 boot ID。`app-check.sh` 根据启动阶段检查原版或补丁版 APK。

## 5. 原生适配层

### 5.1 Binder capability

`libopairshare_capability.so` 拦截 `AIBinder_Class_define()`，在 Mosey 的 Binder 类上替换 transaction handler。transaction code 4 返回一个非空 stable parcelable，其 capability protobuf 的 field 1 为 `false`。其他 transaction 调用原 handler。

这一层将 capability 查询的返回格式与当前原生服务接口对齐。

### 5.2 FFI 配置

`libopairshare_config.so` 通过 `dlsym()` 获取原 `mosey_start_5`，在 `OPAIRSHARE_ENABLE_HOPPING=1` 时安装包装函数。包装函数在配置 protobuf 后追加 field 5 的 `true` 值，并根据 `OPAIRSHARE_SOCIAL_CHANNELS` 选择频道集合。

运行脚本使用：

```text
OPAIRSHARE_ENABLE_HOPPING=1
OPAIRSHARE_SOCIAL_CHANNELS=149
OPAIRSHARE_CONFIG_TRACE=/data/adb/opairshare/config-trace.log
```

包装函数处理配置后继续调用原 `mosey_start_5`，协议实现由原 FFI 库执行。

### 5.3 socket 缓冲

`opairshare-socket-buffer` 使用 `pidfd_open` 和 `pidfd_getfd` 访问指定 Mosey 进程的 socket。匹配条件为：

- 进程命令名为 `com.google.android.mosey`。
- socket 类型为 TCP stream。
- 本地与对端地址均为 IPv6 link-local。
- 本地 scope ID 对应当前 `mosey0`。

`--apply` 将匹配连接的 `SO_SNDBUF` 请求值收敛到 65536 字节；Linux 读回值通常为请求值的两倍。监督器周期性运行该助手，作用范围为当前 Mosey 连接。

### 5.4 Wi-Fi 会话元数据

`OPAirShareSessionWifi` 通过 `app_process` 调用 Android Wi-Fi API，记录 boot ID、network ID 与 autojoin 状态，并在会话结束时恢复。

共存会话直接保持 STA 连接；采用原发现路径的会话由该助手保存并恢复 Wi-Fi 元数据。

## 6. 无线驱动适配

SM8750 平台的实现过程、关键接口和研发结果见 [在 SM8750 原厂固件上实现 AWDL](SM8750-AWDL.md)。

### 6.1 Wonder 与独立 provider

Wonder 向原生服务提供无线接口，底层使用 `mac80211` 与平台 passthru 操作。

`opairshare_wonder_provider` 通过 Linux component 框架暴露绑定点，导出 `cnss_set_vendor_wonder_priv_data()`，将 WLAN 提供的操作表交给 Wonder。它作为独立模块与设备原有 CNSS 服务共同组成绑定关系。

### 6.2 TX 路径

管理帧和 AWDL 数据桥由 `wma_opairshare_pas_mgmt.h` 等文件实现，包含异步工作队列、vdev 生命周期、peer 归属、发送完成与队列计数。

主要模块：

| 文件 | 作用 |
| --- | --- |
| `wma_opairshare_pas_mgmt.h` | 管理帧发送与异步 bridge 队列 |
| `wma_opairshare_awdl_target.h` | 从会话学习 peer 并管理目标上下文 |
| `wma_opairshare_awdl_sequence.h` | AWDL 发送序号管理 |
| `wma_opairshare_flow_control.h` | 队列容量与发送背压 |
| `wma_opairshare_channel_state.h` | 无线会话与频道状态 |

`turbo.sh` 的 `fast` 配置使用 VHT MCS8、2SS、最多 5 个 MSDU、7935 字节聚合上限和 1000 微秒收集窗口，带宽设为 20 MHz。配置通过 `passthru_awdl_*` 模块参数传入驱动。

### 6.3 RX 路径

RX 实现同时涉及辅助 monitor、固件接收 matcher、RAW 帧投递和 native REO 数据路径。

| 文件 | 作用 |
| --- | --- |
| `wlan_hdd_opairshare_aux_rx.h` | 辅助 monitor 与目标接口转发 |
| `wma_opairshare_awdl_rx_mac_filter.h` | matcher 创建、固件响应与会话释放 |
| `wma_opairshare_native_rx_work.h` | 将 native RX ring 激活安排到异步工作队列 |
| `wlan_hdd_opairshare_rx_ring_switch.h` | 会话持有期间选择接收 ring |
| `dp_be_rx.c`、`dp_rx.c`、`dp_rx_err.c` | native 数据路径中的 RAW 帧接纳与投递 |
| `wlan_hdd_opairshare_awdl_rx_dedup.h` | 接收重复帧处理 |
| `wlan_hdd_opairshare_rx_order.h` | 可配置的主机接收排序辅助 |

native RX 工作项绑定当前 vdev 与 peer，执行时检查 bridge 的会话状态。正常 Stop 与实际 vdev 删除完成后，matcher 进入下一次会话可重新启用的状态。

## 7. Wi-Fi 共存

`coex-session.sh` 在本次分享会话中持有驱动参数和 property 的快照。

| STA | 策略 property | 本次会话配置 |
| --- | --- | --- |
| 5745 MHz | `same149` | 同信道发现，沿用当前 TX profile |
| 5200 MHz | `cross5g` | HT MCS7、1SS、聚合关闭、收集窗口为 0 |

两种策略均启用 `passthru_awdl_allow_concurrent` 与 `passthru_mgmt_home_channel`，AWDL 使用 5745 MHz。

prepare 先将 boot ID、STA 频率、原 property 和七个驱动参数写入 `coex-session.preparing/`，再通过目录重命名提交为 `coex-session/`，随后应用会话配置。tick 根据活动 channel 和 matcher 状态选择 native RX ring。restore 在无线会话释放后写回快照，并将记录归档为 `coex-session.restored-*`。

设置中的 `scope=auto` 使用上表两种频率；`scope=same149` 选择 STA 5745 MHz。

## 8. 状态与并发管理

状态根目录为 `/data/adb/opairshare`。boot ID、进程身份与会话文件共同描述运行归属。

| 状态 | 用途 |
| --- | --- |
| `stack-boot` | 当前服务栈所属的 boot |
| `server.pid`、`server-boot` | 原生服务进程身份 |
| `supervisor.pid`、`supervisor-ready` | 界面监督器与就绪状态 |
| `ui-session` | 当前分享界面的会话归属 |
| `coex-session/` | 共存参数快照 |
| `session-wifi` | 原发现路径的 Wi-Fi 元数据 |
| `callback/`、`callback-auth/` | APK 挂载与 provider 认证记录 |
| `autostart`、`turbo-*`、`coex-*` | 持久化偏好 |
| `speed-history/` | 实时速度与历史记录 |

并发操作通过 flock 协调：

| 锁 | 持有者 |
| --- | --- |
| `stack.flock` | 服务栈操作与设置保存 |
| `provider.flock` | 界面监督器 |
| `settings.flock` | 设置保存与共存会话准备 |
| `callback/operation.flock` | callback 挂载操作 |
| `speed-history/worker.flock` | 速度记录进程 |

`settings.sh apply` 保存前记录标记文件与驱动参数；写入中断或返回错误时按快照恢复。WebUI 在保存后重新读取状态，对照目标配置。

## 9. WebUI 与管理 API

前端使用 Vue 3.5.38、miuix-vue 0.1.1 和 Vite。底栏包含状态与设置两个页面：

- 状态：AWDL、QuickShare、OShare 状态，实时发送/接收速度与历史列表。
- 设置：Wi-Fi 共存开关、关于、日志查看与导出。

`webui/src/api.ts` 使用 KernelSU 的 `window.ksu.exec` 调用固定后端命令。每次请求使用独立的 JS callback key，在回调或超时后清理。

| 接口 | 命令 | 返回 |
| --- | --- | --- |
| 状态 | `settings.sh status` | 配置、进程与驱动状态 JSON |
| 保存 | `settings.sh apply <coexist> <scope> <profile> <autostart>` | 保存后的状态 JSON |
| 即时计数 | `settings.sh speed` | 接口字节数、ifindex 与采样时钟 |
| 历史 | `speed-history.sh status` | `recorder`、`live` 与 `history` |
| 日志 | `settings.sh logs` | 最近启动和监督器日志 |
| 导出 | `settings.sh export-logs` | 导出文件路径 JSON |

配置枚举为：

```text
coexist:  on | off
scope:    auto | same149
profile:  fast | vht | on | off
autostart: on | off
```

界面的共存开关先读取完整配置，再修改 `coexist`，其余字段沿用当前值。`awdl` 状态由驱动参数节点和原生服务进程生成；QuickShare 与 OShare 状态来自安装信息和进程状态。原始 `radio`、`matcher`、`pending` 字段用于描述无线会话。

## 10. 速度历史

`speed-history.sh` 是由监督器启动的独立后台进程，每秒读取：

```text
/sys/class/net/mosey0/ifindex
/sys/class/net/mosey0/statistics/tx_bytes
/sys/class/net/mosey0/statistics/rx_bytes
```

采样间隔使用 `/proc/uptime`，展示时间使用 Unix 时间戳。速率单位为字节/秒，前端按十进制 MB/s 展示。

记录规则：

- 接口首次出现、ifindex 变化或计数重置时建立新的采样基线。
- 累计数据变化时更新发送/接收字节数、峰值和平均速率。
- 连续 6 秒空闲、接口重建或进程退出时结算当前记录。
- 捕获到活动传输状态的记录标记为 `transfer`；其他记录标记为 `traffic`，累计流量达到 65536 字节后保存。
- `records.jsonl` 保留最近 100 条，通过临时文件和重命名提交。

统计粒度为 AWDL 流量会话，包含接口上的协议流量；同一会话内的并发文件合并统计。平均速率以最后一次数据变化作为结束时间，空闲等待时间在记录结算时排除。

## 11. 代码阅读顺序

1. `module/bin/control.sh`：服务栈与原厂恢复。
2. `module/bin/ui-lifecycle.sh`、`coex-session.sh`：分享界面与无线会话。
3. `oshare/app/src/main/java/com/tlsu/oshareairdrop/Entry.java`、`ProviderApi.java`：分享入口到 provider。
4. `mosey/callback/build.py`、`mosey/discovery/DiscoveryPolicy.java`：回调归属与联网发现。
5. `native/config.c`、`capability.c`：原生接口适配。
6. `kernel/provider/provider.c` 与 `kernel/driver/wma_opairshare_pas_mgmt.h`：无线绑定和数据路径。
7. `module/bin/settings.sh`、`speed-history.sh` 与 `webui/src/api.ts`：管理接口与界面。

其他设备的适配步骤见 [平台迁移指南](PORTING.md)。
