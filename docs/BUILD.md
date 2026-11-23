# 源码构建与测试

本文面向源码贡献者；应用使用说明见根目录 `README.md`。

## 环境

- Windows 10/11 x64
- JDK 21，并设置 `JAVA_HOME`
- Python 3（运行适配器及离线适配器测试）
- Node.js（可选，用于适配器 JavaScript 执行测试）
- MSYS2 MinGW-w64：G++、protobuf、pkg-config（仅构建 relay 时需要）

Kotlin 构建使用仓库内的 Gradle Wrapper，无需单独安装 Gradle。

## Kotlin 桌面端

PowerShell：

```powershell
$env:JAVA_HOME = "C:\Program Files\Zulu\zulu-21"
cd src\shell
.\gradlew.bat test
.\gradlew.bat run
```

创建免安装目录版：

```powershell
New-Item -ItemType Directory -Force build\wix311
.\gradlew.bat createDistributable -x downloadWix -x unzipWix
```

输出位于 `src/shell/build/compose/binaries/main/app/Kaze/`。排除的两个任务只用于 MSI 安装器；便携目录和 ZIP 不使用 WiX。Compose 会校验 `build/wix311` 输入，因此手动构建时需要创建这个空目录；打包脚本会自动处理，且不会把它放进发布包。

## C++ relay

安装 MSYS2 后，在 MinGW64 环境安装编译工具和 protobuf，然后设置 `KAZE_MSYS` 为 MSYS2 根目录：

```powershell
$env:KAZE_MSYS = "C:\msys64"
powershell -ExecutionPolicy Bypass -File src\relay-cpp\build.ps1 -Clean -SelfTest
```

构建脚本生成 `src/relay-cpp/build/kaze-relay.exe` 及所需 DLL。`gen/` 和 `build/` 都是可再生成目录，不入库。

## 适配器测试

```powershell
python -m compileall -q adapters
python tools\test_trainer_adapter.py
node --check adapters\mvmz\kaze_plugin.js
```

`test_trainer_adapter.py` 是离线测试，不启动游戏、relay 或桌面 UI。未安装 Node.js 时，脚本中的 JavaScript 执行项会失败；其余静态检查仍可运行。

## RGSS 测试夹具

测试夹具已随 `src/shell/src/test/resources/` 提交，正常运行测试不需要 Python 依赖。仅在需要重新生成夹具时执行：

```powershell
python -m pip install -r tools\requirements-dev.txt
python tools\make_rgss_fixtures.py
```

这些文件由脚本生成，仅含虚构角色、道具和对话，不来自商业游戏。

## 发布前检查与打包

```powershell
python tools\check_public_repo.py
$env:JAVA_HOME = "C:\Program Files\Zulu\zulu-21"
$env:KAZE_MSYS = "C:\msys64"
powershell -ExecutionPolicy Bypass -File tools\package_release.ps1
```

打包脚本会依次构建并自检 relay、运行 Kotlin 测试、生成内嵌 relay 资源、创建桌面发行目录，再生成：

```text
release/Kaze-<版本>-win-x64/
release/Kaze-<版本>-win-x64.zip
release/Kaze-<版本>-win-x64.zip.sha256
```

版本号取自 `src/shell/build.gradle.kts` 的 `packageVersion`。发布新版本时，同时更新该文件的 `version`、`packageVersion` 和 relay 的 `kVersion`。

`release/`、生成的 `kaze-relay-runtime.zip`、构建产物、日志和本地设置均由 `.gitignore` 排除。
