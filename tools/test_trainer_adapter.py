"""Offline checks for the trainer adapters (no game, no relay, no UI).

Run:  python tools/test_trainer_adapter.py
"""

from __future__ import annotations

import json as jsonlib
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, os.path.join(ROOT, "adapters", "mvmz"))
sys.path.insert(0, os.path.join(ROOT, "adapters", "renpy"))
sys.path.insert(0, os.path.join(ROOT, "adapters", "memory"))

import mvmz_adapter
import memory_adapter
import kz_wire
import renpy_adapter

FAILED: list[str] = []


def check(name: str, condition: bool, detail: str = "") -> None:
    print(f"[{'PASS' if condition else 'FAIL'}] {name}" + (f"  {detail}" if detail else ""))
    if not condition:
        FAILED.append(name)


def fake_mv():
    adapter = object.__new__(mvmz_adapter.MvmzAdapter)
    adapter.captured: list[str] = []
    adapter.js_json = lambda expr: (adapter.captured.append(expr), {"ok": True, "detail": "ok"})[1]
    adapter.js = lambda expr: (adapter.captured.append(expr), True)[1]
    return adapter


def node_eval(prefix: str, expr: str, after: str = "") -> dict:
    node = shutil.which("node")
    if not node:
        return {}
    code = (
        prefix
        + "\nconst __r = " + expr + ";"
        + "\nconsole.log(JSON.stringify({r: __r" + after + "}));"
    )
    out = subprocess.run(
        [node, "-e", code], capture_output=True, text=True, timeout=30,
        encoding="utf-8", errors="replace",
    )
    if out.returncode != 0:
        print(out.stderr)
        return {}
    return jsonlib.loads(out.stdout.strip() or "{}")


def test_switch_boolean() -> None:
    adapter = fake_mv()
    adapter._vars_set({"name": "s[3]", "value": "false"})
    expr = adapter.captured[-1]
    check(
        "switch off is a real boolean",
        "toLowerCase() === 'true'" in expr and "setValue(idx, on)" in expr,
    )


def test_switch_execution() -> None:
    prefix = (
        "globalThis.$gameVariables = {setValue: () => {}};"
        "globalThis.__switch = null;"
        "globalThis.$gameSwitches = {setValue: (i, v) => {globalThis.__switch = v;}};"
    )
    for raw, expected in (("false", False), ("true", True), ("False", False)):
        adapter = fake_mv()
        adapter._vars_set({"name": "s[1]", "value": raw})
        result = node_eval(prefix, adapter.captured[-1], ", sw: globalThis.__switch")
        check(
            f"switch '{raw}' really stores {expected}",
            result.get("sw") is expected,
            str(result),
        )


def test_encounter_cheat() -> None:
    adapter = fake_mv()
    adapter._cheats_run({"id": "no_encounter"})
    expr = adapter.captured[-1]
    check("MZ encounter uses disableEncounter", "disableEncounter" in expr)
    check("MZ encounter no longer touches _encounterRate", "_encounterRate" not in expr)

    adapter.captured.clear()
    adapter._cheats_run({"id": "encounter"})
    check("MZ encounter restore uses enableEncounter", "enableEncounter" in adapter.captured[-1])


def test_encounter_execution() -> None:
    prefix = (
        "globalThis.__disabled = false;"
        "globalThis.$gameSystem = {"
        "  disableEncounter() {globalThis.__disabled = true;},"
        "  enableEncounter() {globalThis.__disabled = false;},"
        "  isEncounterEnabled() {return !globalThis.__disabled;}"
        "};"
    )
    adapter = fake_mv()
    adapter._cheats_run({"id": "no_encounter"})
    result = node_eval(prefix, adapter.captured[-1], ", disabled: globalThis.__disabled")
    check("disableEncounter really disables", result.get("disabled") is True, str(result))


