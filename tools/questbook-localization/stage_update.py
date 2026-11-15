"""Stage a data-only questbook update against the live All in One release."""

import argparse
from datetime import datetime, timezone
from io import BytesIO
import json
from pathlib import Path
import subprocess
from urllib.request import Request, urlopen
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

import build


BASE = "https://github.com/MarkZamore/All-in-One/releases/download/pack-latest/"
MANIFEST = "pack-manifest.json"


def fetch(name, limit):
    request = Request(BASE + name, headers={"Cache-Control": "no-cache"})
    with urlopen(request, timeout=60) as response:
        data = response.read(limit + 1)
    if len(data) > limit:
        raise ValueError(f"Oversized release asset: {name}")
    return data


def unpack(row, members):
    data = fetch(row["name"], row["sizeBytes"])
    if build.pin(data) != {key: row[key] for key in ("sizeBytes", "sha256")}:
        raise ValueError(f"Invalid asset: {row['name']}")
    with ZipFile(BytesIO(data)) as archive:
        if sorted(archive.namelist()) != sorted(members):
            raise ValueError(f"Unexpected members: {row['name']}")
        return {name: archive.read(name) for name in members}


def bundle(entries):
    stream = BytesIO()
    with ZipFile(stream, "w", compression=ZIP_DEFLATED) as archive:
        for name, data in sorted(entries.items()):
            info = ZipInfo(name)
            info.compress_type = ZIP_DEFLATED
            info.create_system = 3
            archive.writestr(info, data)
    return stream.getvalue()


def git(*args):
    return subprocess.check_output(["git", "-C", str(build.SOURCE), *args])


def stage(archive_path, output, expected_base_sha256):
    recipe = build.read_json(build.SOURCE / build.RECIPE)
    checked = build.generate(archive_path)
    for path, data in checked.items():
        if data != git("show", "HEAD:" + path):
            raise ValueError(f"Commit the verified translation bytes before staging: {path}")
    files = {path: data for path, data in checked.items() if path != build.RECIPE}
    with ZipFile(archive_path) as author:
        for row in recipe["patches"]:
            path = row["path"]
            original = author.read("overrides/" + path)
            if build.pin(original) != row["before"]:
                raise ValueError(f"Wrong patch input: {path}")
            for replacement in row["replacements"]:
                old, new = (replacement[key].encode() for key in ("from", "to"))
                if original.count(old) != 1:
                    raise ValueError(f"Ambiguous patch input: {path}")
                original = original.replace(old, new, 1)
            if build.pin(original) != row["after"]:
                raise ValueError(f"Wrong patch output: {path}")
            files[path] = original

    revision = git("rev-parse", "HEAD").decode().strip()
    timestamp = int(git("show", "-s", "--format=%ct", revision))
    old = fetch(MANIFEST + "?questbook=" + revision, 2 * 1024 * 1024)
    if build.pin(old)["sha256"] != expected_base_sha256:
        raise ValueError("Live release changed. Recheck the release base first.")
    manifest = json.loads(old)
    if (manifest["packId"], manifest["minecraftVersion"], manifest["loader"]) != (
        "minecraft-14", "1.16.5", {"type": "forge", "version": "36.2.34"}
    ):
        raise ValueError("Unexpected release platform")
    inventory = {row["path"]: row for row in manifest["files"]}
    chapter_rows = [row for row in recipe["bookLocalization"] if "before" in row] + recipe["patches"]
    for row in chapter_rows:
        if inventory.get(row["path"]) != {"path": row["path"], **row["before"]}:
            raise ValueError(f"Public quest differs from author's current base: {row['path']}")
    new_paths = set(files) - inventory.keys()
    if new_paths != {build.LANG + locale + ".json" for locale in ("en_us", "ru_ru")}:
        raise ValueError("Unexpected new or preexisting localization paths")

    output.mkdir()
    assets = []
    for asset in manifest["assets"]:
        root = asset["covers"][0].partition("/")[0]
        matching_additions = {path for path in new_paths if path.partition("/")[0] == root}
        changes = (files.keys() & set(asset["covers"])) | matching_additions
        if not changes:
            assets.append(asset)
            continue
        if asset["kind"] != "zipRoot":
            raise ValueError(f"Unexpected bundle type: {asset['name']}")
        entries = unpack(asset, asset["covers"])
        for path, data in entries.items():
            if inventory[path] != {"path": path, **build.pin(data)}:
                raise ValueError(f"Invalid base payload: {path}")
        entries.update({path: files.pop(path) for path in sorted(changes)})
        new_paths -= changes
        for path in changes:
            inventory[path] = {"path": path, **build.pin(entries[path])}
        data = bundle(entries)
        digest = build.pin(data)
        name = f"r-{digest['sha256'][:12]}-{root}.zip"
        (output / name).write_bytes(data)
        assets.append({"name": name, "kind": "zipRoot", "covers": sorted(entries), **digest})
    if files or sorted(inventory) != sorted(path for asset in assets for path in asset["covers"]):
        raise ValueError("Candidate assets must cover the complete file inventory exactly once")

    card_entries = unpack(manifest["library"], ("library.json", "preview.jpg"))
    card = json.loads(card_entries["library.json"])
    if card["revision"] != manifest["revision"]:
        raise ValueError("Base library revision does not match the pack")
    card["revision"] = revision
    card_entries["library.json"] = build.json_bytes(card)
    library = bundle(card_entries)
    digest = build.pin(library)
    library_name = f"library-{digest['sha256']}.zip"
    (output / library_name).write_bytes(library)
    manifest.update(
        revision=revision,
        generatedAtUtc=datetime.fromtimestamp(timestamp, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        library={"name": library_name, "revision": revision, **digest},
        files=[inventory[name] for name in sorted(inventory)],
        assets=sorted(assets, key=lambda asset: asset["name"]),
    )
    (output / MANIFEST).write_bytes(build.json_bytes(manifest))
    (output / "previous-manifest.json").write_bytes(old)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--base-sha256", required=True)
    args = parser.parse_args()
    stage(args.archive, args.output, args.base_sha256)
