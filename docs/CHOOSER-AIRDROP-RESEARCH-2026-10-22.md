# 系统分享面板接入 AirDrop：静态研究

日期：2026-10-22。目标：让 AirDrop 接收设备进入截图中 `com.android.intentresolver.ChooserActivity` 顶部的设备区域，并复用已有 AirDrop 发送能力。

## 结论

优先扩展 **OShare 的 Direct Share 发布通路**。本地 OShare 17.2.36 已经具备“设备列表 → 筛选 → 分享快捷目标 → 系统分享面板”的服务端实现；当前桥接已经覆盖其中的设备列表和公共发送入口。用户补充的 IntentResolver APK 进一步确认：该面板会先走原生 URI 授权入口，再通过 Binder 调用 OShare 的 `sendData()`，可以接到现有 AirDrop 发送 hook。

这份 APK 的头像行采用“动态分享快捷目标 + OShare 实时设备回调”协作：快捷目标提供设备入口、图标与名称；设备回调触发重新查询目标、更新进度，并提供点击时按 ID 查找的设备。只发布快捷目标、或者只发送 `onDeviceChanged()`，都不能构成完整接入。

当前成果为研究和实施设计，未实现或验证设备接入。没有手机连接；本次新增分析用户提供的 `/Users/evermore/Downloads/IntentResolver_2021-11.apk`。其 manifest versionName 确实为 `2021-11`，但 compile/min/target SDK 都是 37，不能把文件名当作 APK 的实际年代。截图与该 APK 的运行对应关系未独立验证；旧 framework 仅作对照，不再用于代替本 APK 的面板行为。

## 证据范围

| 编号 | 来源 | 性质 |
| --- | --- | --- |
| E1 | 当前仓库 `oshare/app/src/main/java/com/tlsu/oshareairdrop/OShareBridge.java`、`Entry.java`、`OShareSymbols.java`、`BridgeMessenger.java` | 源码观察；工作区存在其他正在进行的修改 |
| E2 | `/Users/evermore/Desktop/dev/android/AirDropVivoCN/oshare-base.apk` | 本地 OShare APK；SHA-256 `8bcc8a4874af16a4c19f688a1b9cda2ca6d605c770187257c0dbb6d2b12eccf5` |
| E3 | `/Users/evermore/Desktop/dev/android/AirDropVivoCN/oshare-analysis/jadx-out/` | 既有反编译缓存；manifest 声明包名 `com.coloros.oshare`、版本 `17.2.36 / 17002036` |
| E4 | `/Users/evermore/Desktop/dev/android/dumper/oplus-framework/oplus-framework.jar` | 本地 framework；SHA-256 `cdbef3ac2a08bedd1198a7698eeffb7d0b82567660e67afd8fb9b320d40d673a` |
| E5 | `/tmp/opairshare-chooser-20261022/` | 本次用本机 JADX 1.5.1 从 E2/E4 生成的定向反编译；筛选条件使用 simple 模式复核 |
| E6 | `/Users/evermore/Downloads/IntentResolver_2021-11.apk` | 用户补充的 APK；SHA-256 `f6bc3ec9b4e422605538ff1174347867a7bcefb2dc29aff7d43d3a1e16572717`；包名 `com.android.intentresolver` |
| E7 | `/tmp/opairshare-intentresolver-20261022/` | 本次 JADX 1.5.1 输出；全量反编译报告 13 处错误，关键 Adapter 方法另用 simple 模式复核，未将失败的伪代码当作运行证据 |

本次 REA Android 工具因缺少 `REA_JADX_MCP_JAR` 返回 `capability_unavailable`，转用已安装的 JADX CLI；下述证据是本地源码/反编译观察，没有 REA Evidence ID，也没有执行观测。关键摘录和校验值保存在 [证据记录](research/chooser-airdrop-2026-10-22/evidence.json)。

## 1. 截图的位置有两种不同机制

