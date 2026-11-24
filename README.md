# Kaze

**Kaze** 是一款 Windows 桌面应用，为 RPG Maker 游戏提供**汉化翻译**与**游戏修改**功能：扫描游戏文本，调用你自己的翻译 API 批量翻译并写回游戏文件，内置修改器可启动游戏后实时调整数值。

> 目前仅支持 RPG Maker 系列（MZ / MV / XP / VX / VX Ace）。其他引擎（Unity、Ren'Py、Godot、KiriKiri、Wolf 等）暂不支持。

## 主要功能

**📚 游戏库** — 添加游戏文件夹，自动识别引擎与版本，提取本地封面，一键启动游戏。

**🌐 翻译** — 离线扫描全部游戏文本，通过任意 OpenAI 兼容 API 批量翻译后写回游戏；内置翻译记忆（模糊复用，支持 CSV 导入导出，换机不重译）与术语表。

**🛠 修改器** —
- MV / MZ：开关、变量、货币、道具、队伍状态、穿墙、传送、遇敌率、地图事件定位
- XP / VX / VX Ace：附加进程实时内存修改（扫描 / 筛选 / 写入）
- 存档备份与还原

**⚙️ 设置** — 深色主题、翻译 API 配置、只读模式。

## 支持范围

| 引擎 | 文本翻译 | 修改器 |
| --- | --- | --- |
| RPG Maker MZ / MV | ✅ 全量扫描翻译，写回游戏文件 | ✅ 语义级修改面板 |
| RPG Maker XP / VX / VX Ace | ✅ 离线扫描（UTF-8 可写回，Shift_JIS 暂只读） | ✅ 进程内存修改 |

## 快速开始

1. 从 [Releases](https://github.com/Noritaka-n/kaze/releases) 下载 `Kaze-x.x.x-win-x64.zip`，解压后运行 `Kaze.exe`（标准包自带 Java、relay 与适配器运行环境）。若包内包含 `RUNTIME-REQUIREMENTS.txt`，该构建不内置适配器运行时，需按该文件安装 64 位 Python 3.11+ 并配置 `PATH` 或 `KAZE_PYTHON`。
2. 在「游戏库」添加游戏所在文件夹。
3. 在「设置」填入翻译 API（Base URL / 模型 / API Key，任意 OpenAI 兼容接口均可）。
4. 在「翻译」页扫描 → 翻译 → 写回；或在「修改器」页启动游戏后实时修改。

## 数据与隐私

- API Key 使用 Windows DPAPI 加密，仅保存在本机。
- 翻译请求只发送到你自行配置的 API 地址，应用不上报任何数据。

## 许可证

[MIT](LICENSE)
