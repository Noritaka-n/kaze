"""Generate RGSS / Ruby-Marshal fixtures for the shell unit tests.

The fixtures are written by an independent implementation (the `rubymarshal`
Python package), so the Kotlin reader/writer is checked against data produced
by a different code base instead of against itself.

    python tools/make_rgss_fixtures.py

Output: src/shell/src/test/resources/rgss/
  rgss3/Data/*.rvdata2      VX Ace style: UTF-8 strings tagged with :E
  rgss1/Data/Map002.rxdata  XP/VX style: untagged Shift_JIS byte strings
  codec/sample.rvdata2      links, floats, bignums, nested containers
"""

from __future__ import annotations

import os
import sys

from rubymarshal.classes import RubyObject, RubyString, Symbol
from rubymarshal.constants import TYPE_IVAR, TYPE_STRING
from rubymarshal.writer import Writer, writes

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "src", "shell", "src", "test", "resources", "rgss")


class RubyFaithfulWriter(Writer):
    """Ruby puts every string in the object table; rubymarshal skips plain ones.

    Without this the link indices in the fixtures would disagree with what Ruby
    itself emits, and the Kotlin codec could not be checked against them.
    """

    def write(self, obj):
        if isinstance(obj, bytes):
            if self.must_write(obj):
                self.fd.write(TYPE_STRING)
                self.write_long(len(obj))
                self.fd.write(obj)
            return
        super().write(obj)

    def write_string(self, obj):
        if not self.must_write(obj):
            return
        data = obj.encode("utf-8")
        self.fd.write(TYPE_IVAR)
        self.fd.write(TYPE_STRING)
        self.write_long(len(data))
        self.fd.write(data)
        self.write_long(1)
        self.write(Symbol("E"))
        self.write(True)


def dump(value) -> bytes:
    return writes(value, cls=RubyFaithfulWriter)


def obj(cls: str, **attrs):
    return RubyObject(cls, {("@" + key): value for key, value in attrs.items()})


def command(code: int, params: list, indent: int = 0):
    return obj("RPG::EventCommand", code=code, indent=indent, parameters=params)


def page(commands: list):
    return obj(
        "RPG::Event::Page",
        condition=obj("RPG::Event::Page::Condition", switch1_valid=False),
        list=commands,
    )


def write(relative: str, value) -> None:
    path = os.path.join(OUT, relative)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as handle:
        handle.write(dump(value))
    print(f"{relative}: {os.path.getsize(path)} bytes")


def make_map():
    commands = [
        command(101, ["Face", 0, 0, 2]),
        command(401, ["ミナ：おはよう、いい天気ですね。"]),
        command(401, ["今日は何をする？"]),
        command(102, [["買い物に行く", "家に帰る"], 1]),
        command(402, [0, "買い物に行く"]),
        command(402, [1, "家に帰る"]),
        command(405, ["長い説明の一行目"]),
        command(405, ["長い説明の二行目"]),
        command(201, [0, 5, 3, 0, 0]),
    ]
    event = obj("RPG::Event", id=1, name="EV001", x=5, y=3, pages=[page(commands)])
    second = obj(
        "RPG::Event",
        id=2,
        name="EV002",
        x=8,
        y=2,
        pages=[page([command(401, ["ここは村の広場です。"])])],
    )
    return obj(
        "RPG::Map",
        display_name="始まりの村",
        width=20,
        height=15,
        events={1: event, 2: second},
        data=[0] * 24,
    )


def make_common_events():
    event = obj(
        "RPG::CommonEvent",
        id=1,
        name="宿屋",
        trigger=0,
        switch_id=1,
        list=[
            command(401, ["ゆっくり休んでいってね。"]),
            command(355, ["$game_variables[1] = 1"]),
            command(401, ["おやすみなさい。"]),
        ],
    )
    return [None, event]


def make_items():
    return [
        None,
        obj("RPG::Item", id=1, name="ポーション", description="HPを50回復する。", price=50),
        obj("RPG::Item", id=2, name="エーテル", description="MPを30回復する。", price=120),
    ]


def make_actors():
    return [
        None,
        obj(
            "RPG::Actor",
            id=1,
            name="ミナ",
            nickname="ミナ",
            description="村の見習い魔法使い。",
            profile="いつも元気。",
            initial_level=1,
        ),
    ]


def make_system():
    messages = obj(
        "RPG::System::Terms::Messages",
        actor="%s",
        level="レベル%2$s",
        gold="%s%s",
    )
    terms = obj(
        "RPG::System::Terms",
        basic=["レベル", "体力", "魔力", "攻撃力"],
        commands=["戦う", "逃げる", "アイテム"],
        params=["最大HP", "最大MP", "攻撃力"],
        messages=messages,
    )
    return obj(
        "RPG::System",
        game_title="テストゲーム",
        currency_unit="G",
        switches=["スイッチ1", ""],
        variables=["変数1", ""],
        elements=["炎", "氷"],
        skill_types=["魔法", "必殺技"],
        terms=terms,
    )


def make_map_infos():
    return {
        1: obj("RPG::MapInfo", name="始まりの村", expanded=False, order=1),
        2: obj("RPG::MapInfo", name="森の入口", expanded=False, order=2),
    }


def make_sjis_map():
    """Ruby 1.8 style: byte strings without an encoding ivar (Shift_JIS)."""
    def b(text: str) -> bytes:
        return text.encode("cp932")

    commands = [
        command(401, [b("こんにちは、旅の人。")]),
        command(102, [[b("はい"), b("いいえ")], 1]),
    ]
    event = obj("RPG::Event", id=1, name=b("EV001"), x=1, y=1, pages=[page(commands)])
    return obj("RPG::Map", display_name=b("村"), events={1: event}, data=[0] * 4)


def make_ascii_map():
    """Ruby 1.8 English game: untagged ASCII strings, still not writable."""
    commands = [command(401, [b"Hello, traveler."])]
    event = obj("RPG::Event", id=1, name=b"EV001", x=1, y=1, pages=[page(commands)])
    return obj("RPG::Map", display_name=b"Village", events={1: event}, data=[0] * 4)


def make_codec_sample():
    shared = RubyString("同じ台詞")
    nested = {


        "shared": [shared, shared],
        "list": [1, -1, 123, -124, 0, True, False, None],
        "float": 1.5,
        "big": 2**40 + 7,
        "text": "日本語のテキスト",
    }
    return obj(
        "RPG::Map",
        display_name="コーデック",
        blob=RubyString("二回目"),
        events=nested,
        data=[shared],
    )


def main() -> int:
    write("rgss3/Data/Map001.rvdata2", make_map())
    write("rgss3/Data/CommonEvents.rvdata2", make_common_events())
    write("rgss3/Data/Items.rvdata2", make_items())
    write("rgss3/Data/Actors.rvdata2", make_actors())
    write("rgss3/Data/System.rvdata2", make_system())
    write("rgss3/Data/MapInfos.rvdata2", make_map_infos())
    write("rgss1/Data/Map002.rxdata", make_sjis_map())
    write("rgss1/Data/Map003.rxdata", make_ascii_map())
    write("codec/sample.rvdata2", make_codec_sample())
    print("ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