E4 的 `com.oplus.resolver.OplusResolverOshare` 使用 `OplusOShareManager` 接收 `IOplusOshareCallback.onDeviceChanged(List<OplusOshareDevice>)`。在这份 framework 中，它只根据设备数量更新互传面板，点击后调用 `OplusOShareUtil.startOshareActivity()` 打开互传 Activity，不负责创建截图里的多个头像目标。

E2/E3 的 `com.oplus.oshare.ui.direct.DirectShareServer` 则会创建带名称、第二行名称、头像的动态分享快捷目标。`res/xml/shortcuts.xml` 把 `com.oplus.oshare.ui.category.TEXT_SHARE_TARGET` 映射到 `com.oplus.oshare.ui.ShareActivity`，MIME 为 `*/*`。

因此，优先研究 Direct Share；直接 hook 旧的 `OplusResolverOshare.updateOShareUI()` 只能影响互传入口，不能据此认定能插入头像目标。

E6 的实现已搬到 `com.android.intentresolver` 包内。其 `OplusResolverOshare` / `OplusOShareManager` 不再只是旧 framework 的设备数量面板，而是直接把设备回调交给 `ChooserActivity` 的 Direct Share listener。新接入应以 E6 为准，不能照搬旧 framework 的类名或生命周期。

## 2. 已确认的发布链

```text
Mosey 发现 AirDrop 设备
  → OShareBridge 的 DEVICES（airdrop:<id>）
  → manager.list（本样本 v9.a.n）合并原设备与 AirDrop 设备
  → manager.refresh（本样本 v9.a.r）通知观察者
  → OShareClient$a.a()
  → DirectShareServer.O(listeners, devices)
  → T() → H() 筛选、排序、取前 3 个
  → k0() / l0() / m0() 构建发布批次
  → A(context, device, icon) 构建快捷目标
  → l0.c.a(context, shortcuts)
  → ShortcutManager.addDynamicShortcuts()
  → IntentResolver.ShortcutLoader 查询并转换为 ChooserTarget
  → ChooserListAdapter 显示头像行
```

同时存在另一条必要的实时通路：

```text
OShareClient 的 DirectShareServer 发布后回调 IOplusOshareCallback
  → IntentResolver.OplusOShareManager.mDeviceList
  → OplusResolverOshare 的 listener
  → ChooserActivity.AnonymousClass9.onProgress()
      ├─ 设备变化/空闲列表变化：ShortcutLoader.updateShortcuts()
      └─ 传输状态变化：按 BLE key 查 mDirectMap，更新原生进度
```

本样本符号用于说明证据，不能直接作为跨版本 hook 名称。

`DirectShareServer.A()` 的映射：shortcut ID 来自设备 BLE key；**long label 来自设备名称，short label 来自扩展设备的 secondary name**；category 为上述 TEXT_SHARE_TARGET；能力名为 `actions.intent.SEND_MESSAGE`；设置 long-lived，并传入 IconCompat。这里依据 builder `l0.a$b.g()/i()` 和最终 `ShortcutInfo.Builder` 调用复核，修正了前一版文档将 short/long 写反的问题。

E6 的 `ShortcutLoader.access$filterShortcuts()` 将 shortcut ID 和 short label 放入 `android.intent.extra.shortcut.ID/NAME`，以 `shortcutInfo.getLabel()` 构建 ChooserTarget。`ChooserListAdapter.onBindView()` 对 OShare 特殊处理，将 NAME 用作第二行，因此不能按普通 app 分享目标的字段习惯设置。

`OShareClient.onBind()` 读取 `bind_from` 并交给 DirectShareServer；`registerMultiCallback()` 还会触发 `checkBindFrom`，在来源未知时按本样本的默认来源初始化。发布受来源、开关、会话状态控制，不能无条件调用私有发布方法后认为生效。

E6 的 `OplusOshareServiceUtil.initShareEngine()` 明确传入 `bind_from=0`，连接成功后注册 callback 并 scan。`OplusOShareManager.onShareInit()` 在 **OplusBuild.VERSION.SDK_VERSION >= 38** 时使用 `registerMultiCallback(callback.hashCode(), callback)`，否则使用普通 registerCallback；38 是 Oplus SDK 判断，不能当作 Android API level。

