"""Kaze Ren'Py adapter.

Two ways to run:

1. Standalone demo (no game, no protobuf package needed):

       python renpy_adapter.py --demo

   Connects to kaze-relay, waits for an AttachRequest from the shell, then
   streams fictional lines in batches so the whole pipe can be verified.

2. Inside a real Ren'Py game:

       init python:
           import renpy_adapter
           kaze_adapter = renpy_adapter.install()

   `install()` hooks `config.say_menu_text_filter`: every displayed line is
   buffered, sent as a batch, and replaced by the translation as soon as a
   TextPush comes back.
"""

from __future__ import annotations

import argparse
import ast
import hashlib
import json
import os
import shutil
import sys
import threading
import time
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import kz_wire              
from kaze_client import KazeClient              

ROLE_ADAPTER = 2
ADAPTER_NAME = "renpy"
FLUSH_INTERVAL = 0.35            
FLUSH_UNITS = 12                         

DEMO_LINES = [
    ("ミナ", "おはよう、今日もいい天気ですね。"),
    ("ユウ", "この店、ずっと気になってたんだ。"),
    ("ミナ", "……ごめん、少し考えさせて。"),
    ("ナレーション", "雨が上がり、石畳がゆっくり乾いていく。"),
    ("ユウ", "明日もここで待ち合わせよう。"),
    ("ミナ", "このケーキ、名前はまだ決めてないんだ。"),
    ("ユウ", "じゃあ、僕が考えてもいい？"),
    ("ミナ", "ふふ、期待してる。"),
    ("ナレーション", "窓の外では、傘を閉じる音が続いていた。"),
    ("ユウ", "――ところで、さっきの話の続きなんだけど。"),
    ("ミナ", "うん。ちゃんと聞くよ。"),
    ("ナレーション", "二人の声だけが、静かな店内に残った。"),
]


def session_path() -> str:
    base = os.environ.get("LOCALAPPDATA") or os.path.expanduser("~")
    return os.path.join(base, "Kaze", "session", "relay.json")


def read_session() -> tuple[str, str]:
    with open(session_path(), "r", encoding="utf-8") as handle:
        data = json.load(handle)
    return data["pipe"], data["token"]


def key_of(text: str) -> str:
    return hashlib.blake2b(text.encode("utf-8"), digest_size=8).hexdigest()


class Batcher:
    """Collects lines and flushes them as one TextBatch with neighbour context."""

    def __init__(self, client: KazeClient, interval: float = FLUSH_INTERVAL,
                 max_units: int = FLUSH_UNITS) -> None:
        self.client = client
        self.interval = interval
        self.max_units = max_units
        self._pending: list[tuple[str, str, str]] = []
        self._last_source = ""
        self._lock = threading.Lock()
        self._last_flush = time.monotonic()

    def add(self, speaker: str, text: str, scene: str = "") -> None:
        if not text:
            return
        with self._lock:
            self._pending.append((speaker, text, scene))
            due = len(self._pending) >= self.max_units
        if due:
            self.flush()

    def flush_if_due(self) -> None:
        with self._lock:
            due = self._pending and (time.monotonic() - self._last_flush) >= self.interval
        if due:
            self.flush()

    def flush(self) -> None:
        with self._lock:
            pending, self._pending = self._pending, []
            self._last_flush = time.monotonic()
        if not pending:
            return

        units = []
        for speaker, text, scene in pending:
            units.append(
                kz_wire.text_unit(
                    key=key_of(text),
                    source=text,
                    speaker=speaker,
                    scene=scene,
                    context=self._last_source,
                )
            )
            self._last_source = text

        self.client.send(kz_wire.TEXT_BATCH, kz_wire.text_batch(ADAPTER_NAME, units))
        print(f"[renpy] emit {len(units)} unit(s)  [batched]", flush=True)


