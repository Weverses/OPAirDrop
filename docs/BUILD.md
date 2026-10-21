# 构建说明

本文从仓库根目录介绍各组件的构建方式，以及它们在 KernelSU 模块中的装配位置。WebUI、原生助手和 OShare 桥接可分别构建；Mosey 使用指定版本的 APK 作为输入，内核组件使用 Linux 上的官方平台源码树。

## 1. 环境

| 组件 | 环境与依赖 |
| --- | --- |
| 通用工具 | Git、Python 3.10+ |
| WebUI | Node.js 24、npm；依赖版本由 `webui/package-lock.json` 固定 |
| Android 桥接 | JDK 17、Android SDK Platform 37、Gradle 8.13、AGP 8.13.2 |
| 原生助手 | Android SDK Platform 36、Build Tools 36.0.0、NDK 28.2.13676358、JDK 17 |
| Mosey APK 构建 | Apktool、JDK 17、Android SDK Platform 36、Build Tools 36.0.0 |
| 内核 | Linux x86_64、ext4 构建卷、官方平台源码与随源码提供的工具链 |

原生构建工具支持 macOS 和 Linux。OShare 工程使用 `compileSdk = 37`、`minSdk = 35`、`targetSdk = 37`，依赖 `io.github.libxposed:api:102.0.0`。

在仓库根目录设置环境变量：

```sh
export OPAIRSHARE_REPO="$PWD"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
```

Linux 环境中，将 `ANDROID_HOME` 设为本机 SDK 目录，例如 `$HOME/Android/Sdk`。`java`、`javac` 与 `jar` 使用 JDK 17。

通过 SDK Manager 准备组件：

```sh
sdkmanager \
  "platform-tools" \
  "platforms;android-36" \
  "platforms;android-37" \
  "build-tools;36.0.0" \
  "ndk;28.2.13676358"
```

## 2. 本地输入

`local-inputs/` 用于存放从适配设备和对应系统版本取得的构建输入。模块装配使用以下文件：

| 输入 | 用途 |
| --- | --- |
| 原版 Mosey `base.apk` 与 splits | 系统包扫描、provider 认证和补丁构建基线 |
| `mosey_server` | Binder 原生服务 |
| `libmosey_daemon_ffi.so` | Mosey 无线协议实现 |
| 匹配设备的原厂 WLAN 模块 | 导入接口、导出符号与原厂恢复对照 |
| 同次内核构建的 `Module.symvers` | 模块 ABI 对照 |

当前 callback 构建器对应 Mosey60715。原版 APK、splits 和构建时使用的服务文件应来自同一套适配记录。`local-inputs/reference-r27/` 保存原平台的参考依赖与文件布局。

## 3. WebUI

```sh
cd "$OPAIRSHARE_REPO/webui"
npm ci
npm run check
npm run build
cd "$OPAIRSHARE_REPO"
node tests/test-api.mjs
```

产物为 `webui/dist/`。Vite 使用相对资源路径 `base: './'`，装配到模块的 `webroot/` 后由 KernelSU 加载。

| 文件 | 作用 |
| --- | --- |
| `src/App.vue` | 状态页、速度历史、设置、关于和日志页 |
| `src/api.ts` | KernelSU `ksu.exec` 适配与类型定义 |
| `src/style.css` | 布局与样式 |
| `package-lock.json` | npm 依赖锁定 |

## 4. 原生助手与 Wi-Fi 会话助手

```sh
cd "$OPAIRSHARE_REPO"
python3 tools/build-native.py \
  --sdk "$ANDROID_HOME" \
  --ndk "$ANDROID_NDK_HOME"
```

构建器以 `aarch64-linux-android31-clang` 编译 ARM64 程序，并通过 `javac`、D8 和 `jar` 生成 `app_process` 使用的 DEX JAR。

| 产物 | 源码 | 模块位置 |
| --- | --- | --- |
| `libopairshare_capability.so` | `native/capability.c` | `payload/native/` |
| `libopairshare_config.so` | `native/config.c` | `payload/native/` |
| `opairshare-socket-buffer` | `native/socket-buffer.c` | `payload/native/` |
| `session-wifi.jar` | `native/OPAirShareSessionWifi.java` | `payload/native/` |
| `build.json` | 构建器生成 | 本地构建记录 |