def test_speed_multiplier() -> None:
    adapter = fake_mv()
    adapter._cheats_run({"id": "speed_2"})
    check("speed cheat parses multiplier", "const n = 2" in adapter.captured[-1])

    adapter.captured.clear()
    adapter._cheats_run({"id": "speed_1"})
    check("speed restore parses multiplier", "const n = 1" in adapter.captured[-1])


def test_speed_execution() -> None:
    prefix = (
        "globalThis.window = {};"
        "globalThis.__calls = 0;"
        "globalThis.SceneManager = {updateMain() {globalThis.__calls++;}};"
    )
    adapter = fake_mv()
    adapter._cheats_run({"id": "speed_2"})
    result = node_eval(
        prefix, adapter.captured[-1],
        ", calls: (SceneManager.updateMain(), globalThis.__calls)",
    )
    check("speed x2 steps the frame twice", result.get("calls") == 2, str(result))


def test_map_grid() -> None:
    adapter = fake_mv()
    adapter._map_grid({"map": 0})
    expr = adapter.captured[-1]
    prefix = (
        "globalThis.$gameMap = {mapId: () => 1, width: () => 2, height: () => 2};"
        "globalThis.$dataTilesets = [null, {flags: [0, 0x0f, 0x10]}];"
        "globalThis.$dataMap = {tilesetId: 1, data: ["
        "0,1,0,0, 0,1,0,0, 0,1,0,0, 0,1,0,0]};"
    )
    result = node_eval(prefix, expr)
    payload = jsonlib.loads(result.get("r") or "{}")
    check("map grid returns a bitmap", payload.get("ok") is True, str(payload)[:80])
    check("map grid marks the solid tile", payload.get("blocked") == "Ag==", str(payload)[:80])



    prefix2 = (
        "globalThis.$gameMap = {mapId: () => 1, width: () => 2, height: () => 2};"
        "globalThis.$dataTilesets = [null, {flags: [0, 0x0f]}];"
        "globalThis.$dataMap = {tilesetId: 1, data: ["
        "0,0,0,0, 0,0,0,0, 0,0,0,0, 0,1,0,0]};"
    )
    adapter = fake_mv()
    adapter._map_grid({"map": 0})
    result = node_eval(prefix2, adapter.captured[-1])
    payload = jsonlib.loads(result.get("r") or "{}")
    check("an upper wall still blocks", payload.get("blocked") == "Ag==", str(payload)[:80])


def test_kind_priority() -> None:
    adapter = fake_mv()
    expr = adapter._kind_js()
    prefix = "const e = {pages: [{list: [{code: 108}, {code: 401}]}]};"
    result = node_eval(prefix, "(" + expr + ")(e)")
    check("dialogue outranks a leading comment", result.get("r") == "对话", str(result))


def test_var_labels() -> None:
    prefix = (
        "globalThis.$dataSystem = {"
        "  switches: ['', '开关一', '开关二'], variables: ['', '变量一']};"
        "globalThis.$gameSwitches = {value: (i) => i === 1};"
        "globalThis.$gameVariables = {value: (i) => 42};"
    )
    adapter = fake_mv()
    adapter._vars_page({"kind": "switch", "offset": 0, "limit": 2})
    payload = jsonlib.loads(node_eval(prefix, adapter.captured[-1]).get("r") or "{}")
    items = payload.get("items") or []
    check("switch page carries names", items and items[0].get("label") == "开关一", str(items)[:100])

    adapter = fake_mv()
    adapter._vars_page({"kind": "var", "offset": 0, "limit": 1})
    payload = jsonlib.loads(node_eval(prefix, adapter.captured[-1]).get("r") or "{}")
    items = payload.get("items") or []
    check("variable page carries names", items and items[0].get("label") == "变量一", str(items)[:100])


