"""Kaze adapter for RPG Maker MV / MZ.

Talks to the running game through the Chrome DevTools Protocol, so the game
must be started with:

    Game.exe --remote-debugging-port=9222

Nothing is injected into the game: we only evaluate JavaScript in its own
context through the debugger.
"""

from __future__ import annotations

import argparse
import hashlib
import zlib
import lzstring
import json
import os
import shutil
import socket
import sys
import threading
import time
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "renpy"))

import kz_wire              
from kaze_client import KazeClient              
from cdp import CDP              

ROLE_ADAPTER = 2
ADAPTER_NAME = "mvmz"


def session_path() -> str:
    base = os.environ.get("LOCALAPPDATA") or os.path.expanduser("~")
    return os.path.join(base, "Kaze", "session", "relay.json")


def key_of(text: str) -> str:
    return hashlib.blake2b(text.encode("utf-8"), digest_size=8).hexdigest()


def read_session() -> tuple[str, str]:
    with open(session_path(), "r", encoding="utf-8") as handle:
        data = json.load(handle)
    return data["pipe"], data["token"]


CHEATS = [
    {"id": "money", "name": "金币 +99999", "desc": "$gameParty.gainGold(99999)"},
    {"id": "heal", "name": "全体满血满蓝", "desc": "所有队员 HP/MP 回满"},
    {"id": "items99", "name": "道具各 +99", "desc": "遍历 $dataItems，每个获得 99 个"},
    {"id": "through_on", "name": "穿墙：开", "desc": "$gamePlayer._through = true"},
    {"id": "through_off", "name": "穿墙：关", "desc": "$gamePlayer._through = false"},
    {"id": "no_encounter", "name": "关闭遇敌", "desc": "$gameSystem.disableEncounter()"},
    {"id": "encounter", "name": "恢复遇敌", "desc": "$gameSystem.enableEncounter()"},
    {"id": "speed_2", "name": "倍速 ×2", "desc": "SceneManager.updateMain 双步进"},
    {"id": "speed_3", "name": "倍速 ×3", "desc": "SceneManager.updateMain 三步进"},
    {"id": "speed_1", "name": "倍速 ×1", "desc": "恢复原始速度"},
]



class PluginBridge:
    """Transport that talks to kaze_plugin.js inside the game (no CDP needed)."""

    def __init__(self, port: int = 9223) -> None:
        self.port = port
        self.conn = None
        self._lock = threading.Lock()
        self._seq = 0
        self._pending: dict[int, dict] = {}
        self.engine = ""
        self._server = None

    @property
    def connected(self) -> bool:
        return self.conn is not None

    def start(self) -> bool:
        """Binds the bridge port; returns False when another adapter owns it."""
        try:
            server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                                                                                  
                                                                          
                                             
            if hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
                server.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
            else:
                server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            server.bind(("127.0.0.1", self.port))
            server.listen(2)
            self._server = server
            print(f"[mvmz] plugin bridge listening on {self.port}", flush=True)
        except Exception as exc:
            print(f"[mvmz] plugin bridge failed: {exc}", flush=True)
            return False

        threading.Thread(target=self._accept_loop, args=(server,), daemon=True).start()
        return True

    @staticmethod
    def _stamp() -> str:
        return time.strftime("%H:%M:%S")

    def _accept_loop(self, server) -> None:
        while True:
            try:
                conn, addr = server.accept()
            except Exception:
                return
            threading.Thread(target=self._adopt, args=(conn, addr), daemon=True).start()

    def _adopt(self, conn, addr) -> None:
        """Only a connection that greets with `hello` may become the plugin.

        Kaze's supervisor probes the bridge port every 5 s with a bare TCP
        connect; treating that probe as the plugin replaced the real socket and
        then cleared it on EOF, so every tool call raced a 5-second disconnect.
        """
        try:
            conn.settimeout(0.3)
            head = b""
            deadline = time.monotonic() + 1.5
                                                                            
            while b"\n" not in head and time.monotonic() < deadline:
                try:
                    chunk = conn.recv(4096)
                except socket.timeout:
                    continue
                except Exception:
                    chunk = b""
                if not chunk:
                    break
                head += chunk
        except Exception:
            head = b""
        line, _, rest = head.partition(b"\n")
        hello = None
        try:
            message = json.loads(line.strip()) if line.strip() else None
            if isinstance(message, dict) and message.get("hello") == "kaze":
                hello = message
        except Exception:
            hello = None
        if hello is None:
            try:
                conn.close()
            except Exception:
                pass
            return

        try:
            conn.settimeout(None)
        except Exception:
            pass
        previous = self.conn
        self.conn = conn
        self.engine = hello.get("engine", "")
        if previous is not None and previous is not conn:
                                                                            
                                                                    
                                                
            try:
                previous.shutdown(socket.SHUT_RDWR)
            except Exception:
                pass
        print(
            f"[mvmz] {self._stamp()} accept {addr[1]} engine={self.engine} "
            f"(replaced={bool(previous)})",
            flush=True,
        )
        threading.Thread(target=self._read, args=(conn, addr, rest), daemon=True).start()

    def _read(self, conn, addr=None, initial: bytes = b"") -> None:
        peer = addr[1] if addr else 0
        buffer = initial
        reason = "eof"
        while True:
            try:
                chunk = conn.recv(65536)
            except Exception as exc:
                reason = f"error {exc}"
                break
            if not chunk:
                break
            buffer += chunk
            while b"\n" in buffer:
                line, buffer = buffer.split(b"\n", 1)
                if not line.strip():
                    continue
                try:
                    message = json.loads(line)
                except Exception:
                    continue
                if message.get("hello") == "kaze":
                    self.engine = message.get("engine", "")
                    continue
                slot = self._pending.pop(message.get("id"), None)
                if slot is not None:
                    slot["value"] = message.get("value")
                    slot["event"].set()
        if self.conn is conn:
            self.conn = None
        try:
            conn.close()
        except Exception:
            pass
        print(f"[mvmz] {self._stamp()} close {peer} ({reason})", flush=True)

    def evaluate(self, expression: str, timeout: float = 8.0, wait_for_connection: float = 0.0):
                                                                               
                                                     
        if self.conn is None and wait_for_connection > 0:
            deadline = time.monotonic() + wait_for_connection
            while self.conn is None and time.monotonic() < deadline:
                time.sleep(0.05)
        if self.conn is None:
            raise ConnectionError("plugin not connected")
        with self._lock:
            self._seq += 1
            request_id = self._seq
            slot = {"event": threading.Event(), "value": None}
            self._pending[request_id] = slot
            self.conn.sendall(
                (json.dumps({"id": request_id, "expr": expression}) + "\n").encode("utf-8")
            )
        if not slot["event"].wait(timeout):
            self._pending.pop(request_id, None)
            raise TimeoutError("plugin did not answer")
        return slot["value"]