class Adapter:
    def __init__(self, client: KazeClient, demo: bool = False) -> None:
        self.client = client
        self.cache: dict[str, str] = {}
        self.attached = threading.Event()
        self.batcher = Batcher(client)
        self._stop = threading.Event()
        self.demo = demo
        self.demo_state = {
            "gold": 1200,
            "hp": 48,
            "max_hp": 60,
            "potions": 3,
            "affection_mina": 35,
            "chapter": 3,
            "player_name": "ユウ",
            "skip_unseen": False,
            "god_mode": False,
            "unlock_all": False,
        }

                                                                         

    def emit(self, lines: list[tuple[str, str]], scene: str = "") -> None:
        for speaker, text in lines:
            self.batcher.add(speaker, text, scene)

                                                                         

    def run(self) -> None:
        threading.Thread(target=self._flusher, daemon=True).start()
        while not self._stop.is_set():
            message = self.client.recv(timeout=0.25)
            if message is None:
                self.batcher.flush_if_due()
                continue

            msg_id, body, payload = message
            if body == "attach":
                attach = kz_wire.parse_attach(payload)
                print(f"[renpy] attach requested game={attach['game_id']}", flush=True)
                self.attached.set()
            elif body == "text_push":
                push = kz_wire.parse_text_push(payload)
                for unit in push["units"]:
                    self.cache[unit["key"]] = unit["target"]
                print(f"[renpy] push {len(push['units'])} unit(s)", flush=True)
            elif body == "ping":
                ping = kz_wire.parse_ping(payload)
                now = int(time.time() * 1000)
                self.client.send(kz_wire.PONG, kz_wire.pong(ping["client_time_ms"], now))
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
                print(f"[renpy] invoke {request['op']} ok={result.get('ok')}", flush=True)

                                                                         

    def handle_invoke(self, request: dict) -> dict:
        op = request.get("op", "")
        try:
            args = json.loads(request.get("args_json") or "{}")
        except Exception:
            args = {}

        if op == "vars.list":
            return self._vars_list()
        if op == "vars.set":
            return self._vars_set(args)
        if op == "saves.dir":
            return self._saves_dir()
        if op == "saves.list":
            return self._saves_list()
        if op == "saves.backup":
            return self._saves_backup()
        if op == "saves.restore":
            return self._saves_restore()
        if op == "cheats.list":
            return self._cheats_list()
        if op == "cheats.run":
            return self._cheats_run(args)
        if op == "engine.info":
            return self._engine_info()
        return {"ok": False, "detail": f"unknown op '{op}'"}

                                                                         

    CHEATS = [
        {"id": "skip_unseen", "name": "跳过已读", "desc": "允许跳过未读文本（preferences.skip_unseen）"},
        {"id": "instant_text", "name": "瞬显文本", "desc": "文字立即出现（preferences.text_cps = 0）"},
        {"id": "auto_forward", "name": "自动前进", "desc": "自动播放对话（preferences.afm_time）"},
        {"id": "rollback", "name": "开启回滚", "desc": "允许回滚（config.rollback_enabled = True）"},
        {"id": "console", "name": "开发者控制台", "desc": "开启 Shift+O 控制台（config.console = True）"},
        {"id": "fullscreen", "name": "切换全屏", "desc": "翻转 preferences.fullscreen"},
        {"id": "mute", "name": "静音", "desc": "preferences.mute_volume = 0"},
    ]

    def _cheats_list(self) -> dict:
        return {
            "ok": True,
            "detail": f"{len(self.CHEATS)} cheats",
            "data_json": json.dumps(self.CHEATS, ensure_ascii=False),
        }

    def _cheats_run(self, args: dict) -> dict:
        cheat = str(args.get("id", "")).strip()
        known = {item["id"] for item in self.CHEATS}
        if cheat not in known:
            return {"ok": False, "detail": f"unknown cheat '{cheat}'"}
        if self.demo:
            return {"ok": True, "detail": f"(demo) 执行 {cheat}"}

        try:
            import renpy.config as config                
            import renpy.game as game
            prefs = game.preferences
            if cheat == "skip_unseen":
                prefs.skip_unseen = True
            elif cheat == "instant_text":
                prefs.text_cps = 0
            elif cheat == "auto_forward":
                prefs.afm_time = 15
            elif cheat == "rollback":
                config.rollback_enabled = True
            elif cheat == "console":
                config.console = True
            elif cheat == "fullscreen":
                prefs.fullscreen = not getattr(prefs, "fullscreen", False)
            elif cheat == "mute":
                prefs.mute_volume = 0.0 if getattr(prefs, "mute_volume", 1.0) > 0 else 1.0
        except Exception as exc:
            return {"ok": False, "detail": f"{cheat} 失败: {exc}"}
        return {"ok": True, "detail": f"已执行 {cheat}"}

                                                                         

    def _saves_list(self) -> dict:
        if self.demo:
            items = [
                {"name": "1-1-LT1.save", "size": 48213, "mtime": "2026-10-30 09:12"},
                {"name": "1-2-LT1.save", "size": 49220, "mtime": "2026-10-30 09:40"},
            ]
            return {"ok": True, "detail": f"{len(items)} saves (demo)",
                    "data_json": json.dumps(items, ensure_ascii=False)}

        try:
            import renpy.config as config                
            source = config.savedir
        except Exception as exc:
            return {"ok": False, "detail": f"config.savedir unavailable: {exc}"}

        items = []
        if source and os.path.isdir(source):
            for name in sorted(os.listdir(source)):
                if not name.endswith(".save"):
                    continue
                full = os.path.join(source, name)
                items.append({
                    "name": name,
                    "size": os.path.getsize(full),
                    "mtime": time.strftime("%Y-%m-%d %H:%M", time.localtime(os.path.getmtime(full))),
                })
        return {"ok": True, "detail": f"{len(items)} saves",
                "data_json": json.dumps(items, ensure_ascii=False)}

    def _saves_restore(self) -> dict:
        if self.demo:
            return {"ok": False, "detail": "demo 模式没有存档目录"}

        base = os.environ.get("APPDATA") or os.path.expanduser("~")
        backup_dir = os.path.join(base, "Kaze", "backups")
        if not os.path.isdir(backup_dir):
            return {"ok": False, "detail": "没有备份目录"}

        archives = [
            os.path.join(backup_dir, name)
            for name in os.listdir(backup_dir)
            if name.endswith(".zip")
        ]
        if not archives:
            return {"ok": False, "detail": "没有备份文件"}
        latest = max(archives, key=os.path.getmtime)

        try:
            import renpy.config as config                
            target = config.savedir
        except Exception as exc:
            return {"ok": False, "detail": f"config.savedir unavailable: {exc}"}

        safety = target + "-before-restore"
        try:
            if os.path.isdir(safety):
                shutil.rmtree(safety)
            shutil.copytree(target, safety)
            with zipfile.ZipFile(latest) as archive:
                archive.extractall(target)
        except Exception as exc:
            return {"ok": False, "detail": f"还原失败: {exc}"}

        return {"ok": True, "detail": f"已还原 {os.path.basename(latest)}（原存档备份在 {safety}）"}

    def _vars_list(self) -> dict:
        if self.demo:
            items = [
                {"name": name, "type": type(value).__name__, "value": repr(value)}
                for name, value in sorted(self.demo_state.items())
            ]
            return {"ok": True, "detail": f"{len(items)} vars (demo)",
                    "data_json": json.dumps(items, ensure_ascii=False)}

        try:
            import renpy.store as store                
        except Exception as exc:
            return {"ok": False, "detail": f"renpy.store unavailable: {exc}"}

        items = []
        for name, value in vars(store).items():
            if name.startswith("_") or callable(value):
                continue
            items.append({
                "name": name,
                "type": type(value).__name__,
                "value": repr(value)[:120],
            })
            if len(items) >= 200:
                break
        items.sort(key=lambda item: item["name"])
        return {"ok": True, "detail": f"{len(items)} vars",
                "data_json": json.dumps(items, ensure_ascii=False)}

    def _vars_set(self, args: dict) -> dict:
        name = str(args.get("name", "")).strip()
        raw = str(args.get("value", ""))
        if not name:
            return {"ok": False, "detail": "missing name"}

        try:
                                                                              
                                                                         
            value = json.loads(raw)
        except Exception:
            try:
                value = ast.literal_eval(raw)
            except Exception:
                value = raw

        if self.demo:
            self.demo_state[name] = value
            return {"ok": True, "detail": f"(demo) {name} = {value!r}"}

        try:
            import renpy.store as store                
            setattr(store, name, value)
        except Exception as exc:
            return {"ok": False, "detail": f"set failed: {exc}"}
        return {"ok": True, "detail": f"{name} = {value!r}"}

    def _saves_dir(self) -> dict:
        if self.demo:
            return {"ok": True, "detail": "(demo) 无真实存档目录",
                    "data_json": json.dumps({"path": "(demo)"})}
        try:
            import renpy.config as config                
            path = config.savedir
        except Exception as exc:
            return {"ok": False, "detail": f"config.savedir unavailable: {exc}"}
        return {"ok": True, "detail": path, "data_json": json.dumps({"path": path})}

    def _saves_backup(self) -> dict:
        if self.demo:
            return {"ok": False, "detail": "demo 模式没有真实存档目录"}

        try:
            import renpy.config as config                
            source = config.savedir
        except Exception as exc:
            return {"ok": False, "detail": f"config.savedir unavailable: {exc}"}
        if not source or not os.path.isdir(source):
            return {"ok": False, "detail": f"存档目录不存在: {source}"}

        base = os.environ.get("APPDATA") or os.path.expanduser("~")
        dest_dir = os.path.join(base, "Kaze", "backups")
        os.makedirs(dest_dir, exist_ok=True)
        stamp = time.strftime("%Y%m%d-%H%M%S")
        zip_path = os.path.join(dest_dir, f"renpy-{stamp}.zip")

        count = 0
        try:
            with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as archive:
                for root, _dirs, files in os.walk(source):
                    for name in files:
                        full = os.path.join(root, name)
                        archive.write(full, os.path.relpath(full, source))
                        count += 1
        except Exception as exc:
            return {"ok": False, "detail": f"backup failed: {exc}"}

        return {
            "ok": True,
            "detail": f"{count} 个文件 -> {zip_path}",
            "data_json": json.dumps({"zip": zip_path, "files": count}, ensure_ascii=False),
        }

    def _engine_info(self) -> dict:
        if self.demo:
            return {"ok": True, "detail": "renpy demo",
                    "data_json": json.dumps({"engine": "renpy", "version": "demo"})}
        try:
            import renpy                
            version = getattr(renpy, "version", "unknown")
        except Exception:
            version = "unknown"
        return {"ok": True, "detail": "renpy",
                "data_json": json.dumps({"engine": "renpy", "version": str(version)})}

    def _flusher(self) -> None:
        while not self._stop.is_set():
            time.sleep(0.1)
            self.batcher.flush_if_due()

    def stop(self) -> None:
        self._stop.set()
        self.batcher.flush()


