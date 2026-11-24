"""Kaze memory adapter: attach to a native game process and scan/patch values.

Engine-agnostic: works for Wolf RPG, KiriKiri, RGSS, Unity (non-IL2CPP) and any
other Win32 process. Reads only committed, readable pages.

Ops:
  proc.list                      running processes with a visible window
  proc.attach {exe|pid}          open the game process
  mem.scan {value, type}         first scan, returns candidate addresses
  mem.filter {value, type}       narrow the previous candidates
  mem.results                    current candidates with values
  mem.write {address, type, value}
  mem.read {address, type}
  mem.reset
"""

from __future__ import annotations

import argparse
import ctypes
import json
import os
import struct
import sys
import time
from ctypes import wintypes

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "renpy"))

import kz_wire              
from kaze_client import KazeClient              

ROLE_ADAPTER = 2
ADAPTER_NAME = "memory"

PROCESS_QUERY_INFORMATION = 0x0400
PROCESS_VM_READ = 0x0010
PROCESS_VM_WRITE = 0x0020
PROCESS_VM_OPERATION = 0x0008

MEM_COMMIT = 0x1000
PAGE_GUARD = 0x100
PAGE_NOACCESS = 0x01
PAGE_READONLY = 0x02
PAGE_READWRITE = 0x04
PAGE_WRITECOPY = 0x08
PAGE_EXECUTE_READ = 0x20
PAGE_EXECUTE_READWRITE = 0x40
PAGE_EXECUTE_WRITECOPY = 0x80

READABLE = {PAGE_READONLY, PAGE_READWRITE, PAGE_WRITECOPY,
            PAGE_EXECUTE_READ, PAGE_EXECUTE_READWRITE, PAGE_EXECUTE_WRITECOPY}

MAX_REGION = 64 * 1024 * 1024
CHUNK = 4 * 1024 * 1024
MAX_RESULTS = 400

kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
psapi = ctypes.WinDLL("psapi", use_last_error=True)


class MEMORY_BASIC_INFORMATION(ctypes.Structure):
    _fields_ = [
        ("BaseAddress", ctypes.c_void_p),
        ("AllocationBase", ctypes.c_void_p),
        ("AllocationProtect", wintypes.DWORD),
        ("RegionSize", ctypes.c_size_t),
        ("State", wintypes.DWORD),
        ("Protect", wintypes.DWORD),
        ("Type", wintypes.DWORD),
    ]


kernel32.OpenProcess.restype = wintypes.HANDLE
kernel32.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
kernel32.ReadProcessMemory.argtypes = [wintypes.HANDLE, ctypes.c_void_p, ctypes.c_void_p,
                                       ctypes.c_size_t, ctypes.POINTER(ctypes.c_size_t)]
kernel32.WriteProcessMemory.argtypes = [wintypes.HANDLE, ctypes.c_void_p, ctypes.c_void_p,
                                        ctypes.c_size_t, ctypes.POINTER(ctypes.c_size_t)]
kernel32.VirtualQueryEx.argtypes = [wintypes.HANDLE, ctypes.c_void_p,
                                    ctypes.POINTER(MEMORY_BASIC_INFORMATION), ctypes.c_size_t]
kernel32.VirtualQueryEx.restype = ctypes.c_size_t
kernel32.QueryFullProcessImageNameW.argtypes = [wintypes.HANDLE, wintypes.DWORD,
                                                ctypes.c_wchar_p, ctypes.POINTER(wintypes.DWORD)]


class MODULEINFO(ctypes.Structure):
    _fields_ = [
        ("lpBaseOfDll", ctypes.c_void_p),
        ("SizeOfImage", wintypes.DWORD),
        ("EntryPoint", ctypes.c_void_p),
    ]


psapi.EnumProcesses.argtypes = [ctypes.POINTER(wintypes.DWORD), wintypes.DWORD,
                                ctypes.POINTER(wintypes.DWORD)]
