# 知识来源登记（清洁室）
> **当前实现范围：仅 RPG Maker 系列（MZ / MV / XP / VX / VX Ace）。Ren'Py、Unity、Godot、KiriKiri、Wolf 等仅保留历史调研记录，不在产品支持范围。**


> 规则：实现中出现的每一条"引擎行为"，都必须能在这里找到出处；找不到就先补登记，再写代码。
> 登记格式：`主题 | 来源 | 结论 | 日期`

## 引擎 / 运行时

| 主题 | 来源 | 结论 | 日期 |
| --- | --- | --- | --- |
| Ren'Py 文本过滤 | renpy.org 官方文档 `config.say_menu_text_filter`、`renpy.exports`、`translate` 章节 | 可在 Python 层拦截显示文本，支持语言包与覆盖脚本 | 2026-10-29 |
| Ren'Py 目录结构 | 官方快速开始 | `game/` 下 `.rpy`/`.rpyc`，`script.rpy` 为入口 | 2026-10-29 |
| KiriKiri 插件 ABI | KRKR 官方插件文档（`V2Link`/`V2Unlink`） | DLL 导出 `V2Link` 即可被引擎加载 | 2026-10-29 |
| KiriKiri 脚本 | TJS2 官方语法文档 | 可注入 `patch.tjs` / 插件内 `TJS_Eval` | 2026-10-29 |
| RPG Maker MV/MZ 结构 | 官方帮助 + 公开的 `rpg_core.js` | NW.js 应用，`www/js/` 下为 JS | 2026-10-29 |
| Chromium DevTools Protocol | Chrome DevTools Protocol 公开规范 | `Runtime.evaluate` 可在页面上下文执行 JS | 2026-10-29 |
| NW.js 命令行 | NW.js 官方文档 | 支持透传 Chromium 开关（含调试端口） | 2026-10-29 |
| Ruby Marshal | Ruby 官方 `Marshal` 格式文档 | 可解析 `.rxdata/.rvdata/.rvdata2`；仅带明确 UTF-8 标记的数据支持写回，旧编码数据只读 | 2026-10-29 |
| Ruby Marshal 整数编码 | 同上（`marshal_format`：小值 ±5 偏置、长度字节符号位） | 201 编码为 `01 C9`，-50 编码为 `C9` | 2026-11-05 |
| RGSS 数据对象 | RPG Maker VX Ace 官方帮助（RGSS リファレンス：`RPG::Map` / `RPG::Event` / `RPG::EventCommand` / `RPG::System`）+ 自制夹具自测 | 地图事件指令 `@code/@parameters`，数据库 `@name/@description`，系统 `@terms` | 2026-11-05 |
| Unity 模组加载 | BepInEx / MelonLoader 官方文档 | 提供插件生命周期与 Harmony 补丁 | 2026-10-29 |
| Godot 扩展 | Godot 官方 GDExtension / GDNative 文档 | 可注册自动加载与脚本 | 2026-10-29 |
| SpiderMonkey API | Mozilla 公开 API 文档 | `JS_EvaluateScript` 可执行脚本 | 2026-10-29 |
| Windows 图形捕获 | Microsoft Learn `Windows.Graphics.Capture` | 可窗口级捕获，供 OCR 兜底 | 2026-10-29 |

## 设计 / 规范

| 主题 | 来源 | 结论 | 日期 |
| --- | --- | --- | --- |
| 可访问性 | WCAG 2.2 + Android 无障碍指南 | 对比度、触控目标、减少动效 | 2026-10-29 |

## 技术选型

| 主题 | 来源 | 结论 | 日期 |
| --- | --- | --- | --- |
| SQLite FTS5 | sqlite.org 官方文档 | 支持全文检索虚拟表 | 2026-10-29 |
| protobuf | protobuf.dev | 跨语言序列化与代码生成 | 2026-10-29 |
| minisign | jedisct1/minisign 文档 | Ed25519 签名清单格式 | 2026-10-29 |
| 命名管道 ACL | Microsoft Learn `CreateNamedPipe` | 可设置仅当前用户的安全描述符 | 2026-10-29 |

## 禁止来源

- 任何同类产品的可执行文件、字节码、资源包、日志、UI 截图。
- 任何反编译/反混淆输出。
- 任何论坛"拆包教程"中的代码片段。