输出目录为 `build/native/`。`build.json` 保存各运行产物的构建记录。

## 5. OShare 桥接 APK

```sh
cd "$OPAIRSHARE_REPO/oshare"
./gradlew :app:assembleRelease
sh tests/run.sh
cd "$OPAIRSHARE_REPO"
```

产物路径：

```text
oshare/app/build/outputs/apk/release/app-release.apk
```

Gradle wrapper 使用 8.13，Android Gradle Plugin 使用 8.13.2。依赖仓库配置在 `oshare/settings.gradle.kts`，应用 ID 为 `com.tlsu.oshareairdrop`。 OShare 动态定位依赖固定为 `org.luckypray:dexkit:2.3.0`，Kotlin 标准库固定为 `2.2.21`；native 库随 APK 打包。匹配规则位于 `oshare/app/src/main/java/com/tlsu/oshareairdrop/OShareDexResolver.java`，指定 APK 的离线验证入口为 `oshare/tests/run-dexkit-android.sh`。

release 关闭调试，沿用现有部署使用的 Android debug keystore 签名以支持覆盖升级，保留反射及 DexKit 所需代码。OShare 的版本名、版本码从 `module/module.prop` 读取，打包工具核对 release 元数据后才复制 APK。调试设备测试需要先单独执行 `./gradlew :app:assembleDebug`。

主机测试覆盖回调代理、发送任务、设备展示、发现频道选择与 APK 内容指纹。`tests/run-android.sh` 和 `tests/run-appearance-android.sh` 提供 Android 设备测试入口。

## 6. Mosey 回调与发现补丁

先构建传输回调版本，再用它构建联网发现版本。两个构建器均修改传入的解码目录，每次构建使用单独的工作目录。

### 6.1 传输回调

将原版输入放到 `local-inputs/mosey/base.apk`，然后执行：

```sh
cd "$OPAIRSHARE_REPO"
mkdir -p build/mosey

apktool d local-inputs/mosey/base.apk \
  -o build/mosey/callback-decoded

python3 mosey/callback/build.py \
  --apk local-inputs/mosey/base.apk \
  --decoded build/mosey/callback-decoded \
  --output build/mosey/callback
```

主要产物：

```text
build/mosey/callback/Mosey60715-stage60-callback-unsigned.apk
build/mosey/callback/build.json
```

构建器对照 `smali-inputs.json`，修改 `ccu`、`cee`、`cen` 和 `dly`，重建 `classes2.dex`。

### 6.2 联网发现

解码上一阶段生成的回调 APK：

```sh
CALLBACK_APK="$OPAIRSHARE_REPO/build/mosey/callback/Mosey60715-stage60-callback-unsigned.apk"

apktool d "$CALLBACK_APK" \
  -o build/mosey/discovery-decoded

python3 mosey/discovery/build.py --help
```

按构建器的参数说明，传入回调 APK、解码目录、回调构建记录中的输入标识和输出目录，并启用 `--transfers`。回调构建记录位于 `build/mosey/callback/build.json`，输出目录使用 `build/mosey/discovery/`。

最终 APK 位于 `build/mosey/discovery/base.apk`。该步骤重建发现与传输调用点，并将 `dev.evermore.opairshare.DiscoveryPolicy` 编译为新增的 `classes4.dex`。

模块中的原版 APK 用于包扫描与 provider 认证，构建后的 APK 放在 `payload/app-callback/base.apk`，由运行脚本在开机完成后挂载。

## 7. 内核组件

### 7.1 固定上游基线

同步脚本使用 OnePlusOSS 的 `oneplus/sm8750` 分支与 `oneplus_13_b.xml` 清单，并在 local manifest 中固定 common、msm-kernel 和 modules/devicetree 的源码版本。具体版本配置位于 `tools/kernel/sync-source.sh`。

### 7.2 准备平台源码

在 Linux ext4 构建卷中设置：

