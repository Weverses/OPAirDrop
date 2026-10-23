# OPAirDrop 模块特性说明

OPAirDrop 是一个 Root 模块，为一加 13（PJZ110）等 Oplus 设备开启 AirDrop，让 Android 与 iPhone、iPad、Mac 之间可以直接互传文件。

## 主要特性

### 1. AirDrop 收发

- 在系统分享面板中选择文件，即可看到附近的 Apple 设备并发送。
- 接收邀请、进度和完成提示接入系统通知；接收文件保存到 `Download/OPAirShare`，图片、视频分别保存到 `Pictures/OPAirShare`、`Movies/OPAirShare`。
- 每次接收都需要手动确认，不校验 Apple 联系人身份。

### 2. 三种 AirDrop 分享入口

在 WebUI 中选择，无需重启：

- **Quick Share**：由 Google Quick Share 管理 AirDrop 收发。
- **OShare**：由系统互传管理 AirDrop 收发、名称和保存位置。
- **二者共存**（默认）：两个入口都能发送，后台接收由 OShare 展示。

切换时会等待当前传输和文件保存完成，空闲后再应用。

### 3. 后台持续接收

模块服务会在开机后自动启动并保持后台可见，不需要打开分享页面，也不受系统互传的接收开关和十分钟时限影响。关闭模块后停止新邀请，正在进行的任务继续完成。

### 4. 原生实况通知

接收进度接入 OShare 原生卡片模板，支持状态栏胶囊、实况进度和完成动画；进度环可以直接取消当前接收。系统不支持或卡片创建失败时，自动回退到普通通知和弹窗。

### 5. Wi-Fi 共存

- AWDL 固定使用信道 149，无需手动开关。
- 按当前 Wi-Fi 连接的 2.4 / 5 / 6 GHz 频段自动选择共存策略。
- 网络变化时按任务状态释放和重建会话；传输期间按需关闭 STA 省电。

### 6. WebUI 管理界面

在支持模块 WebUI 的 Root 管理器或兼容宿主中打开，包含：

- 服务状态（AWDL、Quick Share、OShare）。
- 实时速度与传输历史（发送/接收字节、峰值与平均速度）。
- AirDrop 名称和接收保存位置设置。
- 日志查看与导出（导出到 `Download/OPAirShare`）。

### 7. 其他

- **内置 GMS 解锁**：安装时移除国行系统的 Google 服务限制声明，卸载并重启后恢复原配置。
- **自动安装桥接**：检测到 LSPosed 时自动安装并启用 OShare 桥接，配置 `com.coloros.oshare` 和 `com.google.android.mosey` 作用域。
- **在线更新**：支持在 KernelSU 管理器中检查更新、查看日志并覆盖安装。

## 使用条件

| 项目 | 要求 |
| --- | --- |
| 设备 | Oplus 品牌、sun 平台，已验证 PJZ110 |
| 系统 | ColorOS / Oplus 系统，已验证 SDK37，不限制 SDK |
| 内核与驱动 | 已验证 6.6.118；安装不限制版本，驱动 ABI 仍须兼容 |
| 模块环境 | KernelSU 及兼容分支 / Magisk / APatch；内置或外部系统挂载功能可用 |
| 桥接 | LSPosed（OShare / Mosey 作用域） |
| 其他 | 已开启 Google 服务，连接 Wi-Fi |

设备准入检查 Oplus 品牌与 sun 平台。元模块名称及固定目录不作为安装前提，Mountify 等实现按实际系统文件可见性检查。新增管理器与挂载组合尚待实机验证，详情见 [Root 与挂载兼容](ROOT-MOUNT-COMPATIBILITY.md)。其他平台参考 [平台迁移指南](PORTING.md)。通过检测不代表其他机型已完成固件和实际收发验证。

## 已知限制

- 其他机型、其他 ColorOS 版本尚未验证，需要从内核和系统分享服务开始适配。
- 已真机验证的场景有限（Mac 发送文本文件、OShare 接收确认与保存），多文件、大文件、锁屏和异常恢复仍在验证中。
- 本项目为独立社区项目，与 Google、Apple、Oplus 无隶属或合作关系。
