# RPG Maker MV / MZ 适配器

该适配器通过 Chrome DevTools Protocol 与游戏页面通信，并配合 Kaze 插件桥提供修改器操作。

## 当前能力

- 引擎信息、开关、变量、货币和队伍状态；
- 道具查询与添加；
- 地图、事件、坐标、传送、穿墙和遇敌设置；
- 运行时倍速操作；
- 存档备份与还原。

离线文本翻译由 Kotlin 文件路线完成，不依赖本适配器持续运行。运行时插件桥可能写入游戏目录，Kaze 在游戏退出时执行清理；离线写回会创建相应备份。

## 手动启动

游戏需要开放本机 DevTools 端口，例如：

```text
Game.exe --remote-debugging-port=9222
```

先构建并启动 `kaze-relay.exe`，再运行：

```powershell
python -u adapters\mvmz\mvmz_adapter.py --port 9222
```

适配器从 `%LOCALAPPDATA%\Kaze\session\relay.json` 读取管道名和会话令牌。通常直接由桌面端启动游戏和适配器即可。

## 离线检查

```powershell
python tools\test_trainer_adapter.py
node --check adapters\mvmz\kaze_plugin.js
```

测试使用虚构页面状态，不需要真实游戏。完整构建说明见 `docs/BUILD.md`。
