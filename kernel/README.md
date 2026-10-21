# 内核适配

本目录包含 OPAirShare 的 Qualcomm WLAN 源码覆盖层、Wonder 无线接口、独立绑定 provider 和平台基础补丁。构建依托官方 Linux 平台源码树，目标为 `sun perf` / `peach-v2`。

## 目录

| 路径 | 内容 |
| --- | --- |
| `driver/` | 当前 WLAN、WMA、HDD、DP 与共存策略源码 |
| `source-map.json` | 覆盖层在平台源码树中的文件位置 |
| `wonder/` | Wonder 与 mac80211 接口实现 |
| `provider/` | component 绑定桥与绑定探针 |
| `patches/` | 平台构建、枚举、生命周期和数据路径的基础集成补丁 |

当前覆盖层合并了 TX、native RX、共存与会话修复。同名文件以当前覆盖层实现为准，基础补丁按修改区域集成。

`agg-core.h`、`collect-core.h` 是已内联进 bridge 头文件的主机测试助手。其余覆盖层文件的位置由 `source-map.json` 的 `kernel_path` 指定。

## 上游基线

官方源码使用 OnePlusOSS 的 `oneplus/sm8750` 分支与 `oneplus_13_b.xml` 清单。common、msm-kernel 和 modules/devicetree 的版本由 `tools/kernel/sync-source.sh` 固定。

构建工具位于 `tools/kernel/`。`OPAIRSHARE_BUILD_ROOT` 指定 ext4 构建卷，卷内源码目录为 `opairshare/`；`OPAIRSHARE_TOOLS_DIR` 指定构建工具目录。

provider 放入平台树的 `kernel_platform/opairshare-route-b-provider/`，Bazel target 为：

```text
//opairshare-route-b-provider:opairshare_wonder_provider
//opairshare-route-b-provider:opairshare_route_b_probe
```

完整流程见 [构建说明](../docs/BUILD.md#7-内核组件)，运行机制见 [技术实现说明](../docs/TECHNICAL.md#6-无线驱动适配)。

迁移到其他 SoC 或 WLAN 驱动版本时，参考 [平台迁移指南](../docs/PORTING.md)。
