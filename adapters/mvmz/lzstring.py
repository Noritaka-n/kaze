"""Minimal LZString codec (uint8array + base64 flavours) in pure Python.

RPG Maker MZ stores saves as LZString-compressed bytes; MV uses the base64
flavour. Some games (MTool-patched) remove the global LZString from the JS
context, so the adapter cannot borrow the engine's implementation.
"""

from __future__ import annotations

KEY_STR_BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="


def _decompress(length: int, reset_value: int, get_next_value) -> str:
    dictionary: dict[int, str] = {}
    enlarge_in = 4
    dict_size = 4
    num_bits = 3
    entry = ""
    result: list[str] = []
    data = {"val": get_next_value(0), "position": reset_value, "index": 1}

    def read_bits(count: int) -> int:
        bits = 0
        maxpower = 1 << count
        power = 1
        while power != maxpower:
            resb = data["val"] & data["position"]
            data["position"] >>= 1
            if data["position"] == 0:
                data["position"] = reset_value
                data["val"] = get_next_value(data["index"])
                data["index"] += 1
            if resb > 0:
                bits |= power
            power <<= 1
        return bits

    next_code = read_bits(2)
    if next_code == 0:
        character = chr(read_bits(8))
    elif next_code == 1:
        character = chr(read_bits(16))
    elif next_code == 2:
        return ""
    else:
        raise ValueError("bad LZString header")

    dictionary[3] = character
    w = character
    result.append(character)

    while True:
        if data["index"] > length:
            return ""
        code = read_bits(num_bits)

        if code == 0:
            dictionary[dict_size] = chr(read_bits(8))
            dict_size += 1
            code = dict_size - 1
            enlarge_in -= 1
        elif code == 1:
            dictionary[dict_size] = chr(read_bits(16))
            dict_size += 1
            code = dict_size - 1
            enlarge_in -= 1
        elif code == 2:
            return "".join(result)

        if enlarge_in == 0:
            enlarge_in = 1 << num_bits
            num_bits += 1

        if code in dictionary:
            entry = dictionary[code]
        elif code == dict_size:
            entry = w + w[0]
        else:
            raise ValueError("bad LZString stream")

        result.append(entry)
        dictionary[dict_size] = w + entry[0]
        dict_size += 1
        enlarge_in -= 1
        w = entry

        if enlarge_in == 0:
            enlarge_in = 1 << num_bits
            num_bits += 1


def _compress(uncompressed: str, bits_per_char: int, get_char_from_int) -> str:
    """Faithful port of lz-string's _compress (LSB-first bit order)."""
    if not uncompressed:
        return ""

    out: list[str] = []
    state = {"val": 0, "pos": 0}

    def push(bit: int) -> None:
        state["val"] = (state["val"] << 1) | (bit & 1)
        if state["pos"] == bits_per_char - 1:
            out.append(get_char_from_int(state["val"]))
            state["val"] = 0
            state["pos"] = 0
        else:
            state["pos"] += 1

    def push_value(value: int, count: int) -> None:
        for _ in range(count):
            push(value & 1)
            value >>= 1

    dictionary: dict[str, int] = {}
    to_create: dict[str, bool] = {}
    w = ""
    enlarge_in = 2
    dict_size = 3
    num_bits = 2

    for character in uncompressed:
        if character not in dictionary:
            dictionary[character] = dict_size
            dict_size += 1
            to_create[character] = True

        wc = w + character
        if wc in dictionary:
            w = wc
            continue

        if w in to_create:
            code = ord(w[0])
            if code < 256:
                push_value(0, num_bits)
                push_value(code, 8)
            else:
                push_value(1, num_bits)
                push_value(code, 16)
            enlarge_in -= 1
            if enlarge_in == 0:
                enlarge_in = 1 << num_bits
                num_bits += 1
            del to_create[w]
        else:
            push_value(dictionary[w], num_bits)

        enlarge_in -= 1
        if enlarge_in == 0:
            enlarge_in = 1 << num_bits
            num_bits += 1

        dictionary[wc] = dict_size
        dict_size += 1
        w = character

    if w:
        if w in to_create:
            code = ord(w[0])
            if code < 256:
                push_value(0, num_bits)
                push_value(code, 8)
            else:
                push_value(1, num_bits)
                push_value(code, 16)
            enlarge_in -= 1
            if enlarge_in == 0:
                enlarge_in = 1 << num_bits
                num_bits += 1
            del to_create[w]
        else:
            push_value(dictionary[w], num_bits)
        enlarge_in -= 1
        if enlarge_in == 0:
            enlarge_in = 1 << num_bits
            num_bits += 1

    push_value(2, num_bits)                 

    while True:
        state["val"] <<= 1
        if state["pos"] == bits_per_char - 1:
            out.append(get_char_from_int(state["val"]))
            break
        state["pos"] += 1

    return "".join(out)

def decompress_uint8array(data: bytes) -> str:
    words = []
    for index in range(0, len(data) - 1, 2):
        words.append((data[index] << 8) | data[index + 1])
    return _decompress(len(words), 32768, lambda i: words[i] if i < len(words) else 0)


def compress_to_uint8array(text: str) -> bytes:
    packed = _compress(text, 16, lambda code: chr(code))
    out = bytearray()
    for character in packed:
        value = ord(character)
        out.append((value >> 8) & 0xFF)
        out.append(value & 0xFF)
    return bytes(out)


def decompress_base64(text: str) -> str:
    if not text:
        return ""
    values = [KEY_STR_BASE64.index(character) for character in text]
    return _decompress(len(values), 32, lambda i: values[i] if i < len(values) else 0)


def compress_to_base64(text: str) -> str:
    return _compress(text, 6, lambda code: KEY_STR_BASE64[code])