def test_var_pagination() -> None:
    prefix = (
        "globalThis.$dataSystem = {"
        "  switches: ['', 'even', 'odd', 'even'],"
        "  variables: ['', 'alpha', 'beta', 'alphabet', 'alpha']};"
        "globalThis.$gameVariables = {value: (i) => i * 10};"
        "globalThis.$gameSwitches = {value: (i) => i % 2 === 0};"
    )
    adapter = fake_mv()
    for offset, expected_names in ((0, ["v[1]", "v[2]"]), (2, ["v[3]", "v[4]"]), (4, [])):
        adapter.captured.clear()
        adapter._vars_page({"kind": "var", "offset": offset, "limit": 2})
        payload = jsonlib.loads(node_eval(prefix, adapter.captured[-1]).get("r") or "{}")
        items = payload.get("items") or []
        check(
            f"vars.page offset={offset} returns the requested window",
            payload.get("total") == 4 and [row["name"] for row in items] == expected_names,
            str(payload),
        )

    for offset, expected_names in ((0, ["v[1]"]), (1, ["v[3]"]), (2, ["v[4]"]), (3, [])):
        adapter.captured.clear()
        adapter._vars_search({"kind": "var", "q": "alpha", "offset": offset, "limit": 1})
        payload = jsonlib.loads(node_eval(prefix, adapter.captured[-1]).get("r") or "{}")
        items = payload.get("items") or []
        check(
            f"vars.search offset={offset} counts all matches before paging",
            payload.get("total") == 3 and payload.get("offset") == offset
            and [row["name"] for row in items] == expected_names,
            str(payload),
        )

    adapter.captured.clear()
    adapter._vars_search({"kind": "switch", "q": "even", "offset": 1, "limit": 1})
    payload = jsonlib.loads(node_eval(prefix, adapter.captured[-1]).get("r") or "{}")
    items = payload.get("items") or []
    check(
        "switch search uses the same matched-total/window semantics",
        payload.get("total") == 2 and [row["name"] for row in items] == ["s[3]"],
        str(payload),
    )


def test_teleport_bounds() -> None:
    adapter = fake_mv()
    calls: list[str] = []
    adapter.js_json = lambda expr: {"inside": False, "width": 10, "height": 10}
    adapter.js = lambda expr: calls.append(expr)

    result = adapter._player_teleport({"map": 1, "x": 99, "y": 5})
    check("out-of-bounds teleport is refused", result["ok"] is False, result["detail"])
    check("out-of-bounds teleport sends nothing", not calls)

    calls.clear()
    adapter.js_json = lambda expr: {
        "inside": True, "blocked": False, "occupied": 0, "width": 10, "height": 10,
    }
    result = adapter._player_teleport({"map": 1, "x": 3, "y": 4})
    check("valid teleport sends reserveTransfer", result["ok"] and bool(calls))


def test_save_formats_and_memory_boundaries() -> None:
    adapter = fake_mv()
    data = {"party": {"_gold": 7}, "variables": {"_data": [0, 42]}}
    with tempfile.TemporaryDirectory(prefix="kaze-save-test-") as temporary:
        path = os.path.join(temporary, "synthetic.rpgsave")
        original = mvmz_adapter.lzstring.compress_to_base64(jsonlib.dumps(data)).encode("ascii")
        with open(path, "wb") as handle:
            handle.write(original)
        _decoded, encoding = adapter._decode_save_format(path)
        data["party"]["_gold"] = 99
        adapter._encode_save(path, data)
        with open(path, "rb") as handle:
            rewritten = handle.read()
        try:
            decoded = jsonlib.loads(mvmz_adapter.lzstring.decompress_base64(rewritten.decode("ascii")))
        except Exception:
            decoded = {}
        check(
            "MV base64 save keeps base64 encoding after patch",
            encoding == "lz-base64" and decoded == data,
            f"encoding={encoding} prefix={rewritten[:12]!r}",
        )
        with open(path + ".kaze-backup", "rb") as handle:
            check("save patch preserves the original backup bytes", handle.read() == original)

        binary = mvmz_adapter.lzstring.compress_to_uint8array(jsonlib.dumps(data))
        with open(path, "wb") as handle:
            handle.write(binary)
        adapter._encode_save(path, data)
        with open(path, "rb") as handle:
            rewritten_binary = handle.read()
        try:
            decoded_binary = jsonlib.loads(mvmz_adapter.lzstring.decompress_uint8array(rewritten_binary))
        except Exception:
            decoded_binary = {}
        check("MZ uint8array save keeps binary encoding after patch", decoded_binary == data)

    memory = object.__new__(memory_adapter.MemoryAdapter)
    needle = b"\x2a\x00\x00\x00"
    payload = b"abc" + needle + b"xyz"
    memory._regions = lambda: [(0x1000, len(payload))]
    memory._read = lambda address, size: payload[address - 0x1000:address - 0x1000 + size]
    previous_chunk = memory_adapter.CHUNK
    memory_adapter.CHUNK = 4
    try:
        hits = memory._scan(42, "int")
        check("value scan finds an int32 across CHUNK boundaries", hits == [0x1003], str(hits))
        wildcard_hits = memory._aob_scan("?? 2A ??")
        check("AOB wildcard-first pattern works across CHUNK boundaries", wildcard_hits == [0x1002], str(wildcard_hits))
        check("AOB all-wildcard pattern is rejected without TypeError", memory._aob_scan("?? ??") == [])
    finally:
        memory_adapter.CHUNK = previous_chunk