```sh
export OPAIRSHARE_BUILD_ROOT=/mnt/opairshare-build
export OPAIRSHARE_TOOLS_DIR="$OPAIRSHARE_REPO/tools/kernel"

bash "$OPAIRSHARE_TOOLS_DIR/sync-source.sh"
bash "$OPAIRSHARE_TOOLS_DIR/build-baseline.sh"
bash "$OPAIRSHARE_TOOLS_DIR/build-wlan-baseline.sh"
```

目录关系：

```text
$OPAIRSHARE_BUILD_ROOT/
├── logs/
│   ├── source-lock.xml
│   └── checkout-ready
├── baseline-wlan/
└── opairshare/
    ├── kernel_platform/
    └── vendor/
```

`source-lock.xml` 记录同步后的完整提交清单，`baseline-wlan/` 接收原厂 WLAN 基线构建产物。

### 7.3 集成源码

内核集成按“基础补丁 → 当前覆盖层 → 构建配置”进行：

1. 根据 `kernel/patches/` 的修改区域合入 Wonder 构建目标、QDF mode、HDD 生命周期、session 和 DP 接口。
2. 将 `kernel/driver/` 中的当前文件放入 `kernel/source-map.json` 指定的 `kernel_path`。索引中的路径以平台构建树的 `kernel_platform/` 为根，`vendor/` 对应该构建树中的厂商源码布局。
3. 将 `kernel/wonder/` 集成到 `vendor/oplus/kernel/wifi/wonder/`。
4. 将 `kernel/provider/` 放到 `kernel_platform/opairshare-route-b-provider/`。
5. 对齐外部源码树中相关的头文件声明、调用点、导出符号和 `opairshare` 参数名称。

基础补丁与当前覆盖层包含相同区域的不同时期改动，集成时以覆盖层中的当前实现为准。`agg-core.h` 与 `collect-core.h` 是主机测试助手，其逻辑已内联进 bridge 头文件。

构建配置使用 `sun perf` / `peach-v2`，WLAN 功能项为：

```text
CONFIG_DRIVER_PASSTHRU_MODE=y
CONFIG_WONDER_SUPPORT=y
```

Wonder 在平台的 Oplus 模块构建图中与目标 `mac80211`、`cfg80211` 依赖一起构建；`kernel/wonder/Makefile.include` 中的头文件和 symvers 路径随平台输出目录配置。

### 7.4 构建 WLAN、provider 与 Wonder

```sh
bash "$OPAIRSHARE_TOOLS_DIR/build-wlan-feature-check.sh"
bash "$OPAIRSHARE_TOOLS_DIR/build-route-b-provider.sh"
```

第一个入口临时启用两项 WLAN 功能并调用 Kleaf；第二个入口构建独立 provider 与绑定探针。Wonder 和 `mac80211` 使用集成后的平台模块构建入口：

```sh
cd "$OPAIRSHARE_BUILD_ROOT/opairshare"
./kernel_platform/oplus/build/oplus_build_module.sh sun perf vendor/oplus/kernel/wifi
```

WLAN 主产物位于：

```text
kernel_platform/bazel-bin/vendor/qcom/opensource/wlan/qcacld-3.0/sun_perf_qca_cld_peach-v2/qca_cld3_peach_v2.ko
```

provider 与绑定探针的 Bazel target 分别是：

```text
//opairshare-route-b-provider:opairshare_wonder_provider
//opairshare-route-b-provider:opairshare_route_b_probe
```

模块装配需要 `qca_cld3_peach_v2.ko`、`mac80211.ko`、`wonder.ko` 与 `opairshare_wonder_provider.ko`。

### 7.5 ABI 与构建身份

检查原厂模块导入，并与同次构建的 symvers 对照：

```sh
cd "$OPAIRSHARE_REPO"
python3 tools/kernel/module_abi.py \
  --out build/stock-module-abi.json \
  inspect local-inputs/stock/qca_cld3_peach_v2.ko

python3 tools/kernel/module_abi.py \
  --out build/abi-check.json \
  check \
  --stock build/stock-module-abi.json \
  --symvers /path/to/common/Module.symvers /path/to/wlan/Module.symvers
```

`module_exports.py` 用于查看模块导出符号：

