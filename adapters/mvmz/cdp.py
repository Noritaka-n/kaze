"""Minimal WebSocket + CDP client, no third-party dependencies.

Used to talk to an RPG Maker MV/MZ game launched with
`--remote-debugging-port=9222`.
"""

import base64
import json
import os
import socket
import struct
import time
import urllib.request


class WebSocket:
    def __init__(self, url: str, timeout: float = 10.0) -> None:
        if not url.startswith("ws://"):
            raise ValueError(f"unsupported websocket url: {url}")
        rest = url[len("ws://"):]
        hostport, _, path = rest.partition("/")
        host, _, port = hostport.partition(":")
        self.sock = socket.create_connection((host, int(port or 80)), timeout=min(timeout, 3.0))
        self.sock.settimeout(timeout)

        key = base64.b64encode(os.urandom(16)).decode()
        request = (
            f"GET /{path} HTTP/1.1\r\n"
            f"Host: {hostport}\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            "Sec-WebSocket-Version: 13\r\n\r\n"
        )
        self.sock.sendall(request.encode())
        self._read_handshake()

    def _recv_exact(self, count: int) -> bytes:
        chunks = []
        remaining = count
        while remaining > 0:
            chunk = self.sock.recv(remaining)
            if not chunk:
                raise ConnectionError("websocket closed")
            chunks.append(chunk)
            remaining -= len(chunk)
        return b"".join(chunks)

    def _read_handshake(self) -> None:
        data = b""
        while b"\r\n\r\n" not in data:
            data += self.sock.recv(1)
        status = data.split(b"\r\n", 1)[0]
        if b"101" not in status:
            raise ConnectionError(f"websocket handshake failed: {status!r}")

    def send_text(self, text: str) -> None:
        payload = text.encode()
        header = bytearray([0x81])
        length = len(payload)
        if length < 126:
            header.append(0x80 | length)
        elif length < (1 << 16):
            header.append(0x80 | 126)
            header += struct.pack("!H", length)
        else:
            header.append(0x80 | 127)
            header += struct.pack("!Q", length)

        mask = os.urandom(4)
        header += mask
        masked = bytes(byte ^ mask[i % 4] for i, byte in enumerate(payload))
        self.sock.sendall(bytes(header) + masked)

    def recv_text(self, timeout: float | None = None) -> str:
        if timeout is not None:
            self.sock.settimeout(timeout)
        while True:
            header = self._recv_exact(2)
            opcode = header[0] & 0x0F
            masked = bool(header[1] & 0x80)
            length = header[1] & 0x7F
            if length == 126:
                length = struct.unpack("!H", self._recv_exact(2))[0]
            elif length == 127:
                length = struct.unpack("!Q", self._recv_exact(8))[0]

            mask = self._recv_exact(4) if masked else None
            payload = self._recv_exact(length)
            if mask:
                payload = bytes(byte ^ mask[i % 4] for i, byte in enumerate(payload))

            if opcode == 0x8:
                raise ConnectionError("websocket closed by peer")
            if opcode == 0x9:
                self._send_pong(payload)
                continue
            if opcode == 0xA:
                continue
            return payload.decode("utf-8", "replace")

    def _send_pong(self, payload: bytes) -> None:
        header = bytearray([0x8A])
        header.append(0x80 | len(payload))
        mask = os.urandom(4)
        header += mask
        masked = bytes(byte ^ mask[i % 4] for i, byte in enumerate(payload))
        self.sock.sendall(bytes(header) + masked)

    def close(self) -> None:
        try:
            self.sock.close()
        except Exception:
            pass


class CDP:
    """Chrome DevTools Protocol client: just Runtime.evaluate."""

    def __init__(self, port: int = 9222) -> None:
        self.port = port
        self.ws: WebSocket | None = None
        self.seq = 0

    def connect(self) -> None:
        listing = json.load(
            urllib.request.urlopen(f"http://127.0.0.1:{self.port}/json", timeout=1.5)
        )
        candidates = [
            item for item in listing
            if item.get("type") == "page" and item.get("webSocketDebuggerUrl")
        ]
        if not candidates:
            raise ConnectionError("no debuggable page found")
        target = candidates[0]
        self.ws = WebSocket(target["webSocketDebuggerUrl"])
                       
        self.evaluate("1+1")

    def evaluate(self, expression: str, timeout: float = 20.0):
        if self.ws is None:
            raise ConnectionError("not connected")
        self.seq += 1
        request_id = self.seq
        self.ws.send_text(json.dumps({
            "id": request_id,
            "method": "Runtime.evaluate",
            "params": {
                "expression": expression,
                "returnByValue": True,
                "awaitPromise": False,
            },
        }))

        deadline = time.time() + timeout
        while time.time() < deadline:
            message = json.loads(self.ws.recv_text(timeout=max(1.0, deadline - time.time())))
            if message.get("id") != request_id:
                continue
            if "error" in message:
                raise RuntimeError(str(message["error"])[:200])
            result = message.get("result", {})
            if result.get("exceptionDetails"):
                raise RuntimeError(str(result["exceptionDetails"])[:200])
            return result.get("result", {}).get("value")
        raise TimeoutError("CDP evaluate timed out")

    def close(self) -> None:
        if self.ws:
            self.ws.close()
            self.ws = None