## 3. 为什么当前 AirDrop 设备不会自然出现

### 3.1 连接和发现绑定于 OShare Activity

当前 `OShareBridge` 在 OShare Activity 的 resume hook 中保存 Activity、建立 Mosey 连接并开始发现。`connect()`、HELLO 接收、重连都检查 `isCurrentActivity()`；destroy 时清理设备并停止发现。

如果用户只停留在系统 Chooser，OShare 服务虽然可能已经运行，桥接仍不会因为服务被绑定就自动进入这套发现流程。仅添加 LSPosed 作用域不会解决这个问题。

### 3.2 Direct Share 有独立的资格筛选

本次从 APK 重新生成的 `DirectShareServer.H()` simple 代码确认：先按蓝牙状态筛选，再要求 BLE key 非空，然后至少满足同账号、某个扩展设备标记、历史记录或系统特殊设备资格之一；最后排序并取 3 个。

当前 AirDrop 扩展设备默认不具备这些资格，因此即使 `manager.list` 已包含它，也可能在这里被剔除。

设计应增加“本桥接拥有且仍在线的 AirDrop 设备”这一资格。不要伪造同账号标记：同账号还会切换头像加载和排序逻辑；也不要写入虚假的原生传输历史。资格识别应同时检查命名空间和当前桥接快照，不能仅凭任意外部对象上的字符串前缀。

### 3.3 列表展示和快捷目标展示使用不同字段

现有 `OShareAppearance` 设置的扩展设备 bitmap，在本样本中正好对应 DirectShareServer 头像加载检查的 `v9.d.W()`，有复用空间。设备种类由现有逻辑选择 Apple 图标。

但现有 `adapter.label` 返回的“AirDrop”只影响 OShare 独立页面。Direct Share 第二行使用另一条 secondary-name getter（本样本 `v9.d.d0()`）；要显示“AirDrop · iPhone”一类标识，应单独绑定该语义字段或在快捷目标构建边界替换 owned 设备的文本。

系统会自行绘制目标所属应用的角标。沿用 OShare 发布，预期仍是 OShare 角标；显示 AirDrop 文字与更换角标是两个不同需求。目标设备是否支持头像也取决于 provider 实际提供的数据，不能从截图推断 AirDrop 能获得同样的账号头像。

### 3.4 底层 supervisor 尚不识别 Chooser 前台

`module/bin/ui-lifecycle.sh` 的 `foreground_state()` 目前识别 Quick Share 和 OShare ShareActivity，未识别 `com.android.intentresolver.ChooserActivity`。即使 Java 层开始发现，底层仍可能将该界面判断为关闭，或因接收功能恰好开启而掩盖这个缺口。

需要把系统 Chooser 纳入发送会话生命周期，并继续遵守模式切换、传输忙闲、Wi-Fi 共存和关闭恢复逻辑。只添加包名会把其他解析/打开方式界面也算进去，应限定分享 Activity/会话。

## 4. 点击与文件授权：可复用部分和边界

E3 的 Binder 适配器 `com.oplus.oshare.compat.IOshareService$Stub$StubR.sendData(Intent, OplusOshareDevice)` 会将设备包装为内部 wrapper，然后调用 OShareClient 的公共发送实现。现有 DexKit `service.send` 正是 hook 这个内部实现，发现 owned AirDrop key 后转交 Mosey；普通设备继续原路径。cancel 入口也已有相同分流。

E6 已补齐点击路径。`ChooserGridAdapter$3.onClick()` 对目标类名 `com.oplus.oshare.ui.ShareActivity` / `IOSShareActivity` 走专门分支，正常 app 仍走 startSelected。OShare 分支的顺序为：

```text
读取 target extras 中 android.intent.extra.shortcut.ID
  → OplusOShareUtil.sendGrandIntent(context, originalShareIntent)
  → 克隆原分享 Intent，设置 ShareActivity component
  → 遍历 OShareManager.mDeviceList
  → 找 getBleMac() == shortcut ID 的设备
  → OplusOshareServiceUtil.sendData(intent, device)
  → IOplusOshareService Binder
  → OShareClient 内部 service.send
  → 现有 AirDrop hook
```

