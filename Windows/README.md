# Windows 采集端和监控客户端

## 本次 Windows x64 开发包

已提供 `build/NodeMonitor-Windows-x64.zip`（监控客户端）与 `build/nodemon-agent-Windows-x64.zip`（Windows 电脑采集端）。客户端包含所需 .NET / WPF 运行库和 `nodemon-tailscale-bridge.exe`，解压到可写文件夹后运行 `NodeMonitor.exe`。用于查看已有 Ubuntu 服务器时使用客户端包即可。两个包均不包含主机配置、令牌或测试数据。

这是仓库原有 Windows 界面的构建，并未同步 Android 的杏绿色重设计、告警中心、持久历史与六种挂件样式。已在当前 Windows 环境完成启动、回环演示 HTTP 读取、动态指标与桌面挂件冒烟；既有地址校验与 GPU 过期测试通过，Windows 采集端 `--once --no-gpu` 也已实际读取 CPU / 内存 / 磁盘 / 网络。Tailscale 登录及更多硬件组合仍需实机验证。

Windows 原生构建可使用 `scripts/build-windows.ps1`，默认寻找项目内 `.tools/dotnet/dotnet.exe` 与 `.tools/go/bin/go.exe`，也可通过 `-Dotnet`、`-Go` 指定路径。构建缓存均重定向到项目内。

这是开发版。采集端运行在被监控的 Windows 电脑；客户端运行在查看数据的 Windows 电脑，也可以连接已有 Ubuntu 采集端。两端均支持 x64 Windows 10/11；尚需在实际 Windows 设备验证。项目使用同一份 v1 快照协议。

## 运行 Windows 采集端

解压发行包后，将采集端整个目录复制到 Windows 上由当前用户控制的 **NTFS 文件夹**，在该目录打开 PowerShell。源码构建时对应的目录为 `build/windows-agent`。先进行本机验证：

```powershell
.\nodemon-agent.exe --once
.\nodemon-agent.exe
```

首次启动会在 `data\agent.token` 自动生成令牌，Windows ACL 将文件限制为当前用户和 SYSTEM。令牌供监控客户端填写，请通过可信方式自行读取，不要发到聊天、日志或命令参数里。保持这个窗口运行，或在确认需求后自行配置 Windows 任务计划程序；本版本不会自动修改服务、开机启动或防火墙。

只用于可信局域网时，可以明确允许 HTTP 并监听局域网接口：

```powershell
.\nodemon-agent.exe --listen 0.0.0.0:9274 --allow-http-lan
```

HTTP 本身不加密，不应直接暴露公网。远程建议使用内置的 Tailscale：先在交互式终端执行下面的授权步骤，按终端提示在浏览器完成授权（临时授权链接不要分享）；之后以第二条命令启动。仅通过内置 Tailscale 时，普通监听仍保持本机回环，不需开放公网端口。

```powershell
.\nodemon-agent.exe --tailscale-login
.\nodemon-agent.exe --tailscale-listen :9274
```

客户端使用服务器的 Tailscale 地址和采集端令牌。客户端也需要在界面中点击“登录远程网络”并加入同一个网络；Windows 端不用额外安装 Tailscale 客户端。安全规则仍由你的 Tailscale 账户管理。

## Windows 监控客户端

解压发行包后，将客户端整个目录复制到可写 NTFS 文件夹，打开 `NodeMonitor.exe`；源码构建时对应的目录为 `build/windows-client`。添加主机时选择当前使用的连接方式，填写相应地址和采集端令牌；也可以同时保存局域网地址和 Tailscale 地址，此时主机详情会提供切换按钮。设置保存在程序旁的 `data` 目录，令牌使用 Windows 当前账号的 DPAPI 加密。关闭主窗口后程序留在托盘；从托盘菜单可打开窗口、显示桌面挂件或退出。挂件显示所有可用 CPU / GPU 以及内存。窗口或挂件显示时约每 5 秒刷新；两者都隐藏时约每 30 秒刷新。

## 当前硬件范围

- Windows：使用系统 API 读取 CPU 总使用率、物理 CPU 数量、内存、固定磁盘和网络。系统 API 不提供可靠的 CPU 温度，因此显示 `—`；多物理 CPU 的各自使用率暂显示 `—`，另有总使用率。Windows 超过一个处理器组时 CPU 总使用率也显示不可用。
- NVIDIA GPU：需要安装并正常运行 NVIDIA 驱动及 `nvidia-smi`。每张卡分别显示使用率、温度、显存等可用读数。AMD / Intel GPU 监控暂未接入。
- CPU / GPU / 温度不可用时显示 `—`，不会作为真实的 0 显示。首次 CPU / 网络采样需等待第二次更新。

当前没有自动安装器、Windows 服务或自动配置防火墙。`scripts/build-windows.sh` 在 macOS 使用项目内 Go 和 .NET SDK 交叉构建；依赖包缓存也放在项目内。发布给别人前仍需在目标 Windows 机器完成安全和硬件验证。
