# Root 管理器与挂载兼容

模块沿用 Magisk / KernelSU / APatch 的 `customize.sh`、`post-fs-data.sh`、`service.sh` 与 `system/` 接口。管理器负责安装、开机执行、SELinux 规则加载和系统文件挂载。新版安装器不再把 KernelSU 环境变量或固定元模块目录作为安装前提。

## 支持方式与验证范围

| 环境 | 兼容策略 | 验证范围 |
| --- | --- | --- |
| KernelSU 及兼容分支 | 安装器接口、KSU BusyBox、实际 Root 域 | 原有 PJZ110 使用环境；新增分支仍需验收 |
| Magisk | 相同模块布局、Magisk BusyBox、`magisk` 域 | 主机模拟安装与工具调度；未实机验收 |
| APatch | 相同模块布局、APatch BusyBox、实际 Root 域 | 主机模拟安装与工具调度；未实机验收 |
| 内置挂载 / Magic Mount / OverlayFS | 检查系统可见文件；无需识别实现名称 | 主机模拟标准及分区搬移布局 |
| Mountify | 无固定目录检测；尊重挂载端配置 | 主机模拟无需元模块目录；真实 Mountify 待验收 |

其他实现若提供兼容的模块接口、所需工具和 `ksu` / `magisk` / `su` Root 域，也可使用该路径。无法确定 Root 域时会给出错误；不会向应用域或所有域授予权限。管理器的 Root 权限配置仍须允许驱动加载、Binder、全局挂载及访问模块文件。

## 实现

`bin/root-runtime.sh` 根据管理器安装变量优先选择对应 BusyBox，再检查其他已知位置及 PATH 中的 BusyBox，确认所需 applet 存在后调用。无合适 BusyBox 时尝试系统或 PATH 中的独立工具。工具返回失败会向上传递，不重复执行挂载；只读 remount 失败沿用回滚逻辑。

服务、控制脚本、回调挂载和 GMS 挂载进入 PID 1 的挂载命名空间，避免管理器给 Root shell 创建私有命名空间后仅局部生效。回调 APK 通过独立备份文件的 inode 和实际文件挂载点确认归属，不依赖 `/proc/self/mountinfo` 中特定的 `/adb/...` 根路径。

安装器根据 `id -Z` 的实际 Root 域，从 `bin/root-sepolicy.rule` 生成 `sepolicy.rule`；恢复环境无法获得该域时使用管理器环境提示。模板属于包完整性清单，生成文件在设备端创建。默认权限仍由安装器配置。

`bin/mount-check.sh` 在驱动和 Wi-Fi 修改前确认：

- Mosey 原生服务可执行，服务库和全部 APK split 可读。
- 权限、默认权限、hidden API 和 VINTF XML 与模块配置一致。
- 同时接受 `system/system_ext`、`system/product` 与安装器搬移后的 `system_ext`、`product` 来源布局。

检查不依赖挂载类型或元模块 ID，也不要求 OverlayFS 在 mountinfo 中出现特定字符串。它不代替现有 APK 摘要、驱动身份与服务运行状态检查。

## Mountify 排查

1. 确认挂载功能已启用且模块没有 `disable`、`remove`、`skip_mount` 标记。
2. 如果 Mountify 设置为只挂载列表中的模块，将模块 ID `opairshare` 加入其配置并重启。
3. 确认管理器没有对 OShare、Mosey 或 GMS 启用卸载模块功能。
4. 查看 `/data/adb/opairshare/boot-start.log`。`Mount check: missing...` 或 `configuration differs...` 后面的路径即缺失或冲突的系统视图。
5. 修正后重启，让 APK、权限和 VINTF 配置在系统扫描前生效。

可手动检查：

```sh
su -c 'sh /data/adb/modules/opairshare/bin/mount-check.sh /data/adb/modules/opairshare'
```

模块不会自行创建系统目录或替换管理器的整套挂载流程。开机后才临时挂载 APK 无法补齐本次系统启动时已完成的包、权限和服务配置扫描。

## WebUI

页面使用兼容 `ksu.exec(command, options, callback)` 的 Root WebUI 桥。KernelSU、支持该接口的 APatch 或独立宿主可承载页面；Magisk 管理器本身不提供 WebUI。未发现桥接接口时，页面会提示使用兼容宿主。

## 主机验证

```sh
python3 tests/test_root_runtime.py
python3 tests/test_compatibility.py
python3 tests/test_gms_unlock.py
python3 tests/test_verify.py
node tests/test-api.mjs
```

使用隔离文件树和命令替身验证工具选择、退出码传递、全局命名空间重入、Root 域规则生成、完整安装后的包校验、无元模块环境与分区搬移布局，以及挂载失败时阻止后续驱动操作。这些测试不加载设备驱动，也不代表其他管理器已完成实机验收。

接口参考：[Magisk 开发指南](https://topjohnwu.github.io/Magisk/guides.html)、[APatch 模块指南](https://apatch.dev/apm-guide.html)、[KernelSU 元模块说明](https://kernelsu.org/guide/metamodule.html)、[Mountify](https://github.com/backslashxx/mountify)。