def test_backup_restore_safety() -> None:
    import zipfile

    with tempfile.TemporaryDirectory(prefix="kaze-zip-test-") as temporary:
        target = os.path.join(temporary, "saves")
        os.makedirs(target)
        inside = os.path.join(target, "1-1-LT1.save")
        with open(inside, "wb") as handle:
            handle.write(b"ok")

        good = os.path.join(temporary, "renpy-good.zip")
        with zipfile.ZipFile(good, "w") as archive:
            archive.writestr("1-1-LT1.save", b"restored")
            archive.writestr("sub/nested.save", b"nested")
        with zipfile.ZipFile(good) as archive:
            count = kz_wire.safe_extract(archive, target)
        with open(inside, "rb") as handle:
            restored = handle.read()
        check(
            "safe extract restores entries inside the target",
            count == 2 and restored == b"restored" and os.path.isfile(os.path.join(target, "sub", "nested.save")),
            f"count={count} restored={restored!r}",
        )

        outside = os.path.join(temporary, "escaped.save")
        evil = os.path.join(temporary, "evil.zip")
        with zipfile.ZipFile(evil, "w") as archive:
            archive.writestr("../escaped.save", b"pwned")
        try:
            with zipfile.ZipFile(evil) as archive:
                kz_wire.safe_extract(archive, target)
            rejected = False
        except ValueError:
            rejected = True
        check(
            "safe extract refuses entries that escape the target",
            rejected and not os.path.exists(outside),
            f"rejected={rejected} escaped_exists={os.path.exists(outside)}",
        )

        absolute = os.path.join(temporary, "absolute.zip")
        with zipfile.ZipFile(absolute, "w") as archive:
            archive.writestr(os.path.join(temporary, "abs.save").replace("\\", "/"), b"pwned")
        try:
            with zipfile.ZipFile(absolute) as archive:
                kz_wire.safe_extract(archive, target)
            absolute_rejected = False
        except ValueError:
            absolute_rejected = True
        check("safe extract refuses absolute entry paths", absolute_rejected)

    previous = os.environ.get("APPDATA")
    with tempfile.TemporaryDirectory(prefix="kaze-backup-filter-") as temporary:
        appdata = os.path.join(temporary, "appdata")
        backups = os.path.join(appdata, "Kaze", "backups")
        os.makedirs(backups)
        for name in ("mvmz-20260101-000000.zip", "renpy-20260101-000000.zip"):
            with open(os.path.join(backups, name), "wb") as handle:
                handle.write(b"PK")
        os.environ["APPDATA"] = appdata
        try:
            adapter = renpy_adapter.Adapter(None, demo=True)
            filtered = [
                name for name in os.listdir(backups)
                if name.startswith("renpy-") and name.endswith(".zip")
            ]
            check(
                "renpy restore only considers its own backups",
                filtered == ["renpy-20260101-000000.zip"],
                str(filtered),
            )
            del adapter
        finally:
            if previous is None:
                os.environ.pop("APPDATA", None)
            else:
                os.environ["APPDATA"] = previous


