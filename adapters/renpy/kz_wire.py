"""Minimal protobuf wire codec for the Kaze envelope.

No third-party dependency: Ren'Py ships its own Python and would not have
`google.protobuf`, so the adapter encodes/decodes the small message subset by
hand. Wire-compatible with `proto/kaze.proto`.
"""

from __future__ import annotations

WT_VARINT = 0
WT_I64 = 1
WT_LEN = 2
WT_I32 = 5

                              
HELLO = 10
WELCOME = 11
ACK = 12
ERROR = 13
GAME = 20
ATTACH = 21
ATTACH_RESULT = 22
TEXT_BATCH = 30
TEXT_PUSH = 31
CONTROL = 40
INVOKE_REQUEST = 41
INVOKE_RESULT = 42
PING = 50
PONG = 51

BODY_NAMES = {
    HELLO: "hello",
    WELCOME: "welcome",
    ACK: "ack",
    ERROR: "error",
    GAME: "game",
    ATTACH: "attach",
    ATTACH_RESULT: "attach_result",
    TEXT_BATCH: "text_batch",
    TEXT_PUSH: "text_push",
    CONTROL: "control",
    INVOKE_REQUEST: "invoke_request",
    INVOKE_RESULT: "invoke_result",
    PING: "ping",
    PONG: "pong",
}

BODY_NUMBERS = {name: number for number, name in BODY_NAMES.items()}

                                                   
FRAME_TYPE = {HELLO: 1, WELCOME: 2, ACK: 3, ERROR: 4, GAME: 5, ATTACH: 6,
              ATTACH_RESULT: 7, TEXT_BATCH: 8, TEXT_PUSH: 9, CONTROL: 10,
              PING: 11, PONG: 12, INVOKE_REQUEST: 13, INVOKE_RESULT: 14}


                                                                           

def _varint(value: int) -> bytes:
    if value < 0:
        value += 1 << 64
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def _tag(field: int, wire: int) -> bytes:
    return _varint((field << 3) | wire)


def uint_field(field: int, value: int) -> bytes:
    if value == 0:
        return b""
    return _tag(field, WT_VARINT) + _varint(value)


def bool_field(field: int, value: bool) -> bytes:
    if not value:
        return b""
    return _tag(field, WT_VARINT) + b"\x01"


def bytes_field(field: int, payload: bytes) -> bytes:
    return _tag(field, WT_LEN) + _varint(len(payload)) + payload


def string_field(field: int, value: str) -> bytes:
    if not value:
        return b""
    return bytes_field(field, value.encode("utf-8"))


def message_field(field: int, payload: bytes) -> bytes:
    return bytes_field(field, payload)


def envelope(msg_id: int, body: int, payload: bytes) -> bytes:
    return uint_field(1, msg_id) + message_field(body, payload)


                                                                           

def _read_varint(buf: bytes, index: int) -> tuple[int, int]:
    shift = 0
    value = 0
    while True:
        byte = buf[index]
        index += 1
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return value, index
        shift += 7
        if shift > 63:
            raise ValueError("varint too long")


def parse(buf: bytes) -> dict[int, list]:
    fields: dict[int, list] = {}
    index = 0
    size = len(buf)
    while index < size:
        key, index = _read_varint(buf, index)
        field, wire = key >> 3, key & 7
        value: int | bytes
        if wire == WT_VARINT:
            value, index = _read_varint(buf, index)
        elif wire == WT_LEN:
            length, index = _read_varint(buf, index)
            value = buf[index:index + length]
            index += length
        elif wire == WT_I64:
            value = buf[index:index + 8]
            index += 8
        elif wire == WT_I32:
            value = buf[index:index + 4]
            index += 4
        else:
            raise ValueError(f"unsupported wire type {wire}")
        fields.setdefault(field, []).append(value)
    return fields


def get_uint(fields: dict, field: int, default: int = 0) -> int:
    values = fields.get(field)
    return values[0] if values else default


def get_bool(fields: dict, field: int, default: bool = False) -> bool:
    values = fields.get(field)
    return bool(values[0]) if values else default


def get_str(fields: dict, field: int, default: str = "") -> str:
    values = fields.get(field)
    return values[0].decode("utf-8", "replace") if values else default


def get_msg(fields: dict, field: int) -> bytes:
    values = fields.get(field)
    return values[0] if values else b""


def get_all_str(fields: dict, field: int) -> list[str]:
    return [value.decode("utf-8", "replace") for value in fields.get(field, [])]