psapi.EnumProcessModulesEx.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.HMODULE),
                                       wintypes.DWORD, ctypes.POINTER(wintypes.DWORD), wintypes.DWORD]
psapi.GetModuleBaseNameW.argtypes = [wintypes.HANDLE, wintypes.HMODULE, ctypes.c_wchar_p,
                                     wintypes.DWORD]
psapi.GetModuleInformation.argtypes = [wintypes.HANDLE, wintypes.HMODULE,
                                       ctypes.POINTER(MODULEINFO), wintypes.DWORD]


def type_format(type_name: str) -> str:
    return {"int": "i", "int64": "q", "float": "f"}.get(type_name, "i")


def encode(value, type_name: str) -> bytes:
    return struct.pack("<" + type_format(type_name), value)


def decode(raw: bytes, type_name: str):
    return struct.unpack("<" + type_format(type_name), raw)[0]


class MemoryAdapter:
    def __init__(self, client: KazeClient) -> None:
        self.client = client
        self.handle = None
        self.pid = 0
        self.exe = ""
        self.candidates: list[int] = []
        self.last_value = None
        self.last_type = "int"

                                                                         

    def _processes(self) -> list[dict]:
        """Enumerates every process (not just windowed ones)."""
        result: list[dict] = []
        try:
            count = 1024
            while True:
                buffer = (wintypes.DWORD * count)()
                needed = wintypes.DWORD()
                ok = psapi.EnumProcesses(buffer, ctypes.sizeof(buffer), ctypes.byref(needed))
                if not ok:
                    return result
                used = needed.value // ctypes.sizeof(wintypes.DWORD)
                if used < count:
                    break
                count *= 2

            for index in range(used):
                pid = int(buffer[index])
                if pid <= 0:
                    continue
                handle = kernel32.OpenProcess(PROCESS_QUERY_INFORMATION | PROCESS_VM_READ, False, pid)
                if not handle:
                    continue
                try:
                    path_buffer = ctypes.create_unicode_buffer(1024)
                    size = wintypes.DWORD(1024)
                    if kernel32.QueryFullProcessImageNameW(handle, 0, path_buffer, ctypes.byref(size)):
                        path = path_buffer.value
                        result.append({"pid": pid, "exe": os.path.basename(path), "path": path})
                finally:
                    kernel32.CloseHandle(handle)
        except Exception as exc:
            print(f"[memory] process list failed: {exc}", flush=True)
        return result

    def _modules(self) -> list[dict]:
        """Loaded modules with base address and size (for offsets)."""
        if not self.handle:
            return []
        LIST_MODULES_ALL = 0x03
        count = 1024
        result = []
        try:
            while True:
                array = (wintypes.HMODULE * count)()
                needed = wintypes.DWORD()
                ok = psapi.EnumProcessModulesEx(self.handle, array, ctypes.sizeof(array),
                                                ctypes.byref(needed), LIST_MODULES_ALL)
                if not ok:
                    return result
                used = needed.value // ctypes.sizeof(wintypes.HMODULE)
                if used < count:
                    break
                count *= 2

            for index in range(used):
                module = array[index]
                name_buffer = ctypes.create_unicode_buffer(260)
                if not psapi.GetModuleBaseNameW(self.handle, module, name_buffer, 260):
                    continue
                info = MODULEINFO()
                if not psapi.GetModuleInformation(self.handle, module, ctypes.byref(info),
                                                  ctypes.sizeof(info)):
                    continue
                result.append({
                    "name": name_buffer.value,
                    "base": int(info.lpBaseOfDll or 0),
                    "size": int(info.SizeOfImage),
                })
        except Exception as exc:
            print(f"[memory] module list failed: {exc}", flush=True)
        return result

    def _module_range(self, module_name: str):
        wanted = (module_name or "").lower()
        for module in self._modules():
            if module["name"].lower() == wanted or (wanted and wanted in module["name"].lower()):
                return module["base"], module["size"], module["name"]
        return None

    def _aob_scan(self, pattern: str, module_name: str = "", limit: int = 32) -> list[int]:
        """Byte pattern with ?? wildcards, including wildcard-first signatures."""
        tokens = pattern.replace(",", " ").split()
        if not tokens:
            return []
        values: list[int | None] = []
        for token in tokens:
            if token in ("?", "??", "**"):
                values.append(None)
            else:
                value = int(token, 16)
                if not 0 <= value <= 0xFF:
                    raise ValueError(f"AOB byte out of range: {token}")
                values.append(value)
        needle_length = len(values)
        if needle_length < 2:
            return []
        fixed = [index for index, value in enumerate(values) if value is not None]
        if not fixed:
            return []
        anchor = fixed[0]
        anchor_value = values[anchor]
        if anchor_value is None:
            return []
        anchor_byte = bytes([anchor_value])

        if module_name:
            found = self._module_range(module_name)
            if not found:
                return []
            base, size, _ = found
            regions = [(base, size)]
        else:
            regions = self._regions()

        hits: list[int] = []
        seen: set[int] = set()
        chunk_size = max(CHUNK, needle_length)
        step = max(1, chunk_size - needle_length + 1)
        for base, size in regions:
            offset = 0
            while offset < size and len(hits) < limit:
                length = min(chunk_size, size - offset)
                data = self._read(base + offset, length)
                if not data:
                    offset += step
                    continue
                start = 0
                while len(hits) < limit:
                    index = data.find(anchor_byte, start)
                    if index < 0:
                        break
                    start = index + 1
                    candidate = index - anchor
                    if candidate < 0:
                        continue
                    if candidate + needle_length > len(data):
                        break
                    matched = True
                    for shift, expected in enumerate(values):
                        if expected is not None and data[candidate + shift] != expected:
                            matched = False
                            break
                    if matched:
                        hit = base + offset + candidate
                        if hit not in seen:
                            seen.add(hit)
                            hits.append(hit)
                offset += step
        return hits

    def _read_pointer(self, address: int) -> int:
        data = self._read(address, 8)
        if data is None or len(data) != 8:
            return 0
        return struct.unpack("<Q", data)[0]
    def _attach(self, target) -> dict:
        pid = 0
        exe = ""
        if isinstance(target, int) and target > 0:
            pid = target
        else:
            name = str(target or "").lower()
            for item in self._processes():
                if name and (name in item["exe"].lower() or name in item["path"].lower()):
                    pid = item["pid"]
                    exe = item["exe"]
                    break
        if not pid:
            return {"ok": False, "detail": "找不到进程"}

        handle = kernel32.OpenProcess(
            PROCESS_QUERY_INFORMATION | PROCESS_VM_READ | PROCESS_VM_WRITE | PROCESS_VM_OPERATION,
            False, pid,
        )
        if not handle:
            return {"ok": False, "detail": f"OpenProcess 失败：{ctypes.get_last_error()}（可能需要管理员权限）"}

        if self.handle:
            kernel32.CloseHandle(self.handle)
        self.handle = handle
        self.pid = pid
        self.exe = exe or str(pid)
        self.candidates = []
        return {"ok": True, "detail": f"已附加 {self.exe}（pid {pid}）"}

                                                                         

    def _regions(self):
        address = 0
        info = MEMORY_BASIC_INFORMATION()
        max_address = 0x7FFFFFFF0000
        while address < max_address:
            written = kernel32.VirtualQueryEx(self.handle, ctypes.c_void_p(address),
                                              ctypes.byref(info), ctypes.sizeof(info))
            if not written:
                break
            base = info.BaseAddress or 0
            size = info.RegionSize or 0
            address = base + size
            if size == 0:
                continue
            if info.State != MEM_COMMIT:
                continue
            if info.Protect & PAGE_GUARD or info.Protect & PAGE_NOACCESS:
                continue
            if info.Protect not in READABLE:
                continue
            if size > MAX_REGION:
                continue
            yield base, size

    def _read(self, address: int, size: int) -> bytes | None:
        buffer = ctypes.create_string_buffer(size)
        read = ctypes.c_size_t(0)
        ok = kernel32.ReadProcessMemory(self.handle, ctypes.c_void_p(address), buffer, size,
                                        ctypes.byref(read))
        if not ok or read.value == 0:
            return None
        return buffer.raw[: read.value]

    def _scan(self, value, type_name: str) -> list[int]:
        pattern = encode(value, type_name)
        found: list[int] = []
        seen: set[int] = set()
        chunk_size = max(CHUNK, len(pattern))
        step = max(1, chunk_size - len(pattern) + 1)
        for base, size in self._regions():
            offset = 0
            while offset < size and len(found) < MAX_RESULTS:
                length = min(chunk_size, size - offset)
                data = self._read(base + offset, length)
                if not data:
                    offset += step
                    continue
                index = data.find(pattern)
                while index >= 0 and len(found) < MAX_RESULTS:
                    hit = base + offset + index
                    if hit not in seen:
                        seen.add(hit)
                        found.append(hit)
                    index = data.find(pattern, index + 1)
                offset += step
        return found

                                                                         

    def handle_invoke(self, request: dict) -> dict:
        op = request.get("op", "")
        try:
            args = json.loads(request.get("args_json") or "{}")
        except Exception:
            args = {}

        if op == "proc.list":
            items = self._processes()
            return {"ok": True, "detail": f"{len(items)} 个进程",
                    "data_json": json.dumps(items, ensure_ascii=False)}
        if op == "proc.attach":
            return self._attach(args.get("pid") or args.get("exe"))
        if op == "mem.scan":
            if not self.handle:
                return {"ok": False, "detail": "未附加进程"}
            value = args.get("value")
            type_name = str(args.get("type", "int"))
            self.candidates = self._scan(value, type_name)
            self.last_value = value
            self.last_type = type_name
            return {"ok": True, "detail": f"找到 {len(self.candidates)} 个地址",
                    "data_json": json.dumps({"count": len(self.candidates)})}
        if op == "mem.filter":
            if not self.handle:
                return {"ok": False, "detail": "未附加进程"}
            value = args.get("value")
            type_name = str(args.get("type", self.last_type))
            pattern = encode(value, type_name)
            kept = []
            for address in self.candidates:
                data = self._read(address, len(pattern))
                if data == pattern:
                    kept.append(address)
            self.candidates = kept
            self.last_value = value
            self.last_type = type_name
            return {"ok": True, "detail": f"剩余 {len(kept)} 个地址",
                    "data_json": json.dumps({"count": len(kept)})}
        if op == "mem.write":
            if not self.handle:
                return {"ok": False, "detail": "未附加进程"}
            address = int(args.get("address", 0))
            type_name = str(args.get("type", "int"))
            payload = encode(args.get("value"), type_name)
            written = ctypes.c_size_t(0)
            ok = kernel32.WriteProcessMemory(self.handle, ctypes.c_void_p(address), payload,
                                             len(payload), ctypes.byref(written))
            if not ok:
                return {"ok": False, "detail": f"写入失败：{ctypes.get_last_error()}"}
            if written.value != len(payload):
                return {"ok": False, "detail": f"写入不完整：{written.value}/{len(payload)} 字节"}
            return {"ok": True, "detail": f"已写入 0x{address:X}"}
        if op == "mem.read":
            if not self.handle:
                return {"ok": False, "detail": "未附加进程"}
            address = int(args.get("address", 0))
            type_name = str(args.get("type", "int"))
            size = struct.calcsize("<" + type_format(type_name))
            data = self._read(address, size)
            if data is None or len(data) != size:
                return {"ok": False, "detail": "读取失败或不完整"}
            return {"ok": True, "detail": str(decode(data, type_name)),
                    "data_json": json.dumps({"value": decode(data, type_name)})}
        if op == "mem.modules":
            items = self._modules()
            return {"ok": True, "detail": f"{len(items)} 个模块",
                    "data_json": json.dumps(items[:60], ensure_ascii=False)}
        if op == "mem.aob":
            if not self.handle:
                return {"ok": False, "detail": "未附加进程"}
            hits = self._aob_scan(str(args.get("pattern", "")), str(args.get("module", "")))
            self.candidates = hits
            return {"ok": True, "detail": f"命中 {len(hits)} 处",
                    "data_json": json.dumps({"count": len(hits)})}
        if op == "mem.pointer":
            if not self.handle:
                return {"ok": False, "detail": "未附加进程"}
            base = int(args.get("base", 0))
            offsets = [int(x) for x in (args.get("offsets") or [])]
            address = base
            for offset in offsets:
                pointer = self._read_pointer(address)
                if not pointer:
                    return {"ok": False, "detail": f"指针断在 0x{address:X}"}
                address = pointer + offset
            return {"ok": True, "detail": f"0x{address:X}",
                    "data_json": json.dumps({"address": address})}
        if op == "mem.results":
            if not self.handle:
                return {"ok": False, "detail": "未附加进程"}
            rows = []
            size = struct.calcsize("<" + type_format(self.last_type))
            for address in self.candidates[:MAX_RESULTS]:
                data = self._read(address, size)
                if data is not None and len(data) == size:
                    rows.append({"address": address, "hex": f"0x{address:X}",
                                 "value": decode(data, self.last_type)})
            return {"ok": True, "detail": f"{len(rows)} 条",
                    "data_json": json.dumps({"type": self.last_type, "rows": rows})}
        if op == "mem.reset":
            self.candidates = []
            return {"ok": True, "detail": "已清空候选"}
        if op == "engine.info":
            return {"ok": bool(self.handle), "detail": f"memory · {self.exe or '未附加'}",
                    "data_json": json.dumps({"engine": "memory", "pid": self.pid})}
        return {"ok": False, "detail": f"unknown op '{op}'"}

    def run(self) -> None:
        print("[memory] waiting for relay messages", flush=True)
        while True:
            message = self.client.recv(timeout=0.25)
            if not message:
                continue
            msg_id, body, payload = message
            if body == "ping":
                ping = kz_wire.parse_ping(payload)
                self.client.send(kz_wire.PONG,
                                 kz_wire.pong(ping["client_time_ms"], int(time.time() * 1000)))
            elif body == "invoke_request":
                request = kz_wire.parse_invoke_request(payload)
                result = self.handle_invoke(request)
                self.client.send(
                    kz_wire.INVOKE_RESULT,
                    kz_wire.invoke_result(bool(result.get("ok")), result.get("detail", ""),
                                          result.get("data_json", "")),
                    msg_id=msg_id,
                )
                print(f"[memory] invoke {request['op']} ok={result.get('ok')}", flush=True)


def read_session() -> tuple[str, str]:
    base = os.environ.get("LOCALAPPDATA") or os.path.expanduser("~")
    with open(os.path.join(base, "Kaze", "session", "relay.json"), encoding="utf-8") as fh:
        data = json.load(fh)
    return data["pipe"], data["token"]


def main() -> int:
    parser = argparse.ArgumentParser(description="Kaze memory adapter")
    parser.add_argument("--pipe")
    parser.add_argument("--token")
    parser.add_argument("--exe", help="attach on start")
    args = parser.parse_args()

    pipe_name, token = args.pipe, args.token
    if not pipe_name or not token:
        pipe_name, token = read_session()

    client = KazeClient(pipe_name, token, ROLE_ADAPTER, ADAPTER_NAME)
    welcome = client.handshake()
    print(f"[memory] connected session={welcome['session']}", flush=True)

    adapter = MemoryAdapter(client)
    if args.exe:
        print(f"[memory] attach {args.exe}: {adapter._attach(args.exe)}", flush=True)
    try:
        adapter.run()
    except KeyboardInterrupt:
        pass
    finally:
        if adapter.handle:
            kernel32.CloseHandle(adapter.handle)
        client.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
