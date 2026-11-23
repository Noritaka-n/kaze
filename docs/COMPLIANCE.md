# 可公开发布性（GitHub）

Kaze 的目标是仓库本身可以直接公开，不依赖任何“不能上传”的文件。

## 仓库里不放什么

| 类别 | 处理方式 |
| --- | --- |
| 本地模型/推理运行时 | 产品已移除该模式，不下载、不分发 |
| API Key / Token | 不入库；运行时用 Windows DPAPI 加密后存 `%APPDATA%\Kaze\settings.json` |
| 游戏资源、商业素材、第三方 UI 素材 | 不入库；测试夹具由 `tools/` 自行生成 |
| 日志、会话、临时抓包 | 不入库；`.gitignore` 已覆盖 |

## 第三方名称与协议

- 代码只保留在线 OpenAI 兼容 API；不包含免费通道、Ollama、LM Studio、llama.cpp 或本地推理运行时。
- 构建依赖（Kotlin、Gson、protobuf、SQLite JDBC、JNA 等）通过包管理器获取，遵循各自许可证；仓库不 vendored 它们的源码。
- relay 使用 protobuf runtime 的公开 C++ 库；`proto/kaze.proto` 是本项目自己的协议定义。

## 发布前检查

```powershell
python tools/check_public_repo.py
```

脚本检查 Git 已跟踪文件及未被忽略的可发布文件，包括无扩展名文件、`.gitignore`、`.env` 和常见源码/配置格式：

1. 模型/运行时/原生库后缀及运行时配置；
2. 大于 5 MB 的文件；
3. 常见平台令牌、`Bearer ...`、私钥和硬编码 key；
4. 个人用户目录、游戏目录及其他机器特定绝对路径；
5. 已被跟踪的 `build/`、`gen/` 等生成目录内容。

命中时只报告类别、文件和行号，不回显密钥或个人路径。存在 findings 时以非零状态退出，处理后再提交。

## 模型许可证

用户选择的模型有自己的许可证（例如 Qwen、Llama、Gemma 的使用条款）。
Kaze 不下载、不再分发模型，也不对模型的输出负责；需要商用或再分发时，用户自行遵守模型许可证。
