# OShare AirDrop 桥接

连接 OShare 系统分享界面与 Mosey provider 的 LSPosed 模块。

## 构建

环境：JDK 17、Android SDK Platform 37。项目使用 Gradle wrapper 8.13、AGP 8.13.2 与 libxposed API 102.0.0。

```sh
./gradlew :app:assembleDebug
sh tests/run.sh
```

APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`，应用 ID 为 `com.tlsu.oshareairdrop`。

## 作用域

| 进程 | 职责 |
| --- | --- |
| `com.coloros.oshare` | 系统分享 Intent、设备列表与发送交互 |
| `com.google.android.mosey` | provider 调用、设备发现和传输回调 |

主机测试覆盖回调代理、发送任务、设备展示与发现频道选择。Android 测试入口为 `tests/run-android.sh` 和 `tests/run-appearance-android.sh`。

模块运行脚本位于仓库根目录的 `module/`。跨进程通信、任务归属和文件传递见 [技术实现说明](../docs/TECHNICAL.md#3-oshare-分享桥接)，完整构建流程见 [构建说明](../docs/BUILD.md#5-oshare-桥接-apk)。
