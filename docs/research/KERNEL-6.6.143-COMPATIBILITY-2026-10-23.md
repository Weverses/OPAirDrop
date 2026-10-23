# 6.6.143 社区内核兼容性评估

核对日期：2026-10-23。范围：GitHub 公开源码、构建流程，以及本地 OPAirDrop v1.0.31 的四个驱动模块；没有刷写、加载驱动或进行实机收发。

## 结论

可以取消精确匹配 6.6.118 的脚本限制。对本次调查的 Android 15 / Linux 6.6 / SM8750 / 4 KB 社区内核，存在沿用现有驱动的明确技术路径；不能仅按 6.6.143 版本号宣告兼容所有社区构建。

是否必须重新编译取决于实际构建的符号 CRC、CFI、结构布局和厂商依赖。版本号变化本身不构成必须重编译的依据。

## 调查对象

- 当前项目 common 固定基线：OnePlusOSS/android_kernel_common_oneplus_sm8750，提交 `e1b346b6b4f4096eb342ae3684838a942fd6f6c4`，Makefile 为 6.6.118。
- 候选 common：palazik/android_kernel_common_oneplus_sm8750，分支 `6.6.143_oneplus13_coloros16`，Makefile 为 6.6.143。
- 候选构建流程：ShiinaMashiroqp/actions_oplus_sm8750_customrom，`master/.github/workflows/Build Kernel.yml`。流程设定 Android 15、6.6.143，并下载上述 common 分支。
- 此对象是调查样本，用户没有指定自己正在使用的社区内核。

源码链接：

