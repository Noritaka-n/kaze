# Ren'Py 实验适配器（历史保留）

> 当前 Kaze 产品仅支持 RPG Maker 系列。此目录保留早期协议实验代码，游戏库不会把 Ren'Py 游戏列为受支持引擎，也不提供相应的发布质量承诺。

适配器通过 Ren'Py 的文本过滤接口读取显示文本，并使用 `kaze_client.py`、`kz_wire.py` 与 relay 交换消息。 wire 消息由项目自身代码编码，不需要额外生成 `kaze_pb2.py`。

## 离线演示

先构建并启动 relay：

```powershell
$env:KAZE_MSYS = "C:\msys64"
powershell -ExecutionPolicy Bypass -File src\relay-cpp\build.ps1 -Clean
src\relay-cpp\build\kaze-relay.exe
```

在另一个终端运行虚构数据演示：

```powershell
python -u adapters\renpy\renpy_adapter.py --demo
```

演示只产生虚构台词，用于检查握手和文本帧，不表示桌面端已支持 Ren'Py 游戏。相关离线值解析测试由 `tools/test_trainer_adapter.py` 覆盖。
