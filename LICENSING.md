# 开源许可

OPAirShare 默认采用 **GNU GPL v3.0**，完整条款见 [LICENSE](LICENSE)。项目的内核适配、独立原生助手和第三方源码按下面的范围授权。

## 项目代码

| 范围 | 协议 |
| --- | --- |
| 项目维护的运行脚本、构建工具、测试、Mosey 补丁工具、OShare 桥接与文档 | GPL-3.0-only |
| WebUI 中本项目编写的代码与修改 | GPL-3.0-only，随附上游声明 |
| `native/config.c`、Wi-Fi 会话助手及其项目构建文件 | GPL-3.0-only |
| `native/capability.c`、`native/socket-buffer.c` | GPL-2.0-only，沿用文件声明 |
| 本项目新增的内核适配代码 | GPL-2.0-only |
| 引用的 WLAN、Wonder 和其他第三方源码 | 按文件中的许可证与版权声明 |

GPLv3 允许使用、研究、修改和商业分发。分发项目代码或修改版时，应保留许可与版权声明，并按协议提供对应源码。接收者继续享有协议授予的使用和修改权利。

内核适配按 GPLv2 组件维护；用户态工具、应用和文档按上表单独授权。文件内已有的 SPDX 标识和上游版权声明继续适用。

## 第三方代码

Qualcomm WLAN 文件中的 ISC 声明、Wonder 的 GPLv2 声明，以及 WebUI 参考代码和依赖的 Apache 2.0 / MIT 声明随源码保留。具体来源、版本和修改说明见 [第三方声明](THIRD_PARTY_NOTICES.md)。

协议全文和第三方许可文件位于 [LICENSES](LICENSES/README.md)。

## 分发时附带的文件

发布源码时，附带根目录 `LICENSE`、本说明、第三方声明和各组件适用的许可证。发布模块时，随包保留所包含组件的许可文件，并提供对应版本的项目源码。

外部构建输入使用其各自的提供与分发条款。`local-inputs/` 用于本机准备设备依赖，开源仓库发布项目源码、适配工具与许可文件。