- [官方 Makefile](https://github.com/OnePlusOSS/android_kernel_common_oneplus_sm8750/blob/e1b346b6b4f4096eb342ae3684838a942fd6f6c4/Makefile)
- [候选 Makefile](https://github.com/palazik/android_kernel_common_oneplus_sm8750/blob/6.6.143_oneplus13_coloros16/Makefile)
- [候选构建流程](https://github.com/ShiinaMashiroqp/actions_oplus_sm8750_customrom/blob/master/.github/workflows/Build%20Kernel.yml)

## 1. 模块加载规则支持跨补丁版本

官方基线和候选分支的 `kernel/module/version.c` 内容完全一致。本次完整下载文件的 SHA256 均为：

`59e095d2e241f81f9148ace8e62a557376ec74f904a349f27aa1504737165ac0`

其中：

- `same_magic()` 在模块携带 CRC 时，从第一个空格开始比较 vermagic，跳过开头的内核 release 字符串。
- `check_version()` 仍校验符号 CRC。
- `check_modstruct_version()` 仍校验 `module_layout`。
- 候选 `main.c` 仍调用这些校验，没有从调用处直接绕过。

因此，带 CRC 的 6.6.118 模块并不因 release 字符串不同而必然被 6.6.143 拒绝。其余 vermagic 标志与实际 ABI 仍须满足要求。

来源：[候选 version.c](https://github.com/palazik/android_kernel_common_oneplus_sm8750/blob/6.6.143_oneplus13_coloros16/kernel/module/version.c)、[候选 main.c](https://github.com/palazik/android_kernel_common_oneplus_sm8750/blob/6.6.143_oneplus13_coloros16/kernel/module/main.c)。

## 2. 配置与构建流程

两份 `gki_defconfig` 都启用 PREEMPT、MODVERSIONS、MODULE_UNLOAD、CFI_CLANG、MODULE_SIG、MODULE_SIG_PROTECT；页大小选择在该候选 ARM64 Kconfig 中默认为 4 KB。

源码配置存在以下差异：

| 项目 | 官方 6.6.118 基线 | 候选 6.6.143 |
| --- | --- | --- |
| 调度配置 | SLIM_SCHED、SCHED_CLASS_EXT | HMBIRD_SCHED |
| GKI_TASK_STRUCT_VENDOR_SIZE_MAX | defconfig 未显式指定 | 1024 |

这说明候选包含版本升级以外的定制。配置变化不直接证明驱动不兼容，但使“只比较版本号”的判断失效。

候选流程使用 ZyCromerZ Clang 19，应用其他可选补丁，主要编译 `Image`，再放入 AnyKernel3。所查看流程没有同步构建目标 WLAN、mac80211、Wonder 或 provider，也没有提供其与本项目的 ABI 检查结果。推断其设计依赖原有系统厂商模块继续工作；最终是否如此还需核对具体安装包。

流程还删除 `android/abi_gki_protected_exports_*`。这是保护导出列表的改动，不等价于取消符号 CRC 校验，也不能作为 ABI 兼容证明。

来源：[候选 defconfig](https://github.com/palazik/android_kernel_common_oneplus_sm8750/blob/6.6.143_oneplus13_coloros16/arch/arm64/configs/gki_defconfig)、[ARM64 Kconfig](https://github.com/palazik/android_kernel_common_oneplus_sm8750/blob/6.6.143_oneplus13_coloros16/arch/arm64/Kconfig)、上述构建流程。

## 3. 本地发行包的实际依赖

已核对本地 `dist/OPAirDrop-v1.0.31-PJZ110.zip`，包 SHA256：

`d3ab82a4d9f67ff1f76111d7a405881efa9f82e4baedfb549444553e6fd9ec90`

| 模块 | CRC 条目数 | 直接依赖概况 |
| --- | ---: | --- |
| qca_cld3_peach_v2 | 600 | cfg80211、CNSS、sched-walt、IOMMU、QMI 等 |
| mac80211 | 319 | cfg80211、libarc4 |
| wonder | 83 | cfg80211、mac80211 |
| oneplus13_wonder_provider | 11 | modinfo 未列出其他模块 |

所有模块的 vermagic 为 `6.6.118-android15-8-o-4k SMP preempt mod_unload modversions aarch64`；`module_layout` CRC 均为 `0x4e276f37`。条目数包含模块间和厂商依赖，不应把它们全部当作 GKI 核心符号。

完整风险链是：修改版 WLAN / Wonder → 系统 cfg80211、CNSS 等模块 → 新内核。仅证明新内核可以运行原厂 WLAN，仍不能证明全部修改版模块和回调路径兼容。

当前脚本 `kernel_matches()` 校验加载模块的 build ID，用于确认模块归属；不是对 6.6.118 内核 release 的硬性检测。

### 3.1 完整 ABI 描述与 payload CRC 对照

完整下载两份 `android/abi_gki_aarch64.stg`，提取顶层 `elf_symbol` 记录中的 name 与 crc，按名称逐项比较：

| 项目 | 结果 |
| --- | ---: |
| 官方 6.6.118 描述中的带 CRC 符号 | 9,197 |
| 候选 6.6.143 描述中的带 CRC 符号 | 9,543 |
| 两边共同符号 | 9,197 |
| 共同符号 CRC 不同 | 0 |
| 官方描述有、候选描述缺失 | 0 |
| 候选描述新增 | 346 |

候选与官方描述中的 `module_layout` CRC 都是 `0x4e276f37`，与当前四个模块相同。

进一步把本地发行包的导入 CRC 与候选描述、同包其他模块导出的 CRC 对照：

| 模块 | 导入 CRC 总数 | 候选描述覆盖且匹配 | 同包模块补充匹配 | 尚待系统依赖核对 |
| --- | ---: | ---: | ---: | ---: |
| mac80211 | 319 | 215 | 0 | 104 |
| oneplus13_wonder_provider | 11 | 11 | 0 | 0 |
| qca_cld3_peach_v2 | 600 | 437 | 1 | 162 |
| wonder | 83 | 66 | 10 | 7 |

上述已覆盖条目没有发现 CRC 冲突。同包补充包括 provider 的 `cnss_set_vendor_wonder_priv_data`，以及 Wonder 从 mac80211 导入的十个接口。

未覆盖项大量属于 cfg80211、CNSS、IPA、QMI、sched-walt 等模块，例如 `cfg80211_register_netdevice`、`cnss_wlan_enable`、`walt_get_cpus_taken`。这些不在该 common ABI 描述中，不能因此判定它们在实际设备上缺失；还需要目标系统对应模块的导出表。

这是“有条件支持”的正面证据，但不能替代最终工作流构建的 ABI 审计。原始逐项记录见 [kernel-6.6.143-abi-audit.json](kernel-6.6.143-abi-audit.json)。

完整输入描述 SHA256：

- 官方：`7dcdc38d116c79128c413df3df639c99ff4a83649eafcb26054f15b4b919724f`，7,866,854 字节。
- 候选：`2d7b82cf8787193a78e5bb94043a6bbcff67ec871eee309ee56bfec06a68c372`，8,014,403 字节。

来源：[官方 ABI 描述](https://github.com/OnePlusOSS/android_kernel_common_oneplus_sm8750/blob/e1b346b6b4f4096eb342ae3684838a942fd6f6c4/android/abi_gki_aarch64.stg)、[候选 ABI 描述](https://github.com/palazik/android_kernel_common_oneplus_sm8750/blob/6.6.143_oneplus13_coloros16/android/abi_gki_aarch64.stg)。

## 4. 推荐准入与验证路径

1. 对 Oplus / sun / Android 15-6.6 / 4 KB 类目标取消精确补丁版本拦截，以具体内核构建为单位验证。
2. 保留模块 CRC、CFI 和自身 payload 身份校验。
3. 在卸载原厂 WLAN 前，尽量完成静态 ABI 审计。目标 `Module.symvers` 或 vmlinux 导出表比仅看 uname 更有用。
4. 审计要覆盖目标内核、实际系统依赖模块和本项目四个模块；导出符号同名不代表 CRC 一致。
5. 实机依次验证四模块加载、普通 Wi-Fi、发现、双向传输、重复启停、共存、停止恢复与重启。
6. CRC 不匹配或 CFI / 结构接口不兼容时，针对该构建重编译、必要时适配；不要通过伪造版本或删除校验宣告支持。

Android 的稳定 KMI 只覆盖受约束的同一 Android/LTS 分支及受监控符号，不能自动覆盖任意社区补丁和厂商模块之间的接口。来源：[AOSP 稳定 KMI 文档](https://source.android.com/docs/core/architecture/kernel/stable-kmi)。

## 证据边界

已确认源码加载机制、配置差异、本地 payload 元数据，并完成两份仓库 ABI 描述的符号 CRC 对照；未取得指定社区发布包的最终 vmlinux / Module.symvers，未完成目标 ABI 全量匹配，也没有实机验证。

公开仓库中的 ABI 描述即使能完整读取，也首先是仓库记录，不等于套用工作流所有补丁和选项后得到的最终构建 ABI。
