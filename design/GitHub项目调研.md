# GitHub 项目调研：Mac 与 Linux 服务器监控

核对日期：2026-10-28。依据公开仓库 README、部分源文件、GitHub 元数据和官方文档；没有安装或运行这些项目，也没有测量其资源占用。

## 结论

最贴近需求的现成组合是 Beszel + BeszelBar。Beszel 提供 Linux 采集端、集中服务、历史数据与告警；BeszelBar 提供原生 Mac 菜单栏。要达到前面效果图中的桌面挂件、完整 GPU 细节和统一的数据新鲜度显示，仍需开发与验证。

推荐把 Beszel 作为服务端优先验证对象，Mac 客户端按自己的产品需求实现。将已有项目纳入方案后，可以复用服务器采集、历史与告警，减少从头实现的工作；性能以实际部署测试决定，不能用项目宣传代替测试。

## 候选比较

| 项目 | 已核对的能力 | 与本项目的关系 |
| --- | --- | --- |
| [henrygd/beszel](https://github.com/henrygd/beszel) | Go，Hub + Agent；CPU、内存、磁盘、网络、传感器、GPU；历史与告警；MIT | 服务端优先验证对象；需要一个常在线 Hub，可和一台现有 Linux 同机 |
| [Loriage/BeszelBar](https://github.com/Loriage/BeszelBar) | SwiftUI + AppKit；连接 Beszel Hub；多服务器菜单栏、温度、容器、告警；10–300 秒轮询；macOS 14+ | 接近菜单栏入口；当前源文件未发现桌面挂件实现；GPU 数据模型与展示需补齐 |
| [oxy86/argusdesk-macos](https://github.com/oxy86/argusdesk-macos) | 原生 Mac；SSH 读取 Linux；菜单栏健康摘要、独立窗口、通知；可选 NVIDIA GPU 使用率与显存；MIT | SSH 方案参考。远端默认 60 秒，本地默认 5 秒；无专用采集端。不能直接视为已有完整温度与桌面挂件支持 |
| [byrencheema/airstats](https://github.com/byrencheema/airstats) | Mac 本机监控；菜单栏、展开面板、桌面挂件；指标层与界面分离；MIT；Apple Silicon、macOS 14+ | 挂件、界面分层和性能优化参考；现有说明面向本机，远程 Linux 数据接入需要增加 |
| [exelban/stats](https://github.com/exelban/stats) | Mac 本机 CPU/GPU/内存/网络/温度等，菜单栏及系统小组件；MIT；当前 README 也有 Mac 远程上报说明 | 成熟的本机监控参考；所述远程上报面向 Mac，不等于已覆盖本项目的 Linux 采集端 |

## Beszel 的关键核对结果

- 检查时最新公开版本为 [v0.21.1](https://github.com/henrygd/beszel/releases/tag/v0.21.1)。正式实施使用固定版本及校验值，避免随着 main 分支变化而无意改变行为。
- [官方架构](https://github.com/henrygd/beszel/blob/main/readme.md)包含 Hub 和每台主机上的 Agent。Mac 客户端加入后，共有三个运行组件；不应隐去 Hub 的占用。
- Hub 和 Agent 均支持单程序安装，Linux 不必为了监控额外引入 Docker。参见 [Hub 安装](https://beszel.dev/guide/hub-installation)与 [Agent 安装](https://beszel.dev/guide/agent-installation)。
- [v0.21.1 常规更新代码](https://github.com/henrygd/beszel/blob/v0.21.1/internal/hub/systems/system_manager.go)的默认周期为 60 秒。[实时订阅代码](https://github.com/henrygd/beszel/blob/v0.21.1/internal/hub/systems/system_realtime.go)的默认实时周期为 1 秒。二者不能混为一谈。
- 要实现本项目“可见时 5 秒、后台较低频”的省资源策略，需要在 Hub 的实时请求和缓存周期上做可配置适配，同时验证 Agent 内部 GPU 采样周期。只把 Mac 重绘改成 5 秒，并不能保证远端负担也降到 5 秒。
- [GPU 官方说明](https://beszel.dev/guide/gpu)列出 NVIDIA、AMD、Intel 等采集方式；NVIDIA NVML 仍标注为实验性。官方提示它可能比 nvidia-smi 更有利于 GPU 空闲省电，是否适合目标驱动仍需实测。
- 容器、SMART、系统服务等不属于首版必要采集项，部署时按官方支持的设置逐项关闭或不启用，验证后再决定保留哪些。

## BeszelBar 的关键核对结果

- 检查时最新发布版本为 [v1.0.1](https://github.com/Loriage/BeszelBar/releases/tag/v1.0.1)。README 与默认分支源码的能力不应直接当作这个发布包的已验收行为。
- [RefreshService.swift](https://github.com/Loriage/BeszelBar/blob/main/Sources/Services/RefreshService.swift)将轮询限制在 10–300 秒；不是本项目要求的默认 5 秒实时挂件。
- [Models.swift](https://github.com/Loriage/BeszelBar/blob/main/Sources/Models/Models.swift)的详细统计模型没有完整的逐 GPU 温度、显存、功耗结构。不能因为 Beszel 服务端支持 GPU，就认定这个客户端已经能完整显示。
- 当前递归文件列表中没有独立的 LICENSE 文件，虽然 README 声明 MIT。直接复制或分发其代码之前，应确认明确的许可文本。本方案默认独立实现 Mac 客户端，先参考功能和接口。
- Beszel 的[官方第三方桌面应用页](https://beszel.dev/guide/third-party-integrations/desktop-apps)也列出了 BeszelBar，并明确需要能够访问的 Beszel 实例。

## 采用原则

1. 服务端先验证 Beszel：指标覆盖、资源占用、5 秒实时适配、断线行为、版本兼容。
2. Mac 端围绕同一份服务器数据构建菜单栏、独立桌面挂件和完整窗口；可借鉴有明确许可的轻量界面设计。
3. 如 Hub 的额外负担或适配成本不合适，切换到精简 Go 采集端直连。保留相同的 Mac 数据模型，避免重做界面。
4. 项目的“轻量”和作者公布的 CPU 数字只作为调研线索，不作为本项目性能承诺。
