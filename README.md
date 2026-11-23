# Kaze

Runtime text workbench for Japanese/indie games.

Current implementation supports **RPG Maker only**:

- RPG Maker MZ
- RPG Maker MV
- RPG Maker XP / VX / VX Ace (RGSS)

RGSS games use the memory adapter: the Modifier page provides a real
process attach / value scan / filter / write panel. MV/MZ additionally expose
the semantic switch, variable, map, item and cheat panels.

Other engines (Unity, Ren'Py, Godot, KiriKiri, Wolf RPG) are intentionally
out of scope and are not detected or listed by the library.

## Architecture

```text
Compose Desktop (Kotlin)     Library / Translate / Modifier / Settings
        |
        +-- library.json / settings.json
        +-- Kotlin core (scan, translate, write-back, memory)
        +-- kaze-relay (C++ named pipe) --- Python adapter --- game
```

- Desktop UI: Kotlin + Compose Multiplatform + Material 3
- Game runtime: C++20 relay + Python adapters
- Protocol: protobuf over a named pipe
- Translation: user-supplied OpenAI-compatible API (base URL + model + API key)

## Run

```powershell
$env:JAVA_HOME = 'C:\Program Files\Zulu\zulu-21'
tools\gradle-8.11.1\bin\gradle.bat -p src\shell run
```

Tests:

```powershell
tools\gradle-8.11.1\bin\gradle.bat -p src\shell test
```

Public-repo check:

```powershell
python tools\check_public_repo.py
```

## Current UI

- Library: game cards, search, add folder, launch
- Translate: scan, translate, write back, export
- Modifier: runtime switches, variables, maps, events, items, cheats
- Settings: dark appearance, LLM API configuration, read-only mode

Adding a folder scans recursively and accepts only the RPG Maker engines above.
Games with missing directories or unsupported engines are hidden from the
library.

## Layout

```text
kaze/
  adapters/     Python adapters (mvmz, memory)
  proto/        shared protobuf protocol
  src/
    shell/      Kotlin + Compose desktop/core
    relay-cpp/  C++ relay
  docs/         design and scope notes
```
