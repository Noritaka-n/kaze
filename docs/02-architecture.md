# 当前架构

本文描述当前代码结构，不记录历史试验或开发过程。

## 产品范围

Kaze 是 Windows 桌面应用，目前面向 RPG Maker MZ、MV、XP、VX 和 VX Ace。主要模块为游戏库、离线文本翻译、运行时修改器和设置。

Ren'Py 适配器目录保留为历史实验代码，不属于当前产品支持范围，也不会被游戏库识别为受支持引擎。

## 模块

```text
Compose Desktop / Kotlin
  ├─ 游戏库与本地封面
  ├─ 离线文本扫描、翻译记忆与写回
  ├─ 修改器界面
  └─ 设置与 DPAPI 密钥存储
          │
          ├─ 文件路线：直接处理游戏文本数据
          │
          └─ kaze-relay（C++20，命名管道 + protobuf）
                    │
                    ├─ MV/MZ Python 适配器 + CDP/插件桥
                    └─ RGSS Python 内存适配器
```

### Kotlin 桌面端

`src/shell/` 使用 Kotlin、Compose Multiplatform 和 Material 3。protobuf 消息由 Gradle 根据 `proto/kaze.proto` 生成。主要目录：

- `data/`：游戏识别、游戏库、运行时与修改器数据；
- `offline/`：MV/MZ JSON、RGSS Ruby Marshal 与翻译写回；
- `translate/`：OpenAI 兼容 API、SQLite 翻译记忆和设置；
- `ipc/`：命名管道客户端及 relay 会话；
- `ui/`：桌面界面。

### 离线文本路线

MV/MZ 读取游戏数据 JSON；XP/VX/VX Ace 读取 RGSS 序列化数据。带 UTF-8 编码标记的 RGSS3 数据支持写回，未带编码标记的旧数据保持只读。写回与还原路径受只读模式保护。

翻译请求仅发送到用户配置的 OpenAI 兼容 API。API Key 使用 Windows DPAPI 加密后保存在用户配置目录，不进入源码仓库。

### relay 与适配器

`src/relay-cpp/` 是独立的 C++20 进程，通过 Win32 命名管道、KZ1 帧和 protobuf 路由消息。会话文件位于 `%LOCALAPPDATA%\Kaze\session\relay.json`，连接使用随机会话令牌。

MV/MZ 适配器通过 Chrome DevTools Protocol 和临时插件桥访问游戏状态。运行时可能在游戏目录安装桥接文件，并在退出时清理；离线翻译则直接处理数据文件。RGSS 修改器通过内存适配器完成进程附加、扫描和写入。

## 测试与生成物

- Kotlin 测试：`src/shell/src/test/kotlin/`；
- 虚构 RGSS 夹具：`src/shell/src/test/resources/`；
- 适配器离线测试：`tools/test_trainer_adapter.py`；
- relay 协议自检：`src/relay-cpp/build.ps1 -SelfTest`。

`build/`、`gen/`、`__pycache__/`、日志、发布目录和本地设置均可再生成或属于本机状态，由 `.gitignore` 排除。构建与打包步骤见 `docs/BUILD.md`。
