# OShare AirDrop 桥接

连接 OShare 系统分享界面与 Mosey provider 的 LSPosed 模块。OShare 的内部入口与设备属性通过 DexKit 2.3.0 动态解析。

桥接 APK 仅提供 Xposed 功能，没有独立 Activity、桌面图标或 LSPosed 设置页。模块设置和日志使用 KernelSU WebUI，文件收发使用系统互传界面及宿主中的接收对话框。

## 构建

环境：JDK 17、Android SDK Platform 37。项目使用 Gradle wrapper 8.13、AGP 8.13.2 与 libxposed API 102.0.0。

```sh
./gradlew :app:assembleRelease
sh tests/run.sh
```

正式构建 APK 输出到 `app/build/outputs/apk/release/app-release.apk`，应用 ID 为 `com.tlsu.oshareairdrop`。release 关闭调试，debug 和 release 均使用与 OplusCameraPro 相同的自有密钥签名；版本名与版本码从根目录 `module/module.prop` 读取。打包工具只接受与模块版本一致的 release 产物。

在已忽略的 `oshare/local.properties` 或环境变量中配置 `KEYSTORE_PATH`、`KEYSTORE_PASS`、`KEY_ALIAS`、`KEY_PASSWORD`，私钥保留在外部密钥库中。缺少签名配置时不会回退到 Android Debug 签名。签名变更前的 Debug 签名 APK 不能直接覆盖安装，需要先手动卸载旧桥接；KSU 安装脚本保持原逻辑。

调试与现有设备测试脚本仍可使用 `./gradlew :app:assembleDebug`，输出到 `app/build/outputs/apk/debug/app-debug.apk`。

## 作用域

| 进程 | 职责 |
| --- | --- |
| `com.coloros.oshare` | 系统分享 Intent、设备列表与发送交互 |
| `com.google.android.mosey` | provider 调用、设备发现和传输回调 |

主机测试覆盖回调代理、发送任务、设备展示与发现频道选择。Android 测试入口为 `tests/run-android.sh` 和 `tests/run-appearance-android.sh`。

### 桥接握手与诊断

启动 Mosey 的 Activity 显式共享调用方身份，接收端继续校验同一 Android 用户下的受信 UID。不得通过接受 `INVALID_UID` 或信任 Intent 自报 UID 来绕过身份校验。

握手分为 `MSG_ATTACHED`（客户端已通过校验）和 `MSG_HELLO`（provider 已就绪）。入口启动后每 2 秒通过广播重试接入，最多 4 次，10 秒仍未就绪时按阶段提示；广播重试不会再次拉起 Activity。页面销毁或连接成功后，旧连接轮次的延迟任务失效。Binder 断开后清理过期设备，仅在分享页面仍存在时尝试重连，文件需要重新发送。

日志标签为 `OShareAirDrop`。`bridge boot rejected uid=-1` 表示启动方身份未传递；`bridge client admitted ... providerReady=false` 表示身份校验已通过，正在等待 provider；`OShare bridge ready` 表示发送桥接完成。新客户端的 HELLO 仅回复该客户端，避免触发已有客户端重复开启发现。

`tests/run-android.sh` 包含独立握手回归，检查启动身份共享、匿名拒绝、provider 延迟就绪及多个客户端互不干扰。该测试使用临时 app_process，不替换已安装应用；完整发现与收发仍需安装构建后的 APK 验证。

模块运行脚本位于仓库根目录的 `module/`。跨进程通信、任务归属和文件传递见 [技术实现说明](../docs/TECHNICAL.md#3-oshare-分享桥接)，完整构建流程见 [构建说明](../docs/BUILD.md#5-oshare-桥接-apk)。

## OShare 通用定位

OShare 端使用字符串、签名、继承与调用关系查找语义角色，不再写死内部混淆名称。base APK 与 code splits 一起解析，结果按 APK 内容指纹缓存；匹配缺失或有歧义时停止安装 OShare hook。

规则实现位于 `OShareDexResolver.java`；重命名 / 歧义样本可通过 `tests/make-dexkit-fixture.py` 生成，再由 `tests/run-dexkit-android.sh` 验证。Mosey 端与设备内核适配仍保留各自的版本要求。

## AirDrop 接收

`OShareReceiveBridge` 使用独立 DexKit 角色和缓存解析接收 Activity / COUI builder；标记实例的生命周期通过 `invokeSpecial` 进入真实父类，避免运行原互传协议的观察者、计时器和按钮逻辑。未标记实例沿用原系统行为。

`ReceiveController` 以 OShare 非零接收开关、root 共存就绪标记及活着的接收 UI Binder 共同控制广播。`IncomingTransfers` 使用请求 ID 与独立会话令牌隔离决策，`IncomingReceiver` 跟踪待确认、清理、传输和文件发布；`ReceiveFiles` 经 MediaStore 发布完成文件。原生 `text/*` 文件转换仅对本桥接拥有的附件关闭，保留文件原始字节。

测试入口：

```sh
sh tests/run.sh
sh tests/run-android.sh
sh tests/run-receive-dexkit-android.sh /absolute/path/to/OShare.apk baseline
```

接收开关行为及真机验证范围见 [技术实现说明](../docs/TECHNICAL.md#34-airdrop-接收)。

## 原生 Seedling 展示（1.0.13）

`OShareSeedlingResolver` 独立定位 OShare 卡片 provider 和 ROM 支持判断，绑定 Seedling SDK 完整签名。`OShareSeedlingAssets` 只读取已安装 OShare 的原始 UPK，检查实际使用页面的按钮显隐及带实例 ID 的进度事件；不复制或修改厂商模板。

`OShareNativeReceivePresenter` 按会话和交互阶段分配卡片实例，使用原生 SDK 发送 START / 更新 / END。只有系统返回与本机资源版本一致的卡片并确认创建后才收起自动进度弹窗；显式打开的详情保留。卡片实例对应的旧阶段动作会被消耗，未标记的 OShare 卡片沿用原路径。

原始接收确认按钮缺少实例标识，继续使用现有邀请弹窗；完成文件入口保留会话 URI。SDK 初始化与调用异步执行，避免阻塞原接收握手和主线程。详细范围见 [实现说明](../docs/OSHARE-NATIVE-RECEIVE-IMPLEMENTATION.md)。

传输中继续使用流体云；整批文件保存成功后，屏幕已解锁且没有其他活动接收界面时自动弹出完成对话框，提供“完成 / 打开文件”和实际保存目录。锁屏或其他接收任务占用界面时，通过通知打开结果；历史快照与重复完成回调不会重复弹窗。

WebUI → 设置 → 文件接收 → 保存位置可指定内部存储 `Download` 或 `Documents` 下的目录（例如 `Download/AirDrop`）。所有文件统一保存到该目录，下一批保存时生效；留空恢复图片 / 视频 / 其他文件的原分类目录。设置保存在 Settings.Global；暂存目录仍为 `Download/.OPAirShare-incoming`，完成文件继续通过 MediaStore 发布并授权 content URI。