class MvmzAdapter:
    def __init__(self, client: KazeClient, port: int) -> None:
        self.client = client
        self.cdp = CDP(port)
        self.attached = False
        self.last_error = ""
        self.text_enabled = False
        self.pending_text = {}
        self._last_poll = 0.0
        self._cdp_retry_after = 0.0
        self.bridge = PluginBridge()

                                                                         

    def connect(self) -> None:
        if self.bridge.connected:
            return
        if self.cdp.ws is not None:
            return
        now = time.monotonic()
        if now < self._cdp_retry_after:
            raise ConnectionError(
                "未连接游戏：请先在游戏库点启动（插件会自动连上 9223）"
            )
        try:
            self.cdp.connect()
            self._cdp_retry_after = 0.0
        except Exception as exc:
            self._cdp_retry_after = now + 5.0
            self.last_error = str(exc)
            print(f"[mvmz] CDP 连接失败: {exc}", flush=True)
            raise

    def js(self, expression: str):
        if self.bridge.connected:
            return self.bridge.evaluate(expression, wait_for_connection=2.5)
        self.connect()
        return self.cdp.evaluate(expression)

    def js_json(self, expression: str):
        raw = self.js(expression)
        return json.loads(raw) if raw else None

                                                                         

    def handle_invoke(self, request: dict) -> dict:
        op = request.get("op", "")
        try:
            args = json.loads(request.get("args_json") or "{}")
        except Exception:
            args = {}

        try:
            if op == "debug.eval":
                expr = str((args or {}).get("expr", ""))
                try:
                    value = self.js(expr)
                    return {"ok": True, "detail": str(value)[:200],
                            "data_json": json.dumps({"value": value}, ensure_ascii=False, default=str)}
                except Exception as exc:
                    return {"ok": False, "detail": str(exc)}
            if op == "engine.info":
                return self._engine_info()
            if op == "game.state":
                return self._game_state()
            if op == "vars.read":
                return self._vars_read(args)
            if op == "vars.search":
                return self._vars_search(args)
            if op == "vars.page":
                return self._vars_page(args)
            if op == "vars.list":
                return self._vars_list()
            if op == "vars.set":
                return self._vars_set(args)
            if op == "saves.inspect":
                return self._saves_inspect(args)
            if op == "saves.patch":
                return self._saves_patch(args)
            if op == "state.snapshots":
                return self._snapshots_list()
            if op == "state.snapshot":
                return self._state_snapshot()
            if op == "state.restore":
                return self._state_restore(args)
            if op == "assets.iconSet":
                return self._icon_set()
            if op == "party.gold":
                return self._party_gold()
            if op == "party.setGold":
                return self._party_set_gold(args)
            if op == "party.items":
                return self._party_items()
            if op == "party.setItem":
                return self._party_set_item(args)
            if op == "actors.set":
                return self._actors_set(args)
            if op == "actors.buffs":
                return self._actors_buffs(args)
            if op == "actors.setState":
                return self._actors_set_state(args)
            if op == "actors.setBuff":
                return self._actors_set_buff(args)
            if op == "actors.stateList":
                return self._actors_state_list()
            if op == "actors.detail":
                return self._actor_detail(args)
            if op == "party.itemInfo":
                return self._party_item_info(args)
            if op == "party.addItem":
                return self._party_add_item(args)
            if op == "actors.list":
                return self._actors_list()
            if op == "maps.list":
                return self._maps_list()
            if op == "map.preview":
                return self._map_preview(args)
            if op == "map.grid":
                return self._map_grid(args)
            if op == "map.info":
                return self._map_info()
            if op == "player.pos":
                return self._player_pos()
            if op == "events.list":
                return self._events_list()
            if op == "events.all":
                return self._events_all()
            if op == "player.teleport":
                return self._player_teleport(args)
            if op == "speed.set":
                return self._speed_set(args)
            if op == "text.disable":
                return self._text_disable()
            if op == "text.enable":
                return self._text_enable()
            if op == "cheats.list":
                return {"ok": True, "detail": f"{len(CHEATS)} cheats",
                        "data_json": json.dumps(CHEATS, ensure_ascii=False)}
            if op == "cheats.run":
                return self._cheats_run(args)
            if op == "saves.dir":
                return self._saves_dir()
            if op == "saves.list":
                return self._saves_list()
            if op == "saves.backups":
                return self._saves_backups()
            if op == "saves.backup":
                return self._saves_backup()
            if op == "saves.restoreOne":
                return self._saves_restore_one(args)
            if op == "saves.restore":
                return self._saves_restore()
            return {"ok": False, "detail": f"unknown op '{op}'"}
        except Exception as exc:
            self.last_error = str(exc)
            return {"ok": False, "detail": f"{op} 失败: {exc}"}

    def _engine_info(self) -> dict:
        data = self.js_json(
            "(() => {"
            "  if (typeof Utils === 'undefined') return JSON.stringify({engine:'unknown'});"
            "  let title = '';"
            "  try { title = String($dataSystem.gameTitle || ''); } catch (e) {}"
            "  return JSON.stringify({engine: Utils.RPGMAKER_NAME, version: Utils.RPGMAKER_VERSION,"
            "    title: title});"
            "})()"
        ) or {}
        name = data.get("engine", "unknown")
        return {"ok": name in ("MV", "MZ"), "detail": f"{name} {data.get('version','')}",
                "data_json": json.dumps(data, ensure_ascii=False)}

    def _game_state(self) -> dict:
        """Scene / map state: at the title screen every runtime variable is 0."""
        data = self.js_json(
            "(() => {"
            "  let scene = '';"
            "  try { scene = SceneManager._scene ? SceneManager._scene.constructor.name : ''; } catch (e) {}"
            "  let mapId = 0;"
            "  try { mapId = $gameMap ? $gameMap.mapId() : 0; } catch (e) {}"
            "  return JSON.stringify({"
            "    scene: scene, mapId: mapId,"
            "    inGame: scene === 'Scene_Map' || mapId > 0,"
            "    title: (typeof $dataSystem !== 'undefined' && $dataSystem.gameTitle) || ''"
            "  });"
            "})()"
        ) or {}
        return {
            "ok": bool(data),
            "detail": data.get("scene", ""),
            "data_json": json.dumps(data, ensure_ascii=False),
        }

    def _vars_list(self) -> dict:
        raw = self.js(
            "(() => {"
            "  const sys = (typeof $dataSystem !== 'undefined') ? $dataSystem : {};"
            "  const out = [];"
            "  if (typeof $gameParty !== 'undefined') {"
            "    out.push({name: 'party.gold', type: 'money', label: '金币',"
            "              value: String($gameParty.gold())});"
            "  }"
            "  if (typeof $gameVariables !== 'undefined' && $gameVariables._data) {"
            "    const d = $gameVariables._data;"
            "    const n = Math.min(d.length, 61);"
            "    for (let i = 1; i < n; i++) {"
            "      out.push({name: 'v[' + i + ']', type: 'var',"
            "                label: ((sys.variables && sys.variables[i]) || ''),"
            "                value: JSON.stringify(d[i] === undefined ? 0 : d[i])});"
            "    }"
            "  }"
            "  if (typeof $gameSwitches !== 'undefined' && $gameSwitches._data) {"
            "    const d = $gameSwitches._data;"
            "    const n = Math.min(d.length, 61);"
            "    for (let i = 1; i < n; i++) {"
            "      out.push({name: 's[' + i + ']', type: 'bool',"
            "                label: ((sys.switches && sys.switches[i]) || ''),"
            "                value: d[i] ? 'True' : 'False'});"
            "    }"
            "  }"
            "  return JSON.stringify(out);"
            "})()"
        )
        items = json.loads(raw) if raw else []
        return {"ok": True, "detail": f"{len(items)} 项", "data_json": json.dumps(items, ensure_ascii=False)}

    def _vars_read(self, args: dict) -> dict:
        names = [str(n) for n in (args.get("names") or [])][:40]
        expression = (
            "(() => {"
            "  const out = [];"
            "  const names = " + json.dumps(names) + ";"
            "  const sys = (typeof $dataSystem !== 'undefined') ? $dataSystem : {};"
            "  names.forEach(n => {"
            "    if (n === 'party.gold') {"
            "      out.push({name: 'party.gold', type: 'money', label: '金币',"
            "                value: String($gameParty.gold())});"
            "      return;"
            "    }"
            "    const m = n.match(/^([vs])\\[(\\d+)\\]$/);"
            "    if (!m) { out.push({name: n, type: 'unknown', label: '', value: ''}); return; }"
            "    const idx = parseInt(m[2], 10);"
            "    const label = m[1] === 'v' ? ((sys.variables && sys.variables[idx]) || '')"
            "      : ((sys.switches && sys.switches[idx]) || '');"
            "    if (m[1] === 'v') {"
            "      const v = $gameVariables.value(idx);"
            "      out.push({name: n, type: typeof v, label: label, value: JSON.stringify(v)});"
            "    } else {"
            "      out.push({name: n, type: 'bool', label: label,"
            "                value: $gameSwitches.value(idx) ? 'True' : 'False'});"
            "    }"
            "  });"
            "  return JSON.stringify(out);"
            "})()"
        )
        data = self.js_json(expression) or []
        return {"ok": True, "detail": f"{len(data)} 项", "data_json": json.dumps(data, ensure_ascii=False)}

    def _vars_search(self, args: dict) -> dict:
        query = str(args.get("q", ""))
        kind = str(args.get("kind", "var"))
        limit = max(1, min(200, int(args.get("limit", 60))))

        if kind == "switch":
            expression = (
                "(() => {"
                "  const q = " + json.dumps(query.lower()) + ";"
                "  const total = (typeof $dataSystem !== 'undefined' && $dataSystem.switches)"
                "    ? $dataSystem.switches.length - 1 : 0;"
                "  const out = [];"
                "  for (let i = 1; i <= total && out.length < " + str(limit) + "; i++) {"
                "    const on = $gameSwitches.value(i);"
                "    const label = ($dataSystem.switches[i] || '');"
                "    const text = ('s[' + i + '] ' + label + ' ' + (on ? 'true' : 'false')).toLowerCase();"
                "    if (!q || text.indexOf(q) >= 0) {"
                "      out.push({name: 's[' + i + ']', type: 'bool', label: label,"
                "                value: on ? 'True' : 'False'});"
                "    }"
                "  }"
                "  return JSON.stringify({total: total, items: out});"
                "})()"
            )
        else:
            expression = (
                "(() => {"
                "  const q = " + json.dumps(query.lower()) + ";"
                "  const total = (typeof $dataSystem !== 'undefined' && $dataSystem.variables)"
                "    ? $dataSystem.variables.length - 1 : 0;"
                "  const out = [];"
                "  for (let i = 1; i <= total && out.length < " + str(limit) + "; i++) {"
                "    const v = $gameVariables.value(i);"
                "    const label = ($dataSystem.variables[i] || '');"
                "    const text = ('v[' + i + '] ' + label + ' ' + JSON.stringify(v)).toLowerCase();"
                "    if (!q || text.indexOf(q) >= 0) {"
                "      out.push({name: 'v[' + i + ']', type: typeof v, label: label,"
                "                value: JSON.stringify(v === undefined ? 0 : v)});"
                "    }"
                "  }"
                "  return JSON.stringify({total: total, items: out});"
                "})()"
            )

        data = self.js_json(expression) or {"total": 0, "items": []}
        return {
            "ok": True,
            "detail": f"匹配 {len(data.get('items', []))} 项",
            "data_json": json.dumps(data, ensure_ascii=False),
        }

    def _vars_page(self, args: dict) -> dict:
        kind = str(args.get("kind", "var"))
        offset = max(0, int(args.get("offset", 0)))
        limit = max(1, min(300, int(args.get("limit", 100))))

        if kind == "switch":
            expression = (
                "(() => {"
                "  const total = (typeof $dataSystem !== 'undefined' && $dataSystem.switches)"
                "    ? $dataSystem.switches.length - 1 : 0;"
                f"  const start = Math.min({offset}, total);"
                "  const items = [];"
                f"  for (let i = 0; i < {limit} && start + i < total; i++) {{"
                "    const idx = start + i + 1;"
                "    const label = ((typeof $dataSystem !== 'undefined'"
                "      && $dataSystem.switches && $dataSystem.switches[idx]) || '');"
                "    items.push({name: 's[' + idx + ']', type: 'bool', label: label,"
                "                value: $gameSwitches.value(idx) ? 'True' : 'False'});"
                "  }"
                "  return JSON.stringify({total: total, offset: start, items: items});"
                "})()"
            )
        else:
            expression = (
                "(() => {"
                "  const total = (typeof $dataSystem !== 'undefined' && $dataSystem.variables)"
                "    ? $dataSystem.variables.length - 1 : 0;"
                f"  const start = Math.min({offset}, total);"
                "  const items = [];"
                f"  for (let i = 0; i < {limit} && start + i < total; i++) {{"
                "    const idx = start + i + 1;"
                "    const v = $gameVariables.value(idx);"
                "    const label = ((typeof $dataSystem !== 'undefined'"
                "      && $dataSystem.variables && $dataSystem.variables[idx]) || '');"
                "    items.push({name: 'v[' + idx + ']', type: typeof v, label: label,"
                "                value: JSON.stringify(v === undefined ? 0 : v)});"
                "  }"
                "  return JSON.stringify({total: total, offset: start, items: items});"
                "})()"
            )

        data = self.js_json(expression) or {"total": 0, "items": []}
        return {
            "ok": True,
            "detail": f"{data.get('total', 0)} 项",
            "data_json": json.dumps(data, ensure_ascii=False),
        }

    def _vars_set(self, args: dict) -> dict:
        name = str(args.get("name", "")).strip()
        value = str(args.get("value", ""))
        if not name:
            return {"ok": False, "detail": "missing name"}

        expr = (
            "(() => {"
            f"  const name = {json.dumps(name)};"
            f"  const raw = {json.dumps(value)};"
            "  let parsed; try { parsed = JSON.parse(raw); } catch (e) { parsed = raw; }"
            "  if (name === 'party.gold') {"
            "    $gameParty._gold = Number(parsed) || 0;"
            "    return JSON.stringify({ok:true, detail: 'party.gold = ' + $gameParty.gold()});"
            "  }"
            "  const m = name.match(/^([vs])\\[(\\d+)\\]$/);"
            "  if (!m) return JSON.stringify({ok:false, detail:'只支持 v[N] / s[N]'});"
            "  const idx = parseInt(m[2], 10);"
            "  if (m[1] === 'v') { $gameVariables.setValue(idx, parsed); }"
            "  else {"
            "    const on = (parsed === true || parsed === 1 || parsed === '1' ||"
            "      String(parsed).toLowerCase() === 'true');"
            "    $gameSwitches.setValue(idx, on);"
            "  }"
            "  return JSON.stringify({ok:true, detail: name + ' = ' + JSON.stringify(parsed)});"
            "})()"
        )
        result = self.js_json(expr) or {"ok": False, "detail": "no result"}
        return {"ok": bool(result.get("ok")), "detail": result.get("detail", "")}

    def _snapshot_dir(self) -> str:
        base = os.environ.get("APPDATA") or os.path.expanduser("~")
        path = os.path.join(base, "Kaze", "snapshots")
        os.makedirs(path, exist_ok=True)
        return path

    def _state_snapshot(self) -> dict:
        data = self.js_json(
            "(() => {"
            "  const vars = [];"
            "  const total = (typeof $dataSystem !== 'undefined' && $dataSystem.variables)"
            "    ? $dataSystem.variables.length - 1 : 0;"
            "  for (let i = 1; i <= total; i++) vars.push($gameVariables.value(i));"
            "  const switches = [];"
            "  const st = (typeof $dataSystem !== 'undefined' && $dataSystem.switches)"
            "    ? $dataSystem.switches.length - 1 : 0;"
            "  for (let i = 1; i <= st; i++) switches.push(!!$gameSwitches.value(i));"
            "  return JSON.stringify({gold: $gameParty.gold(), vars: vars, switches: switches});"
            "})()"
        )
        if data is None:
            return {"ok": False, "detail": "读取状态失败"}
        name = time.strftime("state-%Y%m%d-%H%M%S.json")
        folder = self._snapshot_dir()
        path = os.path.join(folder, name)
        with open(path, "w", encoding="utf-8") as handle:
            json.dump(data, handle, ensure_ascii=False)

                                           
        try:
            files = sorted(f for f in os.listdir(folder) if f.endswith(".json"))
            for stale in files[:-50]:
                os.remove(os.path.join(folder, stale))
        except OSError:
            pass
        return {"ok": True, "detail": f"快照已保存 {name}",
                "data_json": json.dumps({"file": name}, ensure_ascii=False)}

    def _state_restore(self, args: dict) -> dict:
        name = os.path.basename(str(args.get("file", "")))
        path = os.path.join(self._snapshot_dir(), name)
        if not name or not os.path.isfile(path):
            return {"ok": False, "detail": "快照不存在"}
        with open(path, "r", encoding="utf-8") as handle:
            data = json.load(handle)
        payload = json.dumps(data)
        result = self.js_json(
            "(() => {"
            "  try {"
            "    const snap = JSON.parse(" + json.dumps(payload) + ");"
            "    $gameParty._gold = snap.gold;"
            "    snap.vars.forEach((v, i) => $gameVariables.setValue(i + 1, v));"
            "    snap.switches.forEach((v, i) => $gameSwitches.setValue(i + 1, v));"
            "    return JSON.stringify({ok:true, detail:'已还原快照'});"
            "  } catch (e) { return JSON.stringify({ok:false, detail:String(e)}); }"
            "})()"
        ) or {"ok": False, "detail": "还原失败"}
        return {"ok": bool(result.get("ok")), "detail": result.get("detail", "")}

    def _snapshots_list(self) -> dict:
        folder = self._snapshot_dir()
        items = []
        for name in sorted(os.listdir(folder), reverse=True):
            if not name.endswith(".json"):
                continue
            full = os.path.join(folder, name)
            items.append({"name": name, "mtime": time.strftime("%Y-%m-%d %H:%M",
                                                              time.localtime(os.path.getmtime(full)))})
        return {"ok": True, "detail": f"{len(items)} 个快照",
                "data_json": json.dumps(items, ensure_ascii=False)}

    def _icon_set(self) -> dict:
        data = self.js_json(
            "(() => {"
            "  try {"
            "    const fs = require('fs'); const path = require('path');"
            "    const cwd = process.cwd();"
            "    const names = ['IconSet.png', 'IconSet.png_', 'IconSet.webp'];"
            "    const file = ['img/system', 'www/img/system']"
            "      .flatMap(d => names.map(n => path.join(cwd, d, n)))"
            "      .find(p => fs.existsSync(p));"
            "    if (!file) return JSON.stringify({ok:false, detail:'找不到 IconSet.png'});"
            "    const b64 = fs.readFileSync(file).toString('base64');"
            "    return JSON.stringify({ok:true, url:'data:image/png;base64,' + b64});"
            "  } catch (e) { return JSON.stringify({ok:false, detail:String(e)}); }"
            "})()"
        ) or {"ok": False, "detail": "no result"}
        return {
            "ok": bool(data.get("ok")),
            "detail": data.get("detail", "") or "IconSet.png",
            "data_json": json.dumps(data, ensure_ascii=False),
        }

    def _party_items(self) -> dict:
        raw = self.js(
            "(() => {"
            "  const out = [];"
            "  const push = (list, kind) => (list || []).filter(Boolean).forEach(it => {"
            "    const n = $gameParty.numItems(it);"
            "    out.push({id: it.id, name: it.name, count: n, kind: kind, iconIndex: it.iconIndex || 0});"
            "  });"
            "  push($dataItems, 'item'); push($dataWeapons, 'weapon'); push($dataArmors, 'armor');"
            "  return JSON.stringify(out.slice(0, 500));"
            "})()"
        )
        items = json.loads(raw) if raw else []
        return {"ok": True, "detail": f"{len(items)} 种道具",
                "data_json": json.dumps(items, ensure_ascii=False)}

    def _party_add_item(self, args: dict) -> dict:
        kind = str(args.get("kind", "item"))
        item_id = int(args.get("id", 0))
        count = int(args.get("count", 1))
        table = {"item": "$dataItems", "weapon": "$dataWeapons", "armor": "$dataArmors"}.get(kind)
        if not table:
            return {"ok": False, "detail": f"unknown kind '{kind}'"}

        expr = (
            "(() => {"
            "  const it = " + table + "[" + str(item_id) + "];"
            "  if (!it) return JSON.stringify({ok:false, detail:'no such item'});"
            "  $gameParty.gainItem(it, " + str(count) + ");"
            "  return JSON.stringify({ok:true, detail: it.name + ' +" + str(count)
            + " -> ' + $gameParty.numItems(it)});"
            "})()"
        )
        result = self.js_json(expr) or {"ok": False, "detail": "no result"}
        return {"ok": bool(result.get("ok")), "detail": result.get("detail", "")}

    def _party_set_item(self, args: dict) -> dict:
        kind = str(args.get("kind", "item"))
        item_id = int(args.get("id", 0))
        target = max(0, int(args.get("count", 0)))
        if kind not in ("item", "weapon", "armor"):
            return {"ok": False, "detail": "未知类型"}
        expression = (
            "(() => {"
            "  try {"
            "    const table = " + json.dumps(kind) + " === 'item' ? $dataItems"
            "      : (" + json.dumps(kind) + " === 'weapon' ? $dataWeapons : $dataArmors);"
            "    const data = table[" + str(item_id) + "];"
            "    if (!data) return JSON.stringify({ok: false, detail: '道具不存在'});"
            "    const current = $gameParty.numItems(data);"
            "    const delta = " + str(target) + " - current;"
            "    if (delta !== 0) $gameParty.gainItem(data, delta);"
            "    return JSON.stringify({ok: true, detail: data.name + ' = ' + $gameParty.numItems(data)});"
            "  } catch (e) { return JSON.stringify({ok: false, detail: String(e)}); }"
            "})()"
        )
        data = self.js_json(expression) or {"ok": False, "detail": "设置失败"}
        return {"ok": bool(data.get("ok")), "detail": data.get("detail", "")}

    def _actors_set(self, args: dict) -> dict:
        actor_id = int(args.get("id", 0))
        level = args.get("level")
        hp = args.get("hp")
        mp = args.get("mp")
        full = bool(args.get("full", False))
        expression = (
            "(() => {"
            "  try {"
            "    const actor = $gameActors.actor(" + str(actor_id) + ");"
            "    if (!actor) return JSON.stringify({ok: false, detail: '角色不存在'});"
            + ("" if level is None else
               "    actor.changeLevel(" + str(int(level)) + ", false);")
            + ("" if hp is None else
               "    actor.setHp(" + str(int(hp)) + ");")
            + ("" if mp is None else
               "    actor.setMp(" + str(int(mp)) + ");")
            + ("    actor.setHp(actor.mhp); actor.setMp(actor.mmp);" if full else "")
            + "    return JSON.stringify({ok: true, detail: actor.name() + ' Lv.' + actor.level"
              " + ' HP ' + actor.hp + '/' + actor.mhp + ' MP ' + actor.mp + '/' + actor.mmp});"
            "  } catch (e) { return JSON.stringify({ok: false, detail: String(e)}); }"
            "})()"
        )
        data = self.js_json(expression) or {"ok": False, "detail": "设置失败"}
        return {"ok": bool(data.get("ok")), "detail": data.get("detail", "")}

    def _party_gold(self) -> dict:
        data = self.js_json("(() => JSON.stringify({gold: $gameParty.gold()}))()") or {}
        gold = int(data.get("gold", 0))
        return {"ok": True, "detail": f"金币 {gold}", "data_json": json.dumps({"gold": gold})}

    def _party_set_gold(self, args: dict) -> dict:
        target = max(0, int(args.get("gold", 0)))
        data = self.js_json(
            "(() => {"
            "  try {"
            "    const delta = " + str(target) + " - $gameParty.gold();"
            "    if (delta !== 0) $gameParty.gainGold(delta);"
            "    return JSON.stringify({ok: true, gold: $gameParty.gold()});"
            "  } catch (e) { return JSON.stringify({ok: false, detail: String(e)}); }"
            "})()"
        ) or {"ok": False}
        return {"ok": bool(data.get("ok")), "detail": f"金币 {data.get('gold', target)}",
                "data_json": json.dumps(data, ensure_ascii=False)}

    def _actors_set_buff(self, args: dict) -> dict:
        actor_id = int(args.get("id", 0))
        param = max(0, min(7, int(args.get("param", 0))))
        level = max(-2, min(2, int(args.get("level", 0))))
        raw = self.js_json(
            "(() => {"
            "  try {"
            "    const a = $gameActors.actor(" + str(actor_id) + ");"
            "    if (!a) return JSON.stringify({ok: false, detail: '角色不存在'});"
            "    a._buffs[" + str(param) + "] = " + str(level) + ";"
            "    return JSON.stringify({ok: true, detail: 'BUFF 已更新'});"
            "  } catch (e) { return JSON.stringify({ok: false, detail: String(e)}); }"
            "})()"
        ) or {"ok": False}
        return {"ok": bool(raw.get("ok")), "detail": raw.get("detail", "")}

    def _actors_state_list(self) -> dict:
        raw = self.js(
            "(() => {"
            "  const out = [];"
            "  (($dataStates || []).filter(Boolean)).forEach(s => out.push("
            "    {id: s.id, name: s.name, icon: s.iconIndex || 0}));"
            "  return JSON.stringify(out.slice(0, 300));"
            "})()"
        )
        states = json.loads(raw) if raw else []
        return {"ok": True, "detail": f"{len(states)} 种状态",
                "data_json": json.dumps(states, ensure_ascii=False)}

    def _actors_buffs(self, args: dict) -> dict:
        actor_id = int(args.get("id", 0))
        raw = self.js(
            "(() => {"
            "  const a = $gameActors.actor(" + str(actor_id) + ");"
            "  if (!a) return JSON.stringify(null);"
            "  const states = a.states().map(s => ({id: s.id, name: s.name, icon: s.iconIndex || 0}));"
            "  const names = (typeof TextManager !== 'undefined')"
            "    ? [0,1,2,3,4,5,6,7].map(i => TextManager.param(i))"
            "    : ['MHP','MMP','ATK','DEF','MAT','MDF','AGI','LUK'];"
            "  const buffs = [];"
            "  for (let i = 0; i < 8; i++) {"
            "    const level = a.buff(i);"
            "    if (level !== 0) buffs.push({param: i, name: names[i], level: level,"
            "      icon: (a.buffIconIndex ? a.buffIconIndex(level, i) : 0)});"
            "  }"
            "  return JSON.stringify({states: states, buffs: buffs});"
            "})()"
        )
        data = json.loads(raw) if raw else None
        return {"ok": data is not None, "detail": "角色状态",
                "data_json": json.dumps(data, ensure_ascii=False)}

    def _actors_set_state(self, args: dict) -> dict:
        actor_id = int(args.get("id", 0))
        state_id = int(args.get("stateId", 0))
        add = bool(args.get("add", True))
        raw = self.js_json(
            "(() => {"
            "  try {"
            "    const a = $gameActors.actor(" + str(actor_id) + ");"
            "    if (!a) return JSON.stringify({ok: false, detail: '角色不存在'});"
            "    if (" + ("true" if add else "false") + ") a.addState(" + str(state_id) + ");"
            "    else a.removeState(" + str(state_id) + ");"
            "    return JSON.stringify({ok: true, detail: a.name() + ' 状态已更新'});"
            "  } catch (e) { return JSON.stringify({ok: false, detail: String(e)}); }"
            "})()"
        ) or {"ok": False}
        return {"ok": bool(raw.get("ok")), "detail": raw.get("detail", "")}

    def _actor_detail(self, args: dict) -> dict:
        actor_id = int(args.get("id", 0))
        expression = (
            "(() => {"
            "  try {"
            "    const actor = $gameActors.actor(" + str(actor_id) + ");"
            "    if (!actor) return JSON.stringify(null);"
            "    const equips = actor.equips().map((item, slot) => ({"
            "      slot: slot,"
            "      name: item ? item.name : '',"
            "      id: item ? item.id : 0,"
            "      kind: !item ? '' : (DataManager.isWeapon(item) ? 'weapon' : 'armor')"
            "    }));"
            "    return JSON.stringify({"
            "      id: actor.actorId(), name: actor.name(), level: actor.level,"
            "      exp: actor.currentExp(), nextExp: actor.nextLevelExp(),"
            "      hp: actor.hp, mhp: actor.mhp, mp: actor.mp, mmp: actor.mmp,"
            "      equips: equips"
            "    });"
            "  } catch (e) { return JSON.stringify(null); }"
            "})()"
        )
        data = self.js_json(expression)
        return {"ok": data is not None, "detail": "角色详情",
                "data_json": json.dumps(data, ensure_ascii=False)}

    def _party_item_info(self, args: dict) -> dict:
        kind = str(args.get("kind", "item"))
        item_id = int(args.get("id", 0))
        table = {"item": "$dataItems", "weapon": "$dataWeapons", "armor": "$dataArmors"}.get(kind)
        if not table:
            return {"ok": False, "detail": f"unknown kind '{kind}'"}

        data = self.js_json(
            "(() => {"
            "  const it = " + table + "[" + str(item_id) + "];"
            "  if (!it) return JSON.stringify({ok:false, detail:'no such item'});"
            "  return JSON.stringify({"
            "    ok: true, name: it.name, description: it.description || '',"
            "    price: it.price || 0, iconIndex: it.iconIndex || 0,"
            "    kind: " + json.dumps(kind) + ","
            "    wtype: it.wtypeId || it.atypeId || 0,"
            "    params: it.params || []"
            "  });"
            "})()"
        ) or {"ok": False, "detail": "no result"}
        return {
            "ok": bool(data.get("ok")),
            "detail": data.get("name", "") or data.get("detail", ""),
            "data_json": json.dumps(data, ensure_ascii=False),
        }

    def _actors_list(self) -> dict:
        raw = self.js(
            "(() => {"
            "  const out = [];"
            "  $gameParty.members().forEach(a => out.push({"
            "    id: a.actorId(), name: a.name(), level: a.level,"
            "    className: (a.currentClass() ? a.currentClass().name : ''),"
            "    hp: a.hp, mhp: a.mhp, mp: a.mp, mmp: a.mmp"
            "  }));"
            "  return JSON.stringify(out);"
            "})()"
        )
        actors = json.loads(raw) if raw else []
        return {"ok": True, "detail": f"{len(actors)} 名队员",
                "data_json": json.dumps(actors, ensure_ascii=False)}

    def _maps_list(self) -> dict:
        raw = self.js(
            "(() => {"
            "  const out = [];"
            "  const infos = (typeof $dataMapInfos !== 'undefined' && $dataMapInfos) || [];"
            "  infos.filter(Boolean).forEach(m => out.push({"
            "    id: m.id, name: m.name || '', parentId: m.parentId || 0, order: m.order || 0"
            "  }));"
            "  try {"
            "    const fs = require('fs'), path = require('path');"
            "    const cwd = process.cwd();"
            "    const dir = ['data', 'www/data'].map(d => path.join(cwd, d))"
            "      .find(d => fs.existsSync(d));"
            "    if (dir) {"
            "      const known = new Set(out.map(m => m.id));"
            "      for (const name of fs.readdirSync(dir)) {"
            "        const hit = /^Map(\\d{3})\\.json$/.exec(name);"
            "        if (!hit) continue;"
            "        const id = parseInt(hit[1], 10);"
            "        if (!known.has(id)) out.push({id: id, name: '', parentId: 0, order: 0});"
            "      }"
            "    }"
            "  } catch (e) {}"
            "  out.sort((a, b) => (a.parentId - b.parentId) || (a.order - b.order) || (a.id - b.id));"
            "  return JSON.stringify(out);"
            "})()"
        )
        maps = json.loads(raw) if raw else []
        return {"ok": True, "detail": f"{len(maps)} 张地图",
                "data_json": json.dumps(maps, ensure_ascii=False)}

                                                                         
    _KIND_BY_CODE = {
        101: "对话", 401: "对话", 405: "对话",
        102: "选项", 402: "选项", 403: "选项",
        201: "传送", 202: "传送", 203: "镜头",
        223: "商店", 302: "商店",
        301: "战斗", 601: "战斗", 602: "战斗", 603: "战斗",
        125: "金钱", 311: "金钱",
        129: "角色", 315: "角色", 316: "角色", 317: "角色", 318: "角色",
        319: "角色", 320: "角色", 321: "角色", 324: "角色", 325: "角色",
        126: "道具", 127: "道具", 128: "道具", 312: "道具", 313: "道具", 314: "道具",
        121: "开关", 122: "变量", 123: "开关",
        111: "条件", 411: "条件", 412: "条件",
        112: "循环", 413: "循环", 414: "循环", 115: "循环",
        117: "公共事件",
        204: "移动", 205: "移动", 206: "移动",
        231: "图片", 232: "图片", 235: "图片",
        241: "音频", 242: "音频", 245: "音频", 249: "音频", 250: "音频", 251: "音频",
        230: "等待",
        108: "注释", 408: "注释",
    }

    _KIND_PRIORITY = [
        "对话", "选项", "传送", "商店", "战斗", "金钱", "角色", "道具",
        "开关", "变量", "条件", "循环", "公共事件", "移动", "镜头",
        "图片", "音频", "等待", "注释", "脚本", "其他",
    ]

    def _kind_js(self) -> str:
        """Arrow function `(e) => kind`, safe to define outside a map callback.

        The old form was an immediately-invoked expression referencing `e`
        before it existed, which threw `ReferenceError: e is not defined` in
        map.preview. Priority picks the interesting command instead of the
        first one (a leading comment no longer hides dialogue).
        """
        return (
            "(e) => {"
            "  const kinds = " + json.dumps(self._KIND_BY_CODE) + ";"
            "  const priority = " + json.dumps(self._KIND_PRIORITY) + ";"
            "  const found = new Set();"
            "  for (const page of ((e && e.pages) || [])) {"
            "    for (const cmd of (page.list || [])) {"
            "      const k = kinds[cmd.code];"
            "      if (k) found.add(k);"
            "      if (cmd.code === 355 || cmd.code === 655) found.add('脚本');"
            "    }"
            "  }"
            "  for (const k of priority) if (found.has(k)) return k;"
            "  return '其他';"
            "}"
        )

    def _map_preview(self, args: dict) -> dict:
        """Read any map file from disk so the shell can preview non-current maps."""
        map_id = int(args.get("map", 0))
        if map_id <= 0:
            return {"ok": False, "detail": "缺少地图 ID"}

        map_name = "Map%03d.json" % map_id
        expression = (
            "(() => {"
            "  try {"
            "    const fs = require('fs');"
            "    const path = require('path');"
            "    const cwd = process.cwd();"
            "    const name = '" + map_name + "';"
            "    const file = ['data', 'www/data']"
            "      .map(d => path.join(cwd, d, name)).find(f => fs.existsSync(f));"
            "    if (!file) return JSON.stringify({ok:false, detail:'找不到地图文件'});"
            "    const map = JSON.parse(fs.readFileSync(file, 'utf8'));"
            "    const kindOf = " + self._kind_js() + ";"
            "    const evs = (map.events || []).filter(Boolean).slice(0, 300).map(e => ({"
            "      id: e.id, name: e.name, x: e.x, y: e.y,"
            "      note: String(e.note || '').slice(0, 60), kind: kindOf(e)"
            "    }));"
            "    return JSON.stringify({ok:true, mapId: " + str(map_id) + ","
            "      name: map.displayName || '', width: map.width, height: map.height,"
            "      events: evs});"
            "  } catch (e) { return JSON.stringify({ok:false, detail:String(e)}); }"
            "})()"
        )
        data = self.js_json(expression) or {"ok": False, "detail": "no result"}
        return {
            "ok": bool(data.get("ok")),
            "detail": data.get("name", "") or data.get("detail", ""),
            "data_json": json.dumps(data, ensure_ascii=False),
        }

    def _map_grid(self, args: dict) -> dict:
        """Passability bitmap for the current map or a map file on disk."""
        map_id = int(args.get("map", 0))
        expression = (
            "(() => {"
            "  try {"
            "    const target = " + str(map_id) + ";"
            "    const current = target <= 0 || target === $gameMap.mapId();"
            "    let map, width, height;"
            "    if (current) {"
            "      map = $dataMap; width = $gameMap.width(); height = $gameMap.height();"
            "    } else {"
            "      const fs = require('fs'), path = require('path');"
            "      const cwd = process.cwd();"
            "      const name = 'Map' + String(target).padStart(3, '0') + '.json';"
            "      const file = ['data', 'www/data'].map(d => path.join(cwd, d, name))"
            "        .find(f => fs.existsSync(f));"
            "      if (!file) return JSON.stringify({ok:false, detail:'找不到地图文件'});"
            "      map = JSON.parse(fs.readFileSync(file, 'utf8'));"
            "      width = map.width || 0; height = map.height || 0;"
            "    }"
            "    const tileset = ($dataTilesets && $dataTilesets[map.tilesetId]) || null;"
            "    const flags = tileset ? tileset.flags : null;"
            "    const total = width * height;"
            "    const bytes = new Uint8Array(Math.ceil(total / 8));"
            "    if (flags && map.data) {"
            "      for (let y = 0; y < height; y++) {"
            "        for (let x = 0; x < width; x++) {"
            "          let pass = false;"
            "          for (const z of [3, 2, 1, 0]) {"
            "            const id = map.data[(z * height + y) * width + x];"
            "            const flag = flags[id] || 0;"
            "            if ((flag & 0x10) !== 0) continue;"
            "            pass = ((flag & 0x0f) === 0);"
            "            break;"
            "          }"
            "          if (!pass) { const i = y * width + x; bytes[i >> 3] |= (1 << (i & 7)); }"
            "        }"
            "      }"
            "    }"
            "    let bin = '';"
            "    for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);"
            "    const blocked = (typeof btoa !== 'undefined')"
            "      ? btoa(bin) : Buffer.from(bin, 'binary').toString('base64');"
            "    return JSON.stringify({ok:true, mapId: current ? $gameMap.mapId() : target,"
            "      width: width, height: height, blocked: blocked});"
            "  } catch (e) { return JSON.stringify({ok:false, detail:String(e)}); }"
            "})()"
        )
        data = self.js_json(expression) or {"ok": False, "detail": "no result"}
        return {
            "ok": bool(data.get("ok")),
            "detail": data.get("detail", f"{data.get('width')}x{data.get('height')}"),
            "data_json": json.dumps(data, ensure_ascii=False),
        }

    def _player_pos(self) -> dict:
        data = self.js_json(
            "(() => JSON.stringify({mapId: $gameMap.mapId(),"
            " x: $gamePlayer.x, y: $gamePlayer.y}))()"
        ) or {}
        return {
            "ok": bool(data),
            "detail": f"({data.get('x')},{data.get('y')})",
            "data_json": json.dumps(data, ensure_ascii=False),
        }

    def _map_info(self) -> dict:
        data = self.js_json(
            "(() => {"
            "  const m = $gameMap;"
            "  return JSON.stringify({"
            "    mapId: m.mapId(),"
            "    name: ($dataMap && ($dataMap.displayName || $dataMap.name)) || '',"
            "    width: m.width(), height: m.height(),"
            "    x: $gamePlayer.x, y: $gamePlayer.y"
            "  });"
            "})()"
        ) or {}
        return {"ok": True, "detail": f"地图 {data.get('mapId','?')} {data.get('name','')}",
                "data_json": json.dumps(data, ensure_ascii=False)}

    def _events_list(self) -> dict:
        raw = self.js(
            "(() => {"
            "  const kindOf = " + self._kind_js() + ";"
            "  const evs = ($dataMap && $dataMap.events) || [];"
            "  return JSON.stringify(evs.filter(Boolean).slice(0, 300).map(e => ({"
            "    id: e.id, name: e.name, x: e.x, y: e.y,"
            "    note: String(e.note || '').slice(0, 60), kind: kindOf(e)"
            "  })));"
            "})()"
        )
        events = json.loads(raw) if raw else []
        return {"ok": True, "detail": f"{len(events)} 个事件",
                "data_json": json.dumps(events, ensure_ascii=False)}

    def _events_all(self) -> dict:
        raw = self.js(
            "(() => {"
            "  const kindOf = " + self._kind_js() + ";"
            "  const fs = require('fs'), path = require('path');"
            "  const cwd = process.cwd();"
            "  const dir = ['data', 'www/data'].map(d => path.join(cwd, d))"
            "    .find(d => fs.existsSync(d));"
            "  if (!dir) return JSON.stringify([]);"
            "  const names = {};"
            "  ((typeof $dataMapInfos !== 'undefined' && $dataMapInfos) || [])"
            "    .filter(Boolean).forEach(m => { names[m.id] = m.name || ('地图 ' + m.id); });"
            "  const out = [];"
            "  for (const file of fs.readdirSync(dir)) {"
            "    const hit = /^Map(\d{3})\.json$/.exec(file);"
            "    if (!hit) continue;"
            "    const mapId = parseInt(hit[1], 10);"
            "    let map;"
            "    try { map = JSON.parse(fs.readFileSync(path.join(dir, file), 'utf8')); }"
            "    catch (e) { continue; }"
            "    (map.events || []).filter(Boolean).forEach(e => {"
            "      out.push({"
            "        id: e.id, name: e.name, x: e.x, y: e.y, mapId: mapId,"
            "        mapName: names[mapId] || ('地图 ' + mapId),"
            "        kind: kindOf(e), note: String(e.note || '').slice(0, 80),"
            "        pages: (e.pages || []).length"
            "      });"
            "    });"
            "    if (out.length >= 5000) break;"
            "  }"
            "  return JSON.stringify(out.slice(0, 5000));"
            "})()"
        )
        events = json.loads(raw) if raw else []
        return {"ok": True, "detail": f"{len(events)} 个事件",
                "data_json": json.dumps(events, ensure_ascii=False)}

    def _player_teleport(self, args: dict) -> dict:
        map_id = int(args.get("map", 0))
        x = int(args.get("x", 0))
        y = int(args.get("y", 0))
        if map_id <= 0:
            return {"ok": False, "detail": "缺少地图 ID"}

                                                                             
                                                                    
        probe = self.js_json(
            "(() => {"
            "  try {"
            "    const target = " + str(map_id) + ", tx = " + str(x) + ", ty = " + str(y) + ";"
            "    const here = $gameMap.mapId();"
            "    let width = 0, height = 0, blocked = false, occupied = 0;"
            "    if (target === here) {"
            "      width = $gameMap.width(); height = $gameMap.height();"
            "      blocked = !$gameMap.checkPassage(tx, ty, 0x0f);"
            "      occupied = $gameMap.eventsXy(tx, ty).length;"
            "    } else {"
            "      const fs = require('fs'), path = require('path');"
            "      const cwd = process.cwd();"
            "      const name = 'Map' + String(target).padStart(3, '0') + '.json';"
            "      const file = ['data', 'www/data'].map(d => path.join(cwd, d, name))"
            "        .find(f => fs.existsSync(f));"
            "      if (file) {"
            "        const map = JSON.parse(fs.readFileSync(file, 'utf8'));"
            "        width = map.width || 0; height = map.height || 0;"
            "        const evs = (map.events || []).filter(Boolean);"
            "        occupied = evs.filter(e => e.x === tx && e.y === ty).length;"
            "        const tileset = ($dataTilesets && $dataTilesets[map.tilesetId]) || null;"
            "        const flags = tileset ? tileset.flags : null;"
            "        if (flags) {"
            "          let pass = false;"
            "          const layers = [3, 2, 1, 0];"
            "          for (const z of layers) {"
            "            const id = map.data[(z * height + ty) * width + tx];"
            "            const flag = flags[id] || 0;"
            "            if ((flag & 0x10) !== 0) continue;"
            "            pass = ((flag & 0x0f) === 0);"
            "            break;"
            "          }"
            "          blocked = !pass;"
            "        }"
            "      }"
            "    }"
            "    const inside = tx >= 0 && ty >= 0 && (width === 0 || (tx < width && ty < height));"
            "    return JSON.stringify({inside: inside, blocked: blocked,"
            "      occupied: occupied, width: width, height: height});"
            "  } catch (e) { return JSON.stringify({error: String(e)}); }"
            "})()"
        ) or {}

        if probe.get("inside") is False:
            width = probe.get("width") or 0
            height = probe.get("height") or 0
            return {
                "ok": False,
                "detail": f"坐标越界：map {map_id} ({x},{y}) 不在 {width}x{height} 内，未传送",
            }

        self.js(f"(() => {{ $gamePlayer.reserveTransfer({map_id}, {x}, {y}, 2, 0); }})()")

        notes = []
        if probe.get("blocked"):
            notes.append("目标不可通行（可能卡进墙里）")
        if probe.get("occupied"):
            notes.append(f"该格有 {probe['occupied']} 个事件")
        detail = f"传送请求已发出：map {map_id} ({x},{y})"
        if notes:
            detail += " · " + "，".join(notes)
        return {"ok": True, "detail": detail}

    def _speed_set(self, args: dict) -> dict:
        multiplier = max(1, min(10, int(args.get("multiplier", 1))))
        result = self.js_json(
            "(() => {"
            "  if (typeof SceneManager === 'undefined') "
            "    return JSON.stringify({ok:false, detail:'no SceneManager'});"
            "  if (!window.__kazeUpdateMain) window.__kazeUpdateMain = SceneManager.updateMain;"
            f"  const n = {multiplier};"
            "  SceneManager.updateMain = function() {"
            "    for (let i = 0; i < n; i++) window.__kazeUpdateMain.call(this);"
            "  };"
            "  return JSON.stringify({ok:true, detail:'speed x' + n});"
            "})()"
        ) or {"ok": False, "detail": "no result"}
        return {"ok": bool(result.get("ok")), "detail": result.get("detail", "")}

    def _text_enable(self) -> dict:
        if self.text_enabled:
            return {"ok": True, "detail": "文本采集已在运行"}
        already = self.js(
            "(() => (window.__kazeHooked === true) ? 'yes' : 'no')()"
        )
        if already == "yes":
            self.text_enabled = True
            return {"ok": True, "detail": "文本采集已在运行（复用已有钩子）"}

        result = self.js_json(
            "(() => {"
            "  if (typeof Game_Message === 'undefined') "
            "    return JSON.stringify({ok:false, detail:'Game_Message 不存在'});"
            "  if (window.__kazeHooked) "
            "    return JSON.stringify({ok:true, detail:'已在采集'});"
            "  window.__kazeHooked = true;"
            "  window.__kazeQueue = [];"
            "  const original = Game_Message.prototype.add;"
            "  Game_Message.prototype.add = function(text) {"
            "    const result = original.call(this, text);"
            "    try {"
            "      const index = this._texts.length - 1;"
            "      window.__kazeQueue.push({index: index, text: String(text)});"
            "    } catch (e) {}"
            "    return result;"
            "  };"
            "  window.__kazeHooked = true;"
            "  try {"
            "    window.__kazeQueue = window.__kazeQueue || [];"
            "    const texts = (typeof $gameMessage !== 'undefined' && $gameMessage._texts) || [];"
            "    texts.forEach(t => { if (String(t).trim()) window.__kazeQueue.push({index: 0, text: String(t)}); });"
            "  } catch (e) {}"
            "  return JSON.stringify({ok:true, detail:'已挂载 Game_Message.add'});"
            "})()"
        ) or {"ok": False, "detail": "no result"}
        if result.get("ok"):
            self.text_enabled = True
        return {"ok": bool(result.get("ok")), "detail": result.get("detail", "")}

    def _text_disable(self) -> dict:
        self.text_enabled = False
        self.js(
            "(() => {"
            "  if (window.__kazeQueue) window.__kazeQueue.length = 0;"
            "  return 'ok';"
            "})()"
        )
        return {"ok": True, "detail": "已停止文本采集"}

    def _maybe_poll(self) -> None:
        now = time.monotonic()
        if now - self._last_poll > 0.3:
            self._last_poll = now
            self.poll_text()

    def poll_text(self) -> None:
        if not self.text_enabled:
            return
        try:
            items = self.js_json(
                "(() => { if (!window.__kazeQueue) return '[]';"
                " return JSON.stringify(window.__kazeQueue.splice(0, 12)); })()"
            ) or []
        except Exception:
            return
        if not items:
            return

        units = []
        for item in items:
            text = str(item.get("text", ""))
            if not text.strip():
                continue
            key = key_of(text)
            self.pending_text[key] = int(item.get("index", 0))
            units.append(kz_wire.text_unit(key, text))
        if units:
            self.client.send(kz_wire.TEXT_BATCH, kz_wire.text_batch(ADAPTER_NAME, units))
            print(f"[mvmz] emit {len(units)} line(s)", flush=True)

    def apply_pushes(self, push: dict) -> None:
        for unit in push.get("units", []):
            key = unit.get("key", "")
            target = unit.get("target", "")
            index = self.pending_text.get(key)
            if index is None or not target:
                continue
            expression = (
                "(() => {"
                "  try {"
                "    if (typeof $gameMessage !== 'undefined' && $gameMessage._texts)"
                f"      $gameMessage._texts[{index}] = {json.dumps(target)};"
                "    const scene = (typeof SceneManager !== 'undefined') ? SceneManager._scene : null;"
                "    const w = scene && scene._messageWindow;"
                "    if (w && w._textState) {"
                f"      w._textState.text = {json.dumps(target)};"
                "      w._textState.index = 0;"
                "    }"
                "    return 1;"
                "  } catch (e) { return 0; }"
                "})()"
            )
            try:
                self.js(expression)
            except Exception as exc:
                print(f"[mvmz] apply failed: {exc}", flush=True)
            self.pending_text.pop(key, None)

    def _cheats_run(self, args: dict) -> dict:
        cheat = str(args.get("id", "")).strip()
        snippets = {
            "money": "return $gameParty.gainGold(99999)",
            "heal": (
                "return $gameParty.members().map(a => { a.setHp(a.mhp); "
                "a.setMp(a.mmp); return a.name(); })"
            ),
            "items99": (
                "return (($dataItems || []).filter(Boolean).slice(0, 300)"
                ".forEach(it => $gameParty.gainItem(it, 99)), 99)"
            ),
            "through_on": "return ($gamePlayer._through = true)",
            "through_off": "return ($gamePlayer._through = false)",
            "no_encounter": "return ($gameSystem.disableEncounter(), $gameSystem.isEncounterEnabled())",
            "encounter": "return ($gameSystem.enableEncounter(), $gameSystem.isEncounterEnabled())",
            "victory": (
                "if (typeof BattleManager !== 'undefined' && BattleManager._phase) {"
                " BattleManager.processVictory(); return true; } return false"
            ),
            "defeat": (
                "if (typeof BattleManager !== 'undefined' && BattleManager._phase) {"
                " $gameParty.members().forEach(a => a.setHp(0));"
                " BattleManager.processDefeat(); return true; } return false"
            ),
        }
        if cheat.startswith("speed_"):
            try:
                multiplier = int(cheat.split("_", 1)[1])
            except ValueError:
                return {"ok": False, "detail": f"unknown cheat '{cheat}'"}
            return self._speed_set({"multiplier": multiplier})
        if cheat == "text_hook":
            return self._text_enable()

        if cheat not in snippets:
            return {"ok": False, "detail": f"unknown cheat '{cheat}'"}

        value = self.js(f"(() => {{ {snippets[cheat]}; }})()")
        return {"ok": True, "detail": f"已执行 {cheat}", "data_json": json.dumps({"value": value})}

                                                                         

    def _game_dir(self) -> str:
        raw = self.js(
            "(() => { try { return (typeof process !== 'undefined' && process.cwd) "
            "? process.cwd() : ''; } catch (e) { return ''; } })()"
        )
        return raw or ""

    def _saves_dir(self) -> dict:
        game = self._game_dir()
        if not game:
            return {"ok": False, "detail": "拿不到游戏目录"}
        candidates = [os.path.join(game, "save"), os.path.join(game, "www", "save")]
        path = next((p for p in candidates if os.path.isdir(p)), candidates[0])
        return {"ok": True, "detail": path, "data_json": json.dumps({"path": path})}

    def _saves_list(self) -> dict:
        result = self._saves_dir()
        if not result["ok"]:
            return result
        path = json.loads(result["data_json"])["path"]
        system_files = {"global.rmmzsave", "global.rpgsave", "titleinfo.rmmzsave",
                        "config.rmmzsave", "config.rpgsave"}
        items = []
        if os.path.isdir(path):
            for name in sorted(os.listdir(path)):
                if not name.endswith(".rmmzsave") and not name.endswith(".rpgsave"):
                    continue
                if name.lower() in system_files:
                    continue
                full = os.path.join(path, name)
                items.append({
                    "name": name,
                    "size": os.path.getsize(full),
                    "mtime": time.strftime("%Y-%m-%d %H:%M", time.localtime(os.path.getmtime(full))),
                })
        return {"ok": True, "detail": f"{len(items)} 个存档", "data_json": json.dumps(items, ensure_ascii=False)}

    def _saves_backup(self) -> dict:
        result = self._saves_dir()
        if not result["ok"]:
            return result
        source = json.loads(result["data_json"])["path"]
        if not os.path.isdir(source):
            return {"ok": False, "detail": f"存档目录不存在: {source}"}

        base = os.environ.get("APPDATA") or os.path.expanduser("~")
        dest = os.path.join(base, "Kaze", "backups")
        os.makedirs(dest, exist_ok=True)
        target = os.path.join(dest, f"mvmz-{time.strftime('%Y%m%d-%H%M%S')}.zip")

        count = 0
        with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
            for root, _dirs, files in os.walk(source):
                for name in files:
                    full = os.path.join(root, name)
                    archive.write(full, os.path.relpath(full, source))
                    count += 1
        return {"ok": True, "detail": f"{count} 个文件 -> {target}",
                "data_json": json.dumps({"zip": target, "files": count}, ensure_ascii=False)}

    def _backup_dir(self) -> str:
        base = os.environ.get("APPDATA") or os.path.expanduser("~")
        path = os.path.join(base, "Kaze", "backups")
        os.makedirs(path, exist_ok=True)
        return path

    def _save_path(self, name: str) -> str | None:
        result = self._saves_dir()
        if not result["ok"]:
            return None
        folder = json.loads(result["data_json"])["path"]
        path = os.path.join(folder, os.path.basename(name))
        if os.path.isfile(path):
            return path
        print(f"[mvmz] save not found: {path}", flush=True)
        return None

                                                                            

    @staticmethod
    def _save_blob(raw: bytes) -> bytes:
        """MZ 1.10 writes a binary string (pako "to: string") as UTF-8 text."""
        text = raw.decode("utf-8", "surrogateescape")
        return bytes(ord(character) & 0xFF for character in text)

    def _decode_save(self, path: str):
        raw = open(path, "rb").read()

                                                                      
        try:
            blob = self._save_blob(raw)
            if blob[:1] == b"\x78":
                return json.loads(zlib.decompress(blob).decode("utf-8"))
        except Exception:
            pass

                            
        try:
            text = raw.decode("utf-8").lstrip()
            if text.startswith("{"):
                return json.loads(text)
        except Exception:
            pass

                                          
        try:
            text = lzstring.decompress_uint8array(raw)
            if text:
                return json.loads(text)
        except Exception:
            pass

                                               
        try:
            text = lzstring.decompress_base64(raw.decode("utf-8").strip())
            if text:
                return json.loads(text)
        except Exception:
            pass

        return None

    def _encode_save(self, path: str, data: dict) -> bool:
        """Writes the save back in the same encoding it was read with."""
        raw = open(path, "rb").read()
        text = json.dumps(data, ensure_ascii=False, separators=(",", ":"))

        try:
            blob = self._save_blob(raw)
            zlib_mode = blob[:1] == b"\x78"
        except Exception:
            zlib_mode = False

        if zlib_mode:
            packed = zlib.compress(text.encode("utf-8"), 1)
            payload = "".join(chr(byte) for byte in packed).encode("utf-8")
        elif raw.lstrip()[:1] == b"{":
            payload = text.encode("utf-8")
        elif raw.strip()[:1] in (b"A", b"B", b"C", b"D", b"E", b"F", b"G", b"H"):
            payload = lzstring.compress_to_base64(text).encode("utf-8")
        else:
            payload = lzstring.compress_to_uint8array(text)

        backup = path + ".kaze-backup"
        if not os.path.exists(backup):
            shutil.copy2(path, backup)
        with open(path, "wb") as handle:
            handle.write(payload)
        return True

    def _saves_inspect(self, args: dict) -> dict:
        name = str(args.get("file", ""))
        path = self._save_path(name)
        if not path:
            return {"ok": False, "detail": "存档不存在"}
        try:
            data = self._decode_save(path)
        except Exception as exc:
            return {"ok": False, "detail": f"解码失败: {exc}"}
        if not data:
            return {"ok": False, "detail": "无法识别存档格式"}

        party = data.get("party") or {}
        variables = ((data.get("variables") or {}).get("_data")) or []
        switches = ((data.get("switches") or {}).get("_data")) or []

        numbers = {}
        for index, value in enumerate(variables):
            if isinstance(value, (int, float)) and not isinstance(value, bool):
                numbers[str(index)] = value
        enabled = [str(index) for index, value in enumerate(switches) if value]

        return {
            "ok": True,
            "detail": f"金币 {party.get('_gold', 0)}",
            "data_json": json.dumps({
                "file": name,
                "gold": party.get("_gold", 0),
                "mapId": (data.get("map") or {}).get("_mapId", 0),
                "steps": party.get("_steps", 0),
                "variables": numbers,
                "switches": enabled,
            }, ensure_ascii=False),
        }

    def _saves_patch(self, args: dict) -> dict:
        name = str(args.get("file", ""))
        path = self._save_path(name)
        if not path:
            return {"ok": False, "detail": "存档不存在"}
        try:
            data = self._decode_save(path)
        except Exception as exc:
            return {"ok": False, "detail": f"解码失败: {exc}"}
        if not data:
            return {"ok": False, "detail": "无法识别存档格式"}

        gold = args.get("gold")
        if gold is not None:
            data.setdefault("party", {})["_gold"] = int(gold)

        for key, value in (args.get("variables") or {}).items():
            try:
                index = int(key)
            except ValueError:
                continue
            array = data.setdefault("variables", {}).setdefault("_data", [])
            while len(array) <= index:
                array.append(None)
            array[index] = value

        for key, value in (args.get("switches") or {}).items():
            try:
                index = int(key)
            except ValueError:
                continue
            array = data.setdefault("switches", {}).setdefault("_data", [])
            while len(array) <= index:
                array.append(None)
            array[index] = bool(value)

        try:
            self._encode_save(path, data)
        except Exception as exc:
            return {"ok": False, "detail": f"写入失败: {exc}"}
        return {"ok": True, "detail": f"已写入 {name}（原文件备份 .kaze-backup）",
                "data_json": json.dumps({"file": name, "gold": data.get("party", {}).get("_gold", 0)})}
    def _saves_backups(self) -> dict:
        folder = self._backup_dir()
        items = []
        for name in sorted(os.listdir(folder)):
            if not name.startswith("mvmz-") or not name.endswith(".zip"):
                continue
            full = os.path.join(folder, name)
            items.append({
                "name": name,
                "size": os.path.getsize(full),
                "mtime": time.strftime("%Y-%m-%d %H:%M", time.localtime(os.path.getmtime(full))),
            })
        items.sort(key=lambda item: item["mtime"], reverse=True)
        return {"ok": True, "detail": f"{len(items)} 个备份",
                "data_json": json.dumps(items, ensure_ascii=False)}

    def _saves_restore_one(self, args: dict) -> dict:
        name = os.path.basename(str(args.get("name", "")))
        if not name:
            return {"ok": False, "detail": "缺少备份文件名"}
        folder = self._backup_dir()
        archive_path = os.path.join(folder, name)
        if not os.path.isfile(archive_path):
            return {"ok": False, "detail": f"备份不存在：{name}"}

        result = self._saves_dir()
        if not result["ok"]:
            return result
        target = json.loads(result["data_json"])["path"]

        safety = target + "-before-restore"
        try:
            if os.path.isdir(safety):
                shutil.rmtree(safety)
            shutil.copytree(target, safety)
            with zipfile.ZipFile(archive_path) as archive:
                archive.extractall(target)
        except Exception as exc:
            return {"ok": False, "detail": f"还原失败: {exc}"}
        return {"ok": True, "detail": f"已还原 {name}（原存档留 {os.path.basename(safety)}）"}

    def _saves_restore(self) -> dict:
        result = self._saves_dir()
        if not result["ok"]:
            return result
        target = json.loads(result["data_json"])["path"]

        base = os.environ.get("APPDATA") or os.path.expanduser("~")
        backup_dir = os.path.join(base, "Kaze", "backups")
        if not os.path.isdir(backup_dir):
            return {"ok": False, "detail": "没有备份目录"}
        archives = [
            os.path.join(backup_dir, n) for n in os.listdir(backup_dir)
            if n.startswith("mvmz-") and n.endswith(".zip")
        ]
        if not archives:
            return {"ok": False, "detail": "没有 MV/MZ 备份"}
        latest = max(archives, key=os.path.getmtime)

        safety = target + "-before-restore"
        if os.path.isdir(safety):
            shutil.rmtree(safety)
        shutil.copytree(target, safety)
        with zipfile.ZipFile(latest) as archive:
            archive.extractall(target)
        return {"ok": True, "detail": f"已还原 {os.path.basename(latest)}"}

                                                                         

    def run(self) -> None:
        print(f"[mvmz] waiting for relay messages", flush=True)
        while True:
            self._maybe_poll()
            message = self.client.recv(timeout=0.25)
            if message is None:
                continue
            msg_id, body, payload = message

            if body == "ping":
                ping = kz_wire.parse_ping(payload)
                self.client.send(kz_wire.PONG, kz_wire.pong(ping["client_time_ms"],
                                                             int(time.time() * 1000)))
            elif body == "attach":
                self.attached = True
                info = self.handle_invoke({"op": "engine.info", "args_json": "{}"})
                print(f"[mvmz] attach -> {info['detail']}", flush=True)
            elif body == "invoke_request":
                request = kz_wire.parse_invoke_request(payload)
                result = self.handle_invoke(request)
                self.client.send(
                    kz_wire.INVOKE_RESULT,
                    kz_wire.invoke_result(
                        bool(result.get("ok")),
                        result.get("detail", ""),
                        result.get("data_json", ""),
                    ),
                    msg_id=msg_id,
                )
                print(f"[mvmz] invoke {request['op']} ok={result.get('ok')}", flush=True)
            elif body == "text_push":
                push = kz_wire.parse_text_push(payload)
                self.apply_pushes(push)
                print(f"[mvmz] push {len(push['units'])} unit(s) applied", flush=True)


