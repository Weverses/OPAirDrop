# Wi-Fi / AWDL 共存验收记录（2026-10-22）

## 结论

未通过“全频段实机共存”验收。手机已部署不同于当前工作区的自动共存策略，策略层接受 2.4 / 5 / 6 GHz；当前仅直接观察到 STA 5745 MHz 与固定 149 AWDL 同时处于活动状态。历史记录证明曾为 2447 MHz、5805 MHz 准备共存会话，不能单凭准备记录判定发现、双向传输、文件一致性和 STA 连续联网均通过。

本次只读取设备状态和日志，并在主机运行频率分类测试；没有修改手机配置、切换网络、重载驱动或发起传输。

## 两套实现存在差异

- 当前工作区 `module/bin/coex-wifi.sh` 只接受 5745 MHz；13 个频率输入中，仅 5745 成功，其余返回 `wifi_not149`。
- 手机 `/data/adb/modules/opairshare/bin/coex-wifi.sh` 支持 `band24`、`cross5g`、`same149`、`band6`，默认 scope 为 `auto`。
- 手机 `coex-session.sh` 在 auto 模式将 `passthru_sta_coexist` 设置为 3，非 149 使用 discovery policy `auto`，AWDL 保持 5745 MHz。手机使用 `debug.op13.discovery`；当前工作区使用 `debug.opairshare.discovery`。
- 手机 module.prop 自报 1.0.13；此版本号不足以唯一标识这些运行脚本和驱动修改。

SHA256：

| 文件 | 当前工作区 | 手机 |
| --- | --- | --- |
| coex-wifi.sh | `5a0ea14a9a9d700f4801526687d3d1e95a3df210939d9b662e96c209671bc46b` | `e790dd6b6a2bbaac76c3eb3ed079c268a7529de13e9974df4e72d97bd60465fa` |
| coex-session.sh | `0d14a573e968c1bc45449db252e939b4b6fd7473ff8b226d4bf333e29ea262f6` | `a52fade1825b73a6146b18e1ebe9235f07ca28f938c4b31f2b190e82985b0aee` |

## 手机当前状态

- PJZ110，ADB root 查询成功。
- `passthru_sta_coexist=3`，参数可写。
- 无 `coex-off`、`coex-same149-only`、`coex-wifi-blocked` 标记。
- 当前快照：frequency=5745、class=same149、scope=auto、policy=same149。
- `debug.op13.discovery=same149`，`debug.opairshare.receive.ready=1`。
- Wi-Fi status 报告 STA 5745 MHz。
- 驱动 channel：`busy=0 fault=0 error=0 accepting=1 vdev=2 freq=5745`。
- matcher：`used=1 armed=1 pending=0 active=1 fault=0`，freq=5745。
- Mosey Java 进程及 mosey_server 存活。

这些状态证明当前同信道无线会话及接收准入已打开，不代表本次已经完成文件发送/接收验证。

## 历史及主机测试证据

supervisor.log 和已恢复会话快照包含：

```text
COEX_UI_PREPARED frequency=5805 class=cross5g scope=auto policy=auto mode=3
COEX_UI_PREPARED frequency=2447 class=band24 scope=auto policy=auto mode=3
COEX_UI_PREPARED frequency=5745 class=same149 scope=auto policy=same149 mode=3
```

还存在断线、Wi-Fi 关闭、网络变化及恢复日志。准备快照在驱动参数应用和 provider 激活之前创建；`COEX_UI_PREPARED` 也在 provider 激活之前记录，均不能替代后续数据通路验收。

读取手机 helper 原文到主机临时目录，在不执行任何 Wi-Fi 写操作的 fixture 中验证：2412、2437、2484、5180、5200、5260、5500、5745、5765、5805、5955、6115、7115 MHz，auto 模式全部返回 eligible，并按频段分类。这仅验证策略分支，不验证监管域、实际可连信道、硬件/固件并发或射频收发。

## 尚缺少的验收证据

| 项目 | 本次结论 |
| --- | --- |
| 149 同信道 | 当前状态正常；本次未做文件传输 |
| 其他 5 GHz | 5805 有历史准备记录；完整共存待实测 |
| 2.4 GHz | 2447 有历史准备记录；完整共存待实测 |
| 6 GHz | 仅策略测试通过，无实机连接/传输证据 |
| DFS、宽带宽、MLO | 没有专项验收证据 |
| 跨频段切换期间的任务恢复 | 有重建日志，无完整传输与文件一致性证据 |

要宣称全频段实机支持，需在设备和监管域实际支持的各频段，验证保持 STA 联网的同时进行 AWDL 发现、双向文件传输及哈希一致性，并覆盖取消、断线重连和频段切换；DFS、带宽、MLO 等组合需单独标明通过范围。重新打包前还需核对运行版本对应源码，避免当前工作区覆盖掉手机已有的自动共存实现。