因此点击头像无需先进入 OShare 独立分享页。若快捷目标已经显示，但实时设备列表为空或 ID 不匹配，这个点击分支直接返回，不会发送。`airdrop:<id>` 必须同时用于设备 BLE key、shortcut ID、设备查询和取消/进度归属；不可给每次 UI 刷新创建不同 ID。重复点击也再次调用 sendData，活动任务的再次点击应由现有桥接的 ACTIVE/取消逻辑处理，不能假设 Chooser 点击分支会调用 cancelTask。

原生授权路径也已定位：`sendGrandIntent()` 克隆原分享 Intent，设置 `GrantUriActivity`，增加 65536 flag，通过 `startActivityAsCaller(intent, null, false, -10000)` 启动。E2 的 `GrantUriActivity` 在 onCreate 中遍历 ClipData 的 content URI，以 READ 权限授权给自身包，随后 finish。E2 manifest 同时声明 ColorOS GrantUriActivity alias，匹配 E6 的资源配置。应保留这个已有入口，不能替换成直接传 URI 字符串给 Mosey。

现有发送实现使用 ContentResolver 打开 content URI，再用 ParcelFileDescriptor 将文件交给 Mosey，避免 Mosey 直接依赖原分享方的 URI 权限。这一方式可以保留。静态代码已经说明了预期授权转发方式，真实 provider 的授权有效性、URI 规范化和授权 Activity 与后续文件打开的时序仍需测试，不能把调用存在当作所有第三方 content provider 都可读取的证据。

如果点击时连接未就绪，应先保留目标和有界的待发送请求/已取得的 FD，完成握手后发送一次；超时、取消、Binder death 时关闭 FD。文件打开和准备移到工作线程，避免首次点击在主线程阻塞。已经离线的快捷目标不能重新触发旧设备的发送。

## 5. 推荐实施顺序

| 阶段 | 修改范围 | 通过条件 |
| --- | --- | --- |
| 1. 解耦发现会话 | 从 OShareBridge 中抽出连接/设备快照/发现生命周期；由独立页和 Chooser 会话共同持有 | 不打开 OShare 独立页也能连接、发现；关闭一个入口不会中断另一个或活动传输 |
| 2. Direct Share 适配 | 新增独立 DexKit resolver/symbols，定位 DirectShareServer 的资格筛选、构建目标、会话入口 | owned 在线设备可发布；原设备资格、身份和历史不被修改 |
| 3. 展示与清理 | 复用 bitmap，增加 secondary name，处理发布批次和离线/切换 | lost/mode change 后清理目标；未改变的名称/头像不反复重建；并发刷新无重复项 |
| 4. 点击接入 | 复用已定位的授权与 Binder 发送分支，补齐请求保留、身份握手与 URI/FD 授权 | 单文件、多文件、文本都能一次点击发送；冷启动不吞首次请求 |
| 5. 底层生命周期 | 扩展 ui-lifecycle 前台判断，必要时消费明确的 Chooser 会话租约 | 接收关闭时也能发送；退出与传输结束后正确释放 |

当前只需将 `com.coloros.oshare` 和 Mosey 作为主要 hook 宿主。若 OShare 服务的 bind/register/unregister 足够识别 Chooser 会话，可以保持两个作用域。只有当这些信息不足、必须观察目标选择/Activity 生命周期或调整本机渲染时，再增加 `com.android.intentresolver`；届时同步修改 Entry 分派、scope.list、LSPosed 自动配置和 UID 校验，不能仅勾选作用域。不要全局 hook system_server。

