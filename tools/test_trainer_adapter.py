"""Offline checks for the trainer adapters (no game, no relay, no UI).

Run:  python tools/test_trainer_adapter.py
"""

from __future__ import annotations

import json as jsonlib
import os
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, os.path.join(ROOT, "adapters", "mvmz"))
sys.path.insert(0, os.path.join(ROOT, "adapters", "renpy"))

import mvmz_adapter
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
    test_teleport_bounds()
    test_renpy_values()
    print()
    print(f"{len(FAILED)} failure(s)")
    return 1 if FAILED else 0


if __name__ == "__main__":
    raise SystemExit(main())
