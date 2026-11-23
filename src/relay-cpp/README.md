# kaze-relay（C++20）

Kaze 的进程外路由服务，使用 Win32 命名管道、KZ1 帧和 protobuf 消息，在桌面端与游戏适配器之间转发请求和文本批次。relay 不包含 UI。

## 构建与自检

完整环境说明见 `docs/BUILD.md`。安装 MSYS2 MinGW-w64、G++、protobuf 和 pkg-config 后：

```powershell
$env:KAZE_MSYS = "C:\msys64"
powershell -ExecutionPolicy Bypass -File src\relay-cpp\build.ps1 -Clean -SelfTest
```

脚本通过 `protoc` 生成 `gen/kaze.pb.{h,cc}`，编译 `build/kaze-relay.exe`，并复制所需 protobuf、Abseil 和 MinGW 运行时 DLL。`gen/`、`build/` 和二进制文件均不入库。

## 运行

```powershell
src\relay-cpp\build\kaze-relay.exe
```

启动后将会话信息写入 `%LOCALAPPDATA%\Kaze\session\relay.json`，客户端使用其中的管道名和随机会话令牌连接。`--selftest` 使用内置虚构适配器验证握手、附加、文本批次、回填和确认链路。

## 源码

- `kz_relay.cpp`：进程、连接、会话和适配器路由；
- `kz_protocol.cpp` / `kz_protocol.h`：KZ1 帧读写；
- `../../proto/kaze.proto`：跨 Kotlin、C++ 和 Python 适配器使用的协议定义。