def main() -> int:
    parser = argparse.ArgumentParser(description="Kaze RPG Maker MV/MZ adapter")
    parser.add_argument("--port", type=int, default=9222, help="CDP port")
    parser.add_argument("--plugin-only", action="store_true", help="use the in-game plugin bridge only")
    parser.add_argument("--pipe")
    parser.add_argument("--token")
    args = parser.parse_args()

    pipe_name, token = args.pipe, args.token
    if not pipe_name or not token:
        pipe_name, token = read_session()

                                                                                
                                                                             
    bridge = PluginBridge()
    if not bridge.start():
        print("[mvmz] 9223 已被另一个适配器占用，退出（未向 relay 注册）", flush=True)
        return 1

    client = KazeClient(pipe_name, token, ROLE_ADAPTER, ADAPTER_NAME)
    welcome = client.handshake()
    print(f"[mvmz] connected session={welcome['session']} relay={welcome['relay_version']}", flush=True)

    adapter = MvmzAdapter(client, args.port)
    adapter.bridge = bridge
    if args.plugin_only:
        print("[mvmz] plugin-only mode; waiting for KazeBridge on 9223", flush=True)
    else:
        try:
            adapter.connect()
            print(f"[mvmz] CDP connected on port {args.port}", flush=True)
        except Exception as exc:
            print(f"[mvmz] 等待游戏插件连接 9223（CDP: {exc}）", flush=True)

    try:
        adapter.run()
    except KeyboardInterrupt:
        pass
    finally:
        adapter.cdp.close()
        client.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
