"""Validate the Forge 36.2.34 controls preset against the saved key inventory."""

from itertools import combinations
import json
from pathlib import Path
import re
import sys

HERE = Path(__file__).resolve().parent
PRESET = HERE.parents[1] / "launcher/controls-preset.txt"
UNBOUND = "key.keyboard.unknown"
IN_GAME = {
    "key.forward", "key.left", "key.back", "key.right", "key.jump", "key.sneak",
    "key.sprint", "key.attack", "key.chat", "key.playerlist", "key.command",
    "key.togglePerspective", "key.smoothCamera",
    "mod.chiselsandbits.keys.key.undo", "mod.chiselsandbits.keys.key.redo",
    "mod.chiselsandbits.keys.key.measuring-tape.reset",
}
FANCYMENU_BUILT_IN = {"Reload Menu | CTRL + ALT + ", "Toggle Customization Overlay | CTRL + ALT + "}
MEKANISM_GUI = {"key.mekanism.details", "key.mekanism.description"}
MODIFIER_BASES = {"SHIFT": "shift", "CONTROL": "control", "ALT": "alt"}
RESERVED = {"key.keyboard." + key for key in ("escape", "f1", "f3", "f4")}
VALID = re.compile(
    r"key\.(?:mouse\.(?:left|right|middle|[4-8])|keyboard\.(?:[a-z0-9]|f(?:[1-9]|1[0-2])|"
    r"keypad\.(?:[0-9]|add|subtract|multiply|divide|decimal|enter|equal)|"
    r"unknown|space|tab|backspace|escape|insert|delete|home|end|page\.up|page\.down|"
    r"left|right|up|down|caps\.lock|scroll\.lock|pause|grave\.accent|apostrophe|"
    r"minus|equal|left\.bracket|right\.bracket|semicolon|comma|period|slash|backslash|"
    r"(?:left|right)\.(?:shift|control|alt)))(?::(?:SHIFT|CONTROL|ALT))?"
)


def context(name):
    if name.startswith("key.jei.") or name in MEKANISM_GUI or name in {
        "key.refinedstorage.focusSearchBar", "key.refinedstorage.clearGridCraftingMatrix",
    }:
        return "GUI"
    if name in IN_GAME or name.startswith(("key.mekanism.", "key.refinedstorage.open")):
        return "IN_GAME"
    # Including unverified item-specific handlers in the universal context
    # avoids relying on mutually exclusive screens or equipped items.
    return "UNIVERSAL"


def modifier_matches(modifier, key):
    base = MODIFIER_BASES.get(modifier)
    return base and key in {f"key.keyboard.left.{base}", f"key.keyboard.right.{base}"}


def conflict(first, second, mapping):
    a, _, am = mapping[first].partition(":")
    b, _, bm = mapping[second].partition(":")
    if UNBOUND in (a, b):
        return False
    ac, bc = context(first), context(second)
    if ac != bc and "UNIVERSAL" not in (ac, bc):
        return False
    if modifier_matches(am, b) or modifier_matches(bm, a):
        return True
    return a == b and (am == bm or ("IN_GAME" in (ac, bc) or "UNIVERSAL" in (ac, bc)) and not (am and bm))


def layout():
    snapshot = json.loads((HERE / "registered-keys.json").read_bytes())["registered"]
    changes = json.loads((HERE / "layout.json").read_bytes())
    if not changes.keys() <= snapshot.keys():
        raise ValueError(f"Unregistered controls: {sorted(changes.keys() - snapshot.keys())}")
    if any(name.startswith("key.") and name[4:].split(".")[0] in {
        "attack", "use", "forward", "back", "left", "right", "jump", "sneak", "sprint",
        "drop", "inventory", "chat", "command", "playerlist", "pickItem", "swapOffhand",
        "saveToolbarActivator", "loadToolbarActivator", "hotbar", "screenshot", "togglePerspective",
        "fullscreen", "advancements", "socialInteractions", "smoothCamera", "spectatorOutlines",
    } for name in changes):
        raise ValueError("Base Minecraft controls must retain their captured values")
    if {name for name in snapshot if name.strip() != name} != FANCYMENU_BUILT_IN or FANCYMENU_BUILT_IN & changes.keys():
        raise ValueError("Review which mod controls have names the launcher cannot parse")
    result = {name: value for name, value in (snapshot | changes).items() if name not in FANCYMENU_BUILT_IN}
    for name, key in result.items():
        if not VALID.fullmatch(key) or key.partition(":")[0] in RESERVED:
            raise ValueError(f"Invalid or reserved key: {name} = {key}")
        if snapshot[name] != UNBOUND and key == UNBOUND:
            raise ValueError(f"Previously bound control disabled: {name}")
    return result


def render(mapping):
    return ("# All in One | Minecraft 1.16.5 Forge 36.2.34\n" +
            "".join(f"key_{name}:{key}\n" for name, key in mapping.items())).encode()


def main():
    mapping = layout()
    conflicts = [(a, b) for a, b in combinations(mapping, 2) if conflict(a, b, mapping)]
    if conflicts:
        for a, b in conflicts:
            print(f"{a}: {mapping[a]} <> {b}: {mapping[b]}", file=sys.stderr)
        raise SystemExit(1)
    if PRESET.read_bytes() != render(mapping):
        raise SystemExit("Run tools/keybinds/build_preset.py to regenerate the preset")
    print(f"{len(mapping)} launcher-compatible controls, {len(FANCYMENU_BUILT_IN)} FancyMenu chords retained, "
          f"{len(conflicts)} modeled conflicts")


if __name__ == "__main__":
    main()
