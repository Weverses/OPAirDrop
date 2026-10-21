# OPAirDrop 更新日志

## 1.0.6

- 移除 KernelSU 的执行脚本（Action）入口。
- 模块作者更新为 TimelessU，关于页名称统一为 OPAirDrop，补齐参考项目与组件致谢。
- WebUI 设置新增 AirDrop 显示名称，可保存自定义名称或恢复默认。
- 默认使用设备的 ro.vendor.oplus.market.enname（如 OnePlus 13），属性为空时回退到机型。
- 名称持久保存，接收广播和发送身份保持一致；待确认或传输中修改将在任务结束后更新广播。

安装更新后重启以加载新桥接。

## 1.0.5

- 新增 OShare 接管 AirDrop 接收：邀请确认、进度、拒绝、取消、完成后打开文件与保存失败重试。
- 跟随 OShare 接收开关：仅联系人与所有人可见均开启 AirDrop，关闭互传后停止新邀请；每次接收仍需手动确认，暂不校验 Apple 通讯录身份。
- 后台接收保持共存会话，待确认邀请、活动传输及文件保存期间防止无线会话被提前释放。
- 接收界面独立解析并使用原生 COUI 对话框；完成文件通过 MediaStore 发布，支持多文件与重名处理。

首次使用此版本需安装模块并重启，保留当前 PJZ110 / SDK37 / 内核适配要求。反向传输兼容性以接收验收记录为准。

## 1.0.4

- 移除服务启动时的文件清单与哈希校验，修复安装后 README.md、customize.sh 被清理导致 AWDL 无法启动。
- GMS 配置挂载明确使用 KernelSU BusyBox，修复 Toybox 只读 remount 失败。
- 修复 Oplus 独立 app_process 的 SQLite 默认同步模式异常，恢复 LSPosed 自动激活。
- 自动配置重试时复用已安装的相同桥接 APK，避免重复安装改变路径导致 DexKit native 库加载失败。

安装或更新后请重启，重新加载 GMS feature 和无线服务。

## 1.0.3

- 集成最新 OShare DexKit 2.3.0 动态解析，按 APK 内容指纹缓存语义角色，支持 base APK 与 code splits。
- 使用 release 构建 OShare，关闭调试，保留现有 APK 签名以支持覆盖升级。
- OShare 与 KSU 模块共享版本号和版本码，打包时核对 release 产物的版本信息。
- 保留内置 GMS 解锁、OShare 自动安装与激活、KernelSU 在线更新。

适配范围：一加 13 PJZ110、SDK37、内核 `6.6.118-android15-8-g9bc34d5b0c79-abogki537459655-4k`。OShare 动态解析不改变设备及内核要求。安装或更新后请重启。

## 1.0.2

- 接入 KernelSU 管理器的模块在线更新功能。
- 打包时自动生成 update.json、中文更新日志和 ZIP 的 SHA256 校验文件。
- 发布正式 GitHub Release 后，自动核验模块安装包并上传更新信息；也可手动重新运行发布流程。
- 保留内置 Unlock CN GMS v3.8，以及 OShare 自动安装、激活和作用域配置。

适配范围：一加 13 PJZ110、SDK37、内核 `6.6.118-android15-8-g9bc34d5b0c79-abogki537459655-4k`。需要 KernelSU、Magic Mount metamodule 和 LSPosed；Google 服务需在系统设置中开启。更新安装后请重启。

## 1.0.1

- 内置 fei-ke 的 Unlock CN GMS v3.8 功能，安装时生成配置副本，开机早期只读挂载。
- 保留其他 XML 配置内容，检测到独立旧解锁模块时自动停用并接管。

## 1.0.0

- 模块名称调整为 OPAirDrop，版本号改为语义化版本，使用中文模块描述。
- 自动检测 LSPosed、安装 OShare 桥接并启用主用户的 OShare、Mosey 作用域。
- 整合已校验的 r27 运行组件与最新用户态构建产物。
