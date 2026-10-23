# OShare 向 Apple 设备发送的流体云展示

OShare 的 AirDrop 发送接入手机原有 `OShareSending2.0.upk` 与 Seedling SDK，展示准备发送、连接、等待对方接收、传输百分比，以及完成、拒绝、取消、超时或失败结果。结果卡片在 12 秒后结束，普通结果通知继续保留。系统不支持或卡片创建失败时保留发送进度通知；原分享面板继续可用。

## 实现

- `OShareSendingAssets` 只读取手机原始签名模板，校验资源身份、版本、页面交互及 `cancelTask + instanceId` 契约；不修改、复制或随 APK 分发厂商模板。
- `OShareNativeTransferPresenter` 从原接收 presenter 提取共用 SDK 生命周期。接收实例仍使用 `opairshare-<UUID>`，发送使用 `opairshare-send-<UUID>`；两端 hook 只处理自己的命名空间。原生普通互传卡片继续走系统路径。
- `OShareSendPresentation` 管理独立于 Activity 和发现列表的发送任务，关联底层通知 ID。重新打开分享页时恢复进行中任务的状态和进度。
- 每次发送在 OShare 生成新的 session，并随命令、Mosey 发送 lease、进度回调、失败事件与取消命令跨进程传递。provider 在真正执行取消前检查当前 lease 的 session；已完成、退休或上一轮卡片的点击无法取消下一轮任务。
- 发送、取消中、结果三种交互阶段使用不同卡片实例。卡片返回匹配的模板版本和实例后才启用取消；取消中隐藏控件；退休实例的迟到事件被消耗。
- 只有匹配当前 session 的事件更新分享页与流体云。迟到的 `send_requested` 不覆盖已经到达的传输进度，重复终态不重播结果；同一设备重发时结束旧卡片并清理旧通知。
- 流体云取消、通知取消与分享页取消均绑定当前发送会话。锁屏卡片只尝试打开分享页，通知取消需要解锁。取消失败时恢复可操作状态并显示错误。

## 本地验证

```sh
sh oshare/tests/run.sh
sh oshare/tests/run-android.sh --compile-only
sh oshare/tests/run-sending-assets.sh /absolute/path/to/OShareSending2.0.upk
cd oshare
./gradlew :app:assembleRelease --offline
```

主机回归包含会话隔离、重复发送、旧取消、乱序提交事件、传输 / 取消 / 终态映射、异常进度和并发任务。模板回归读取实际发送 UPK，并拒绝无实例取消、未知动作、错误 provider、未受控按钮和未知事件。Android harness 校验实际回调携带 session；`--compile-only` 只编译和生成 DEX，不代表已在 Android 执行。

## 真机验收

安装新的桥接 APK 后，使 OShare 和 Mosey 两个 LSPosed 作用域进程重新加载模块，再依次验证：

1. 向 Mac / iPhone 发送，检查准备、等待接收、百分比和完成动画；让对方拒绝，检查结果。
2. 传输中关闭分享页，确认流体云继续更新；从通知重新打开，确认状态和进度恢复。
3. 分别从流体云、通知和分享页取消；向同一设备重发，确认旧卡片 / 旧通知动作不能取消新任务。
4. 接收与发送同时发生时，确认双方卡片与取消动作互不影响，普通 OShare 设备沿用系统行为。
5. 关闭流体云支持或模拟 SDK 创建失败，确认普通进度通知和取消仍可用。
6. 在锁屏时检查卡片和通知操作需要进入解锁流程；确认 provider 断开后任务呈现失败结果。

当前环境的 ADB 设备列表为空；本次未安装 APK，未验证 ROM 的实际胶囊显示与真实 Apple 设备收发。
