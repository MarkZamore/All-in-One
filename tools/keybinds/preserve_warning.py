"""Attach or verify the pack's multiplayer warning policy on a prepared author release."""

import argparse
import json
from pathlib import Path
import subprocess
import sys
from zipfile import ZipFile

from stage_release import OPTIONS, bundle, encoded_json, pin
from check_preset import PRESET

EXPECTED = b"!skipMultiplayerWarning:true\n"


def prepared_manifest(directory):
    path = directory / "pack-manifest.json"
    manifest = json.loads(path.read_bytes())
    if (manifest["packId"], manifest["minecraftVersion"], manifest["loader"]) != (
            "minecraft-14", "1.16.5", {"type": "forge", "version": "36.2.34"}):
        raise ValueError("Review the Minecraft warning option on a changed platform")
    return path, manifest


def verify(directory, manifest):
    files = [row for row in manifest["files"] if row["path"] == OPTIONS]
    assets = [row for row in manifest["assets"] if OPTIONS in row["covers"]]
    if files != [{"path": OPTIONS, **pin(EXPECTED)}] or len(assets) != 1:
        raise ValueError("The prepared manifest is missing the required warning option")
    asset, = assets
    target = directory / "assets" / asset["name"]
    digest = {key: asset[key] for key in ("sizeBytes", "sha256")}
    if asset["kind"] != "zipRoot" or pin(target.read_bytes()) != digest:
        raise ValueError("The warning option archive differs from its manifest")
    with ZipFile(target) as archive:
        if archive.namelist().count(OPTIONS) != 1 or archive.read(OPTIONS) != EXPECTED:
            raise ValueError("The warning option is missing, disabled, or changes another setting")


def apply(directory, manifest_path, manifest, policy=PRESET.parents[1] / OPTIONS):
    data = policy.read_bytes()
    if data != EXPECTED:
        raise ValueError("The pack policy must hold only skipMultiplayerWarning:true")
    matches = [row for row in manifest["assets"] if OPTIONS in row["covers"]]
    if len(matches) > 1:
        raise ValueError("Warning options occur in multiple prepared archives")
    if matches:
        previous, = matches
        old = directory / "assets" / previous["name"]
        if previous["kind"] != "zipRoot" or pin(old.read_bytes()) != {
                key: previous[key] for key in ("sizeBytes", "sha256")}:
            raise ValueError("Unexpected prepared archive for warning options")
        with ZipFile(old) as archive:
            if sorted(archive.namelist()) != sorted(previous["covers"]):
                raise ValueError("Unexpected prepared archive members")
            entries = {name: archive.read(name) for name in archive.namelist()}
        prior_options = entries.pop(OPTIONS)
        for line in prior_options.decode("utf-8-sig").splitlines():
            if line.strip() and not line.lstrip().startswith("#") and (
                    line.strip().removeprefix("!").partition(":")[0].strip() != "skipMultiplayerWarning"):
                raise ValueError("Author launcher options contain another setting; review how to retain it")
        manifest["assets"].remove(previous)
        if entries:
            add_asset(directory, manifest, entries, "author-retained")
    manifest["files"] = [row for row in manifest["files"] if row["path"] != OPTIONS]
    manifest["files"].append({"path": OPTIONS, **pin(data)})
    manifest["files"].sort(key=lambda row: row["path"])
    add_asset(directory, manifest, {OPTIONS: data}, "options-default")
    manifest["assets"].sort(key=lambda row: row["name"])
    if sorted(row["path"] for row in manifest["files"]) != sorted(
            path for row in manifest["assets"] for path in row["covers"]):
        raise ValueError("Prepared archives do not cover all files exactly once")
    verify(directory, manifest)
    pending = manifest_path.with_suffix(".json.pending")
    pending.write_bytes(encoded_json(manifest))
    pending.replace(manifest_path)


def add_asset(directory, manifest, entries, label):
    asset = bundle(entries)
    digest = pin(asset)
    name = f"r-{digest['sha256'][:12]}-{label}.zip"
    (directory / "assets" / name).write_bytes(asset)
    manifest["assets"].append({"name": name, "kind": "zipRoot", "covers": sorted(entries), **digest})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("release", type=Path)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--stage", type=Path, help="verified author game stage")
    parser.add_argument("--launcher-source", type=Path)
    parser.add_argument("--revision")
    parser.add_argument("--include-optional", action="append", default=[])
    args = parser.parse_args()
    if args.stage:
        if args.check or not args.launcher_source or not args.revision:
            parser.error("--stage requires --launcher-source and --revision, without --check")
        source = PRESET.parents[1]
        upstream = args.launcher_source / "tools/PackData/CurseForge/stage_release.py"
        command = [sys.executable, "-B", str(upstream), "--pack-source", str(source),
                   "--stage", str(args.stage), "--output", str(args.release),
                   "--revision", args.revision, "--id", "minecraft-14"]
        for optional in args.include_optional:
            command.extend(("--include-optional", optional))
        subprocess.run(command, check=True)
    path, manifest = prepared_manifest(args.release)
    if args.check:
        verify(args.release, manifest)
    else:
        apply(args.release, path, manifest)
    print("Prepared release holds only skipMultiplayerWarning:true")
