# OShare AirDrop 桥接

连接 OShare 系统分享界面与 Mosey provider 的 LSPosed 模块。OShare 的内部入口与设备属性通过 DexKit 2.3.0 动态解析。

## 构建

环境：JDK 17、Android SDK Platform 37。项目使用 Gradle wrapper 8.13、AGP 8.13.2 与 libxposed API 102.0.0。

```sh
./gradlew :app:assembleRelease
sh tests/run.sh
```

正式构建 APK 输出到 `app/build/outputs/apk/release/app-release.apk`，应用 ID 为 `com.tlsu.oshareairdrop`。release 关闭调试，沿用现有部署所使用的 Android debug keystore 签名，支持原版本覆盖升级；版本名与版本码从根目录 `module/module.prop` 读取。打包工具只接受与模块版本一致的 release 产物。

调试与现有设备测试脚本仍可使用 `./gradlew :app:assembleDebug`，输出到 `app/build/outputs/apk/debug/app-debug.apk`。

## 作用域

| 进程 | 职责 |
| --- | --- |
| `com.coloros.oshare` | 系统分享 Intent、设备列表与发送交互 |
| `com.google.android.mosey` | provider 调用、设备发现和传输回调 |

主机测试覆盖回调代理、发送任务、设备展示与发现频道选择。Android 测试入口为 `tests/run-android.sh` 和 `tests/run-appearance-android.sh`。

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