def split_envelope(payload: bytes) -> tuple[int, str | None, bytes]:
    fields = parse(payload)
    msg_id = get_uint(fields, 1)
    for number, name in BODY_NAMES.items():
        if number in fields:
            return msg_id, name, get_msg(fields, number)
    return msg_id, None, b""


                                                                         

def hello(proto: int, role: int, pid: int, token: str, nonce: str, name: str) -> bytes:
    return (
        uint_field(1, proto)
        + uint_field(2, role)
        + uint_field(3, pid)
        + string_field(4, token)
        + string_field(5, nonce)
        + string_field(6, name)
    )


def text_unit(key: str, source: str, speaker: str = "", scene: str = "", context: str = "") -> bytes:
    return (
        string_field(1, key)
        + string_field(2, source)
        + string_field(3, speaker)
        + string_field(4, scene)
        + string_field(5, context)
    )


def text_batch(adapter: str, units: list[bytes]) -> bytes:
    payload = string_field(1, adapter)
    for unit in units:
        payload += message_field(2, unit)
    return payload


def pong(client_time_ms: int, server_time_ms: int) -> bytes:
    return uint_field(1, client_time_ms) + uint_field(2, server_time_ms)


def parse_welcome(payload: bytes) -> dict:
    fields = parse(payload)
    return {
        "proto": get_uint(fields, 1),
        "server_time_ms": get_uint(fields, 2),
        "caps": get_all_str(fields, 3),
        "session": get_str(fields, 4),
        "relay_version": get_str(fields, 5),
    }


def parse_attach(payload: bytes) -> dict:
    fields = parse(payload)
    return {"adapter": get_str(fields, 1), "game_id": get_str(fields, 2)}


def parse_attach_result(payload: bytes) -> dict:
    fields = parse(payload)
    return {
        "ok": get_bool(fields, 1),
        "detail": get_str(fields, 2),
        "engine": get_str(fields, 3),
        "took_ms": get_uint(fields, 4),
    }


def parse_ping(payload: bytes) -> dict:
    fields = parse(payload)
    return {"client_time_ms": get_uint(fields, 1)}


def parse_text_push(payload: bytes) -> dict:
    fields = parse(payload)
    units = []
    for raw in fields.get(2, []):
        unit = parse(raw)
        units.append(
            {
                "key": get_str(unit, 1),
                "source": get_str(unit, 2),
                "speaker": get_str(unit, 3),
                "scene": get_str(unit, 4),
                "context": get_str(unit, 5),
                "target": get_str(unit, 6),
            }
        )
    return {"adapter": get_str(fields, 1), "units": units}


def parse_error(payload: bytes) -> dict:
    fields = parse(payload)
    return {"code": get_uint(fields, 1), "message": get_str(fields, 2)}


                                                                         

def invoke_request(adapter: str, op: str, args_json: str = "{}") -> bytes:
    return string_field(1, adapter) + string_field(2, op) + string_field(3, args_json)


def invoke_result(ok: bool, detail: str = "", data_json: str = "") -> bytes:
    return bool_field(1, ok) + string_field(2, detail) + string_field(3, data_json)


def parse_invoke_request(payload: bytes) -> dict:
    fields = parse(payload)
    return {
        "adapter": get_str(fields, 1),
        "op": get_str(fields, 2),
        "args_json": get_str(fields, 3) or "{}",
    }


def parse_invoke_result(payload: bytes) -> dict:
    fields = parse(payload)
    return {
        "ok": get_bool(fields, 1),
        "detail": get_str(fields, 2),
        "data_json": get_str(fields, 3),
    }


def safe_extract(archive, target: str) -> int:
    """Extract a backup archive, refusing entries that escape ``target``.

    ``ZipFile.extractall`` happily writes ``../`` members outside the target
    directory, so a malformed or hand-edited backup could overwrite files
    anywhere the user can write. Only regular files under ``target`` are kept.
    """
    import os

    root = os.path.realpath(target)
    prefix = root + os.sep
    count = 0
    for member in archive.infolist():
        name = member.filename.replace("\\", "/")
        if not name or name.endswith("/"):
            continue
        destination = os.path.realpath(os.path.join(root, name))
        if destination != root and not destination.startswith(prefix):
            raise ValueError(f"存档包含越界路径: {member.filename}")
        parent = os.path.dirname(destination)
        if parent:
            os.makedirs(parent, exist_ok=True)
        with archive.open(member) as source, open(destination, "wb") as handle:
            handle.write(source.read())
        count += 1
    return count