def test_save_patch_rejects_invalid_slots() -> None:
    with tempfile.TemporaryDirectory(prefix="kaze-patch-test-") as temporary:
        path = os.path.join(temporary, "file1.rpgsave")
        data = {"party": {"_gold": 5}, "variables": {"_data": [0, 11, 22, 33]},
                "switches": {"_data": [False]}}
        with open(path, "wb") as handle:
            handle.write(mvmz_adapter.lzstring.compress_to_base64(jsonlib.dumps(data)).encode("ascii"))

        adapter = object.__new__(mvmz_adapter.MvmzAdapter)
        adapter._save_path = lambda name: path
        result = adapter._saves_patch({
            "file": "file1.rpgsave",
            "variables": {"-1": 999, "4": 44, "99999999": 1},
            "switches": {"-2": True},
        })
        after = adapter._decode_save(path)
        variables = after["variables"]["_data"]
        check(
            "save patch keeps original slots when ids are out of range",
            variables[3] == 33 and variables[4] == 44 and 999 not in variables,
            str(variables),
        )
        check("save patch does not allocate oversized slot arrays", len(variables) < 100, str(len(variables)))
        check(
            "save patch reports skipped out-of-range ids",
            result["ok"] and '"skipped": 3' in result["data_json"],
            result["data_json"],
        )


def test_adapter_invoke_never_raises() -> None:
    adapter = object.__new__(memory_adapter.MemoryAdapter)
    adapter.handle = 1
    adapter.candidates = []
    adapter.last_type = "int"
    adapter.last_value = 0
    adapter.pid = 1
    adapter.exe = "fake"
    requests = [
        {"op": "mem.write", "args_json": '{"address":"abc","value":1,"type":"int"}'},
        {"op": "mem.write", "args_json": '{"address":4096,"value":99999999999,"type":"int"}'},
        {"op": "mem.write", "args_json": '{"address":4096,"type":"int"}'},
        {"op": "mem.scan", "args_json": '{"value":"x","type":"int"}'},
    ]
    escaped = []
    for request in requests:
        try:
            result = adapter.handle_invoke(request)
            if result.get("ok") is not False:
                escaped.append(f"{request['args_json']} -> {result}")
        except Exception as exc:
            escaped.append(f"{request['args_json']} raised {type(exc).__name__}")
    check(
        "memory adapter reports malformed invokes instead of crashing",
        not escaped,
        "; ".join(escaped),
    )


def test_renpy_values() -> None:
    adapter = renpy_adapter.Adapter(None, demo=True)
    adapter._vars_set({"name": "flag", "value": "true"})
    check("renpy true decodes to bool", adapter.demo_state["flag"] is True)
    adapter._vars_set({"name": "off", "value": "false"})
    check("renpy false decodes to bool", adapter.demo_state["off"] is False)
    adapter._vars_set({"name": "num", "value": "123"})
    check("renpy number decodes", adapter.demo_state["num"] == 123)
    adapter._vars_set({"name": "text", "value": '"hi"'})
    check("renpy string decodes", adapter.demo_state["text"] == "hi")


def main() -> int:
    test_switch_boolean()
    test_switch_execution()
    test_encounter_cheat()
    test_encounter_execution()
    test_speed_multiplier()
    test_speed_execution()
    test_map_grid()
    test_kind_priority()
    test_var_labels()
    test_var_pagination()
    test_save_formats_and_memory_boundaries()
    test_backup_restore_safety()
    test_save_patch_rejects_invalid_slots()
    test_adapter_invoke_never_raises()
    test_teleport_bounds()
    test_renpy_values()
    print()
    print(f"{len(FAILED)} failure(s)")
    return 1 if FAILED else 0


if __name__ == "__main__":
    raise SystemExit(main())