E6 证明了先保留两个作用域的具体切入点：在 OShare 服务端跟踪来自 IntentResolver 的 registerMultiCallback / registerCallback、unregister 和 callback Binder death，维护 Chooser 的发现租约。注册时读取真实 Binder caller UID，并核对同用户的 IntentResolver 包身份；异步转主线程前捕获身份。不能只依赖 onBind（服务复用时不会每次重新 onBind），也不能把最后一个 callback 注销等同于允许终止活动传输。连接应使用 OShare Context 的暖连接/就绪通知，解除当前 HELLO 对活着的 ShareActivity 的依赖；底层 supervisor 负责仅打开 Chooser 时的 provider 就绪。这样 Mosey 仍只信任 OShare 客户端，不需要为这一方案放宽 Mosey 的 UID 白名单。

设备资格放行要位于排序/取前 3 个之前；保留原生排序时，满 3 个原生候选可能挤掉 AirDrop。若产品要求 AirDrop 始终有一席，需要明确一个席位保留策略，而不是简单对筛选结果 append。系统最终展示数量与顺序仍可能受 Chooser 自己的规则影响。

应复用原发布器的会话失效/代次检查和更新节流。原发布器内部使用过 removeAllDynamicShortcuts，另起一个发布循环会相互清空；不要在未整合原批次之前直接写 ShortcutManager。

E6 对 OShare 候选已有很高的排序基分（本 APK 为 `2.1474836E9f`），并有特殊去重分支，不需要为了排在常规 app 快捷目标前再改 Chooser 排序。最终能显示几个仍同时受 OShare 的 3 个候选限制、Chooser 配置与 profile 影响，不能仅凭 APK 中 framework resource 的反编译别名读出实际限制值。

## 6. 原生进度可复用

E6 `ChooserListAdapter.updateDeviceProgress()` 已使用 simple 模式核对。它读取 `OplusOshareDevice.getState()/getProgress()`：TRANSIT_WAIT/READY 显示等待，TRANSITING 设置圆形进度并显示正在发送，成功播放完成动画；还支持失败、拒绝、取消、取消等待、忙和超时。View 由 `mDirectMap[BLE key]` 定位。

现有 `AirDropPresentation.state()` 与多数原生状态直接对应，因此有条件复用原生进度，不必先在 Chooser 增加一套 View。现有 `adapter.label` 的细分阶段文案不会自动影响 Chooser；如要求显示“正在连接对方/等待对方接收”等，需要额外处理本机文本，首版可保留原生等待文案。SPACE_NOT_ENOUGH 虽存在于 enum，本方法没有对应的专门文案分支，需要确定失败显示策略。

头像/第二名称变化也有缓存边界：空闲设备列表比较使用 equals，而该设备 equals 只比较 name/ble/wifi；仅头像或第二名称变化不一定触发重新查询。图标缓存又包含 component + shortcut ID，不能假设同 ID 重新发布就立即换图。这部分应单独验收，必要时只对 owned 目标补刷新，而非修改全局逻辑。

## 7. 验证范围与未决问题

已回答（静态）：现有 hook 覆盖范围、服务端发布链与过滤原因、本 APK 的快捷目标加载、设备回调和列表刷新、点击后直接 Binder 发送、原生 URI 授权入口、原生进度渲染、发现/底层生命周期缺口、名称字段的实际映射。

部分回答：截图界面与本 APK 机制一致，尚未通过真机确认安装的 APK 完全相同；服务端 APK 为本地 17.2.36 样本，需保持跨版本 DexKit 语义定位和匹配失败时的原生回退。

未回答（需运行验证）：第三方 provider 实际读授权/时序、冷启动可用性、快速出现/消失时的刷新竞态、仅头像/第二名称更新、真实进度与重复点击取消效果、多个候选/profile 的最终排列、角标和完成动画的视觉结果、真实耗时。尚未编写接入实现或打包安装测试。

真机验收至少覆盖：接收开关关闭后的冷启动分享；目标出现/消失；3 个以上原生与 AirDrop 候选混合；重复开关面板；单文件/多文件/文本/第三方 content provider；点击后立即关闭 Chooser；发送期间模式切换；Binder 重连；过期快捷目标。成功标准是“发现→显示→授权→发送→结果→清理”完整闭环，不只是把头像塞进列表。
