"""KZ1 client over a Win32 named pipe, with a dependency-free protobuf codec.

Reads are polled with PeekNamedPipe before calling ReadFile: the pipe handle is
synchronous, and a pending read would block writes from other threads.
"""

from __future__ import annotations

import ctypes
import os
import struct
import threading
import time
from ctypes import wintypes

import kz_wire

MAGIC = 0x00315A4B
VERSION = 1
HEADER = struct.Struct("<I B B H I Q")
MAX_PAYLOAD = 1 << 20

GENERIC_READ = 0x80000000
GENERIC_WRITE = 0x40000000
OPEN_EXISTING = 3
INVALID_HANDLE_VALUE = ctypes.c_void_p(-1).value

kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)

kernel32.CreateFileW.argtypes = [
    wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD, wintypes.LPVOID,
    wintypes.DWORD, wintypes.DWORD, wintypes.HANDLE,
]
kernel32.CreateFileW.restype = wintypes.HANDLE
kernel32.ReadFile.argtypes = [wintypes.HANDLE, wintypes.LPVOID, wintypes.DWORD,
                              ctypes.POINTER(wintypes.DWORD), wintypes.LPVOID]
kernel32.ReadFile.restype = wintypes.BOOL
kernel32.WriteFile.argtypes = [wintypes.HANDLE, wintypes.LPCVOID, wintypes.DWORD,
                               ctypes.POINTER(wintypes.DWORD), wintypes.LPVOID]
kernel32.WriteFile.restype = wintypes.BOOL
kernel32.PeekNamedPipe.argtypes = [wintypes.HANDLE, wintypes.LPVOID, wintypes.DWORD,
                                   ctypes.POINTER(wintypes.DWORD),
                                   ctypes.POINTER(wintypes.DWORD),
                                   ctypes.POINTER(wintypes.DWORD)]
kernel32.PeekNamedPipe.restype = wintypes.BOOL
kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
kernel32.CloseHandle.restype = wintypes.BOOL


class KazeClient:
    def __init__(self, pipe_name: str, token: str, role: int, name: str = "") -> None:
        self._token = token
        self._role = role
        self._name = name
        self._seq = 0
        self._write_lock = threading.Lock()
        self._handle = None

        path = rf"\\.\pipe\{pipe_name}"
        for _ in range(100):
            handle = kernel32.CreateFileW(
                path, GENERIC_READ | GENERIC_WRITE, 0, None, OPEN_EXISTING, 0, None
            )
            if handle and handle != INVALID_HANDLE_VALUE:
                self._handle = handle
                return
            time.sleep(0.02)
        raise RuntimeError(f"cannot open pipe {path}: {ctypes.get_last_error()}")

    def _available(self) -> int:
        available = wintypes.DWORD(0)
        ok = kernel32.PeekNamedPipe(self._handle, None, 0, None,
                                    ctypes.byref(available), None)
        if not ok:
            error = ctypes.get_last_error()
            raise ConnectionError(f"pipe peek failed: {error}")
        return available.value

    def _read_exact(self, length: int, deadline: float | None = None) -> bytes:
        buffer = ctypes.create_string_buffer(length)
        offset = 0
        while offset < length:
            remaining_time = None if deadline is None else deadline - time.monotonic()
            if remaining_time is not None and remaining_time <= 0:
                raise TimeoutError("timed out during frame read")
            if deadline is None:
                read_size = length - offset
            else:
                assert remaining_time is not None
                ready = self._available()
                if not ready:
                    time.sleep(min(0.008, max(0.0, remaining_time)))
                    continue
                read_size = min(ready, length - offset)
            read = wintypes.DWORD(0)
            ok = kernel32.ReadFile(
                self._handle, ctypes.byref(buffer, offset),
                read_size, ctypes.byref(read), None,
            )
            if not ok or read.value == 0:
                error = ctypes.get_last_error()
                raise ConnectionError(f"pipe read failed: {error}")
            offset += read.value
        return buffer.raw[:length]

    def _write(self, data: bytes) -> None:
        offset = 0
        while offset < len(data):
            chunk = data[offset:]
            written = wintypes.DWORD(0)
            ok = kernel32.WriteFile(
                self._handle, chunk, len(chunk), ctypes.byref(written), None
            )
            if not ok or written.value == 0:
                raise ConnectionError(f"write failed: {ctypes.get_last_error()}")
            offset += written.value

    def send(self, body: int, payload: bytes, msg_id: int | None = None) -> int:
        # Sequence allocation, envelope construction and every WriteFile for a
        # frame share one lock; batch flushes and pong/reply threads cannot mix.
        with self._write_lock:
            if msg_id is None:
                self._seq += 1
                msg_id = self._seq
            else:
                self._seq = max(self._seq, msg_id)
            envelope = kz_wire.envelope(msg_id, body, payload)
            if len(envelope) > MAX_PAYLOAD:
                raise ValueError("payload too large")
            frame = HEADER.pack(MAGIC, VERSION, kz_wire.FRAME_TYPE.get(body, 0), 0,
                                len(envelope), msg_id) + envelope
            self._write(frame)
        return msg_id

    def recv(self, timeout: float = 0.0):
        """Return one frame, or None only when no header arrives before timeout.

        Header and payload share one monotonic deadline. A partial-frame timeout
        is fatal to this stream, rather than a false empty poll that would leave
        the next receive starting in the middle of a payload.
        """
        deadline = time.monotonic() + timeout if timeout else None
        while self._available() < HEADER.size:
            if deadline is not None and time.monotonic() >= deadline:
                return None
            delay = 0.008 if deadline is None else min(
                0.008, max(0.0, deadline - time.monotonic())
            )
            time.sleep(delay)

        magic, version, _type, _flags, length, _msg_id = HEADER.unpack(
            self._read_exact(HEADER.size, deadline)
        )
        if magic != MAGIC:
            raise ValueError(f"bad magic 0x{magic:08X}")
        if version != VERSION:
            raise ValueError(f"unsupported version {version}")
        if length > MAX_PAYLOAD:
            raise ValueError("payload too large")

        return kz_wire.split_envelope(self._read_exact(length, deadline))

    def handshake(self) -> dict:
        try:
            nonce = f"py-{int(time.time() * 1000)}"
            self.send(
                kz_wire.HELLO,
                kz_wire.hello(1, self._role, os.getpid(), self._token, nonce, self._name),
            )

            reply = self.recv(timeout=5.0)
            if reply is None:
                raise TimeoutError("no welcome")
            _msg_id, body, payload = reply
            if body == "error":
                error = kz_wire.parse_error(payload)
                raise RuntimeError(f"relay rejected: {error['code']} {error['message']}")
            if body != "welcome":
                raise RuntimeError(f"unexpected handshake reply: {body}")
            return kz_wire.parse_welcome(payload)
        except Exception:
            self.close()
            raise

    def close(self) -> None:
        handle = self._handle
        self._handle = None
        if handle:
            kernel32.CloseHandle(handle)
