# AirDrop 系统分享面板接入

本实现复用系统 OShare 的快捷目标发布、URI 授权、Binder 点击发送和设备进度展示。LSPosed 作用域仍为 OShare / Mosey，无需修改 IntentResolver APK 或增加独立分享 Activity。

## 实现

- `OShareChooserResolver` 根据 Direct Share 日志、调用关系、字段读写和服务签名定位候选筛选、第二名称 setter、注册/注销入口，使用独立 APK 指纹缓存。匹配缺失或歧义时只关闭 Chooser 能力，保留 OShare 独立分享页。
- `OShareChooserBridge` 在服务端捕获真实 Binder UID，仅跟踪同 Android 用户的 `com.android.intentresolver` 客户端。普通注册按 callback Binder、多回调注册按 UID + tag 隔离；注销和 Binder death 释放会话，替换注册不会被旧 Binder 的死亡误删。
- 候选合并只接受当前桥接快照中的 `airdrop:<id>`；原生候选保持相对顺序，最多 3 个候选，原生已占满时为 AirDrop 保留一席。不修改原生账号标记和传输历史。
- 快捷目标 ID、设备 BLE key、进度和发送归属使用同一 ID。第二名称写入 AirDrop；图标沿用已有 Apple 设备种类图标。完成/传输状态经原有 manager refresh 和 callback 回到 Chooser 的原生圆形进度、状态文字及完成动画。
- `OShareBridge` 将连接/发现归属改为“独立页或 Chooser 会话”。Chooser 使用已有广播握手和 provider 就绪通知，不从后台拉起 OShare 页面或权限 Activity；独立页保留原冷启动路径。
- 系统头像点击仍先进入 GrantUriActivity 授权，再调用原 OShare sendData。现有文件/文本发送和任务 session 继续复用；文件查询与打开移到有界后台线程池。最多 4 个准备任务，每个 12 秒超时；重复点击可取消准备，离线目标、旧连接、取消和过期的后台结果都不能再提交。
- 文件准备前先向 Mosey 请求 `prepare_send`，收到确认才开始读取；服务端租约最多 15 秒，取消用 `finish_prepare` 释放，实际 send 提交后由服务端释放。暂存文件期间的 OutgoingTransfers 也计为 busy，防止关面板或模式切换时提前回收 provider。
- provider 事件携带命令 Binder；OShare 校验发送 UID 和已握手 Binder，忽略断线后排队到达的旧事件，防止复活过期快捷目标。
- `module/bin/ui-lifecycle.sh` 新增 ChooserActivity 及 Launcher alias 的前台识别，仅在 oshare / both 模式使用，不匹配普通 ResolverActivity 或类名前缀相似的其他 Activity。

等待、发送中、拒绝、失败、取消和完成采用原生状态展示。独立页的细分阶段文案不会自动变成 Chooser 文案；SPACE_NOT_ENOUGH 在所分析 Chooser 中没有单独的文本分支。仅图标变化可能受系统快捷图标缓存影响，这些仍需真机核对。

## 构建与验证

```sh
(cd oshare && ./gradlew :app:assembleRelease :app:assembleDebug --offline --no-daemon)
sh oshare/tests/run.sh
python3 -m unittest discover -s tests -p 'test_chooser_foreground.py'
python3 -m unittest discover -s tests -p 'test_receive_lifecycle.py'
sh oshare/tests/run-android.sh --compile-only
sh oshare/tests/run-dexkit-android.sh /absolute/path/to/OShare.apk baseline --compile-only
```

主机用例覆盖混合设备、候选保留与去重、离线清理、会话隔离、旧 Binder death、准备超时/取消后的晚到结果、准备队列容量、服务端租约的 UID 隔离和期限、文件暂存忙闲，以及前台识别和底层会话恢复。Android 握手与 DexKit harness 可在没有手机时编译；这不代表 Android 上已执行。

有设备时去掉两个 harness 的 `--compile-only`；DexKit 的 baseline 必须使用研究中同一版本的 OShare APK，其他版本使用 scan 模式。重命名样本增加了 DirectShareServer，以检验筛选与字段定位不依赖其混淆名。

安装测试应使用**当前桥接 APK 与更新后的 module 脚本一起打包的模块**，重启使 OShare 和 Mosey 两端同时加载。单独覆盖桥接 APK不能确认旧模块的前台生命周期逻辑已更新。当前工作区的既有发送展示等改动保留在构建中；本次未替用户刷入、安装或重启手机。

## 真机验收

首次打开系统分享面板即可发现 AirDrop 设备；同时存在原生设备时 AirDrop 能进入候选。点击单文件、多文件及文本目标，确认沿原生授权发送，进度随 provider 事件更新；重复点击能取消。关闭独立页不能中断仍在 Chooser 中的发现，关面板不能中断正在准备/传输的任务。设备消失、Binder 重连和模式切换后不能通过旧快捷目标发送。

日志重点：`OSHARE_CHOOSER_RESOLVED`、`OShare Chooser candidates and callback session hooks installed`、`OShare bridge ready`。`System Chooser AirDrop capability unavailable` 表示语义定位或绑定失败，应提供异常日志核对该版 OShare，不回退到猜测混淆名。准备或传输时 `OPAIRSHARE_RECEIVE busy=1` 供底层统一执行忙闲保护。

静态链路依据和 APK 指纹见 [研究记录](CHOOSER-AIRDROP-RESEARCH-2026-10-22.md)。当前无真机运行证据，尚不能确认目标 ROM 的实际发现、URI 授权时序、进度显示和传输成功。