def run_demo(adapter: Adapter) -> None:
    def stream() -> None:
        adapter.attached.wait()
        index = 0
        while not adapter._stop.is_set():
            chunk = [DEMO_LINES[(index + i) % len(DEMO_LINES)] for i in range(6)]
            adapter.emit(chunk, scene="ch03.cafe")
            index += 6
            time.sleep(4.0)

    threading.Thread(target=stream, daemon=True).start()
    adapter.run()


def install(pipe_name: str | None = None, token: str | None = None):
    """Hook Ren'Py. Returns the adapter so callers can shut it down."""
    import renpy.config as config                
    import renpy.game as game

    if pipe_name is None or token is None:
        pipe_name, token = read_session()

    client = KazeClient(pipe_name, token, ROLE_ADAPTER, ADAPTER_NAME)
    welcome = client.handshake()
    print(f"[renpy] welcome {welcome['session']} relay={welcome['relay_version']}")

    adapter = Adapter(client)
    adapter.attached.set()

    def say_filter(text: str) -> str:
        if not text:
            return text
        key = key_of(text)
        if key not in adapter.cache:
            try:
                scene = str(game.context().current)
            except Exception:
                scene = ""
            adapter.batcher.add("", text, scene)
        return adapter.cache.get(key, text)

    config.say_menu_text_filter = say_filter
    threading.Thread(target=adapter.run, daemon=True).start()
    print("[renpy] hooked config.say_menu_text_filter")
    return adapter


def main() -> int:
    parser = argparse.ArgumentParser(description="Kaze Ren'Py adapter")
    parser.add_argument("--demo", action="store_true", help="stream fictional lines")
    parser.add_argument("--pipe", help="pipe name (default: session file)")
    parser.add_argument("--token", help="session token (default: session file)")
    args = parser.parse_args()

    pipe_name, token = args.pipe, args.token
    if not pipe_name or not token:
        pipe_name, token = read_session()

    client = KazeClient(pipe_name, token, ROLE_ADAPTER, ADAPTER_NAME)
    welcome = client.handshake()
    print(f"[renpy] connected session={welcome['session']} relay={welcome['relay_version']}", flush=True)

    adapter = Adapter(client, demo=args.demo)
    try:
        if args.demo:
            run_demo(adapter)
        else:
            print("[renpy] nothing to do without --demo; use install() inside Ren'Py")
            adapter.run()
    except KeyboardInterrupt:
        pass
    finally:
        adapter.stop()
        client.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