```sh
python3 tools/kernel/module_exports.py --dump /path/to/module.ko
```

装配模块时，用当次构建的四个内核模块更新 `bin/kernel-identity.sh` 中的 `NOTE_*`。模板中的 `BUILD_REQUIRED` 表示待填入的构建标识，`STOCK_NOTE_*` 对应设备原厂恢复模块。获取这些信息的工具随目标内核工具链提供。

## 8. 模块装配

### 8.1 当前发行包

使用已校验的 `local-inputs/reference-r27/` 内核与 Mosey 运行组件，配合仓库最新用户态构建产物生成 OPAirDrop：

```sh
python3 tools/build-lsposed.py --sdk "$ANDROID_HOME"
python3 tests/test_lsposed.py
python3 tools/package.py
```

执行前完成上文的 WebUI、原生助手和 OShare release APK 构建。输出为 `dist/OPAirDrop-v1.0.4-PJZ110.zip`、`.zip.sha256`、`update.json` 和 `changelog.md`。模块名称、版本号、描述及在线更新地址以 `module/module.prop` 为准。

打包器逐项核验参考二进制的 SHA256，保留其实际导出的内核模块名、sysfs 接口及回调属性名，填入对应的内核 build-id。模块路径、状态目录和最新用户态助手继续使用当前仓库名称。OShare 对参考回调属性提供兼容读取。本流程不会重编译内核或 Mosey 补丁；来源记录位于 ZIP 内的 `build-info.json`。

安装脚本通过 Android 包管理器的标准输入安装 `companion/OShareAirDrop.apk`，通过 `app_process` 运行 `lsposed-setup.jar`。配置器支持旧式模块 ID 表和新版按用户启用表；修改前使用 SQLite 一致性备份，随后通过事务启用桥接并补齐主用户的两个作用域，保留其他模块及已有作用域。LSPosed 在重启后重新加载配置。未知表结构会拒绝修改，安装失败会保留日志并安排开机重试。

v1.0.1 集成 Unlock CN GMS v3.8。`bin/setup-gms.sh` 在安装时读取 `bin/gms-paths.txt` 的 11 个候选路径，通过 `bin/gms-filter.awk` 仅移除两项目标 feature，生成 `gms-unlock/overlays/`、挂载清单和动态 SHA256 清单。`post-fs-data.sh` 在 Android 读取权限 XML 前调用 `bin/gms-unlock.sh`，检查目标路径后使用 KernelSU BusyBox 进行只读 bind mount，失败信息写入 `/data/adb/opairshare/gms-unlock.log`。生成的配置来自安装设备，不预置到发行 ZIP 中；输入包来源记录随包保存在 `third-party/unlock-cn-gms/`。

运行 `python3 tests/test_gms_unlock.py` 检查 XML 保留、11 个位置、无匹配配置、重复安装、独立模块接管所需的已清理配置、目标路径、挂载失败及只读失败回滚路径。测试通过隔离的文件系统与 mount 替身执行。v1.0.4 起服务启动不再遍历文件清单或校验组件哈希；APK 原版/回调挂载切换仍按对应版本识别文件，避免混用认证状态。

### 8.2 发布与在线更新

KernelSU 读取 `module.prop` 的 `updateJson`，该地址指向 GitHub 最新正式 Release 的 `update.json` 附件。JSON 包含 `version`、整数 `versionCode`、`zipUrl` 和 `changelog`；下载和日志链接固定到对应版本标签，避免指向其他版本。`tools/package.py` 自动从实际 ZIP 内的版本信息及 `CHANGELOG.md` 生成这些文件，校验全部文件的 SHA256，遇到版本、标签、模块 ID、文件名或更新源不一致时拒绝生成。

发布步骤：

1. 更新 `module/module.prop` 中的语义化 `version`、递增的 `versionCode`，并在 `CHANGELOG.md` 添加对应版本段落。
2. 运行 `python3 tests/test_release.py`、`python3 tools/check.py` 和 `python3 tools/package.py`。
3. 将代码及 `.github/workflows/ksu-update.yml` 推送到主分支；在 GitHub 创建标签与模块版本一致的草稿 Release（例如 `v1.0.2`），上传 `dist/` 下的 ZIP、`.zip.sha256`、`update.json` 和 `changelog.md`，然后发布为正式 Release。
4. `.github/workflows/ksu-update.yml` 在正式发布后，下载该版本 ZIP，核对其完整性和版本信息，重新生成并上传更新元数据及校验文件。需要重新生成时，在 Actions 中手动运行并填写现有正式 Release 标签。流程不会处理草稿或预发布版本，不会编译依赖本地输入的内核与 APK。

