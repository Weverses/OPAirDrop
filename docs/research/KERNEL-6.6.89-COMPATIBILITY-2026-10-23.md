# 官方 6.6.89 内核兼容性评估

日期：2026-10-23。范围：一加官方 SM8750 源码、仓库 ABI 描述和当前 OPAirDrop v1.0.31 模块；没有实机刷写、模块加载或传输测试。

## 结论

官方 6.6.89 可以列为有明确静态兼容依据的实验目标。没有发现仅因补丁版本回退而必须重新编译当前四个模块的证据：仓库 ABI 描述覆盖到的导入 CRC 全部匹配，也没有引用 6.6.118 描述新增而 6.6.89 描述缺失的符号。

这不是目标固件的最终兼容认证。系统中的 cfg80211、CNSS 等依赖以及实际驱动加载、传输和恢复仍须核对。

## 官方来源

已定位 OnePlusOSS/android_kernel_common_oneplus_sm8750 的提交：

`5a5534458764ecedd480533dbdb9f13d4d45af25`

该提交说明对应 CPH2649 / CPH2653 / CPH2655 的 `16.0.7.201(EX01)` 和 PJZ110 的 `16.0.7.201(CN01)`，基线为 `android15-6.6-2025-06_r35`。Makefile 的 VERSION / PATCHLEVEL / SUBLEVEL 为 6 / 6 / 89，确认是官方 6.6.89 源码。

- [官方提交](https://github.com/OnePlusOSS/android_kernel_common_oneplus_sm8750/commit/5a5534458764ecedd480533dbdb9f13d4d45af25)
- [官方 Makefile](https://github.com/OnePlusOSS/android_kernel_common_oneplus_sm8750/blob/5a5534458764ecedd480533dbdb9f13d4d45af25/Makefile)
- [官方 ABI 描述](https://github.com/OnePlusOSS/android_kernel_common_oneplus_sm8750/blob/5a5534458764ecedd480533dbdb9f13d4d45af25/android/abi_gki_aarch64.stg)

定位线索来自 Hipuu/OnePlus13-KernelBuilder 的固定 manifest，但版本和来源已回到 OnePlusOSS 的上述不可变提交核实，没有将社区配置当成官方源码的证明。

## 模块加载机制

6.6.89 的 `kernel/module/version.c` 与当前 6.6.118 基线完全相同，文件 SHA256 均为：

`59e095d2e241f81f9148ace8e62a557376ec74f904a349f27aa1504737165ac0`

模块携带 CRC 时，`same_magic()` 跳过 release 字符串，比较余下 vermagic 标志；符号 CRC 与 `module_layout` 校验仍然保留。因此，6.6.118 编译的模块不一定因运行内核为 6.6.89 而被拒绝。

来源：[官方 version.c](https://github.com/OnePlusOSS/android_kernel_common_oneplus_sm8750/blob/5a5534458764ecedd480533dbdb9f13d4d45af25/kernel/module/version.c)。

## 配置对照

两份官方 `gki_defconfig` 均保留 `-4k` 命名，启用 PREEMPT、SLIM_SCHED、SCHED_CLASS_EXT、MODVERSIONS、MODULE_UNLOAD、CFI_CLANG、MODULE_SIG 和 MODULE_SIG_PROTECT。

在 defconfig 文本层面，6.6.118 相对该 6.6.89 仅增加四项：

- CONFIG_NETFILTER_XTABLES_COMPAT=y
- CONFIG_ZRAM_WRITEBACK=y
- CONFIG_ZRAM_MULTI_COMP=y
- CONFIG_CRYPTO_SHA1_ARM64_CE=y

这比先前调查的 HMBIRD 社区 6.6.143 样本在 defconfig 文本上更接近现有基线。但 defconfig 差异少不代表源代码、最终完整 .config 或厂商模块接口完全一致。

来源：[6.6.89 配置](https://github.com/OnePlusOSS/android_kernel_common_oneplus_sm8750/blob/5a5534458764ecedd480533dbdb9f13d4d45af25/arch/arm64/configs/gki_defconfig)、[6.6.118 配置](https://github.com/OnePlusOSS/android_kernel_common_oneplus_sm8750/blob/e1b346b6b4f4096eb342ae3684838a942fd6f6c4/arch/arm64/configs/gki_defconfig)。

## 完整 ABI 描述对照

完整读取两个不可变官方提交的 `android/abi_gki_aarch64.stg`，提取顶层 elf_symbol 记录的名称与 CRC：

| 项目 | 结果 |
| --- | ---: |
| 6.6.89 带 CRC 符号 | 8,998 |
| 6.6.118 带 CRC 符号 | 9,197 |
| 共同符号 | 8,998 |
| 共同符号 CRC 变化 | 0 |
| 仅 6.6.118 描述中存在 | 199 |
| 仅 6.6.89 描述中存在 | 0 |

两个描述的 `module_layout` CRC 都为 `0x4e276f37`，与当前四个模块相同。

把当前本地 `dist/OPAirDrop-v1.0.31-PJZ110.zip` 的导入 CRC 对照 6.6.89 描述，再用同包模块导出表补充：

| 模块 | 导入 CRC 总数 | 官方 6.6.89 描述覆盖且匹配 | 同包补充匹配 | 尚待系统依赖核对 |
| --- | ---: | ---: | ---: | ---: |
| mac80211 | 319 | 215 | 0 | 104 |
| oneplus13_wonder_provider | 11 | 11 | 0 | 0 |
| qca_cld3_peach_v2 | 600 | 437 | 1 | 162 |
| wonder | 83 | 66 | 10 | 7 |

所有已覆盖条目无 CRC 冲突。四个模块均未引用上述 199 个只在 6.6.118 描述中存在的符号；因此，本次没有发现向旧版本加载时新增的已知核心符号缺口。未覆盖系统依赖数量与之前的 6.6.143 调查相同，不能将它们当作缺失符号或已验证项。

6.6.89 ABI 描述：7,791,263 字节，SHA256：

`39ebb1b51422eb00cbcc670ef839bce3e0c30f2685476e9d8cea31d3b59a06ca`

本地包 SHA256：

`d3ab82a4d9f67ff1f76111d7a405881efa9f82e4baedfb549444553e6fd9ec90`

逐项数据见 [kernel-6.6.89-abi-audit.json](kernel-6.6.89-abi-audit.json)。这仅是仓库记录的 CRC 对照，不证明目标构建的完整结构布局、CFI、固件接口或运行行为一致。

## 判断边界

需要分别判断：

1. 现有 6.6.118 模块能否直接加载在目标 6.6.89 内核及其系统依赖模块上。
2. 若直接复用失败，能否以官方 6.6.89 完整平台源码重新编译、移植本项目修改。

官方开源为第二条提供了可操作的源码基础，但不能自动证明第一条成立。向旧版本兼容还必须核对较新模块是否引用了旧内核没有的符号。

最终判断需要目标 vmlinux / Module.symvers，以及实际 cfg80211、CNSS、IPA、QMI、sched-walt 等依赖模块。仓库 ABI 描述覆盖不到的符号不等于在设备上缺失。