也可在发布前独立核验：

```sh
python3 tools/release.py --zip dist/OPAirDrop-v1.0.2-PJZ110.zip --out dist --tag v1.0.2
```

首次 Release 发布前，线上更新入口不可访问；此前不含 `updateJson` 的旧版本需要手动安装新版一次。未发布时，本地生成的更新文件是待上传产物。

### 8.3 组件手动装配

当前构建入口按组件提供，模块使用以下布局装配：

```text
module/
├── module.prop
├── customize.sh
├── service.sh
├── post-fs-data.sh                  ← 内置 GMS 解锁的开机早期挂载
├── uninstall.sh
├── sepolicy.rule
├── bin/
├── webroot/                         ← webui/dist/
├── companion/                       ← OShare 桥接 APK
├── payload/
│   ├── kernel/                      ← 四个内核模块
│   ├── native/                      ← 四个原生/Java 助手
│   └── app-callback/base.apk         ← 最终 Mosey 补丁 APK
└── system/
    ├── product/etc/permissions/
    └── system_ext/
        ├── bin/mosey_server
        ├── lib64/libmosey_daemon_ffi.so
        ├── priv-app/MoseyApp/        ← 原版 APK 与 splits
        └── etc/                     ← 权限及 VINTF 配置
```

建议在 `build/package/module/` 中装配，保留仓库内 `module/` 作为源模板：

```sh
cd "$OPAIRSHARE_REPO"
mkdir -p build/package/module
cp -R module/. build/package/module/
mkdir -p build/package/module/webroot build/package/module/payload/native
cp -R webui/dist/. build/package/module/webroot/
cp build/native/libopairshare_capability.so build/package/module/payload/native/
cp build/native/libopairshare_config.so build/package/module/payload/native/
cp build/native/opairshare-socket-buffer build/package/module/payload/native/
cp build/native/session-wifi.jar build/package/module/payload/native/
```

其余产物按布局表放入对应目录。OShare APK 固定放在 `companion/OShareAirDrop.apk`，自动激活助手放在 `payload/native/lsposed-setup.jar`。

装配后为原版 APK、补丁 APK 和模块运行文件生成安装器与运行脚本所需的文件清单。模块内路径以装配目录为根；清单覆盖模块配置、入口脚本、`bin/`、`webroot/`、`payload/` 和 `system/`。文件权限按 `customize.sh` 设置，ZIP 中保留 Unix 文件模式。

在装配目录完成校验后打包：

```sh
sh build/package/module/bin/verify.sh build/package/module
mkdir -p dist
cd build/package/module
zip -r ../../../dist/OPAirShare.zip .
cd "$OPAIRSHARE_REPO"
```

## 9. 检查与验收

源码与主机回归：

```sh
python3 tools/check.py
python3 tests/test_settings.py
python3 tests/test_history.py
node tests/test-api.mjs
sh oshare/tests/run.sh
```

设备验收按以下顺序记录：

1. 安装模块，重启后读取 `control.sh status` 与 WebUI 三项服务状态。
2. 分别在 STA 5745 MHz 与 5200 MHz 下打开分享界面，记录发现设备、无线参数和普通 Wi-Fi 连通性。
3. 双向传输文件，核对接收文件的大小与内容，检查进度、取消、连续发送和重新进入界面的行为。
4. 关闭分享界面，检查 radio、matcher、pending 与共存快照的释放状态。
5. 使用 `control.sh stop` 停止服务栈，核对原厂模块与 Wi-Fi 恢复状态。
6. 查看速度历史与导出日志，记录系统版本、模块版本、构建配置和测试结果。

检查命令和状态字段的含义见 [技术实现说明](TECHNICAL.md)。
