"""Stage the reviewed launcher files against the live All in One payload."""

from datetime import datetime, timezone
import hashlib
from io import BytesIO
import json
from pathlib import Path
import subprocess
import sys
from urllib.request import Request, urlopen
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

from check_preset import HERE, PRESET

BASE = "https://github.com/MarkZamore/All-in-One/releases/download/pack-latest/"
RELATIVE = "launcher/controls-preset.txt"
OPTIONS = "launcher/options-default.txt"


def pin(data):
    return {"sizeBytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}


def fetch(name, limit):
    request = Request(BASE + name, headers={"Cache-Control": "no-cache"})
    with urlopen(request, timeout=40) as response:
        data = response.read(limit + 1)
    if len(data) > limit:
        raise ValueError(f"Oversized asset: {name}")
    return data


def bundle(entries):
    stream = BytesIO()
    with ZipFile(stream, "w") as archive:
        for name, data in sorted(entries.items()):
            info = ZipInfo(name)
            info.compress_type = ZIP_DEFLATED
            archive.writestr(info, data)
    return stream.getvalue()


def encoded_json(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode()


def stage(output, base_sha):
    source = PRESET.parents[1]
    revision = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
    timestamp = int(subprocess.check_output(["git", "-C", str(source), "show", "-s", "--format=%ct", revision]))
    old = fetch(f"pack-manifest.json?controls={revision}", 2 * 1024 * 1024)
    if pin(old)["sha256"] != base_sha:
        raise ValueError("The public manifest changed before staging")
    manifest = json.loads(old)
    if (manifest["schemaVersion"], manifest["packId"], manifest["minecraftVersion"], manifest["loader"]) != (
            2, "minecraft-14", "1.16.5", {"type": "forge", "version": "36.2.34"}):
        raise ValueError("Unexpected platform")
    snapshot = json.loads((HERE / "registered-keys.json").read_bytes())
    mods = {row["path"]: row["sha256"] for row in manifest["files"] if row["path"].startswith("mods/")}
    if mods != snapshot["modSha256"]:
        raise ValueError("Published mod bytes differ from the reviewed controls inventory")

    options = (source / OPTIONS).read_bytes()
    if options != b"!skipMultiplayerWarning:true\n":
        raise ValueError("Review changes to the pack's warning option before publishing")
    updates = {RELATIVE: PRESET.read_bytes(), OPTIONS: options}
    assets = []
    for row in manifest["assets"]:
        if updates.keys() & set(row["covers"]):
            if len(row["covers"]) != 1 or row["kind"] != "zipRoot":
                raise ValueError("A launcher file shares an archive; review before replacing it")
        else:
            assets.append(row)
    files = {row["path"]: row for row in manifest["files"]}
    new_assets = {}
    for path, payload in updates.items():
        files[path] = {"path": path, **pin(payload)}
        data = bundle({path: payload})
        name = f"r-{pin(data)['sha256'][:12]}-{Path(path).stem}.zip"
        assets.append({"name": name, "kind": "zipRoot", "covers": [path], **pin(data)})
        new_assets[name] = data
    if sorted(files) != sorted(path for asset in assets for path in asset["covers"]):
        raise ValueError("Candidate assets do not cover the full inventory exactly once")

    library = fetch(manifest["library"]["name"], manifest["library"]["sizeBytes"])
    if pin(library) != {key: manifest["library"][key] for key in ("sha256", "sizeBytes")}:
        raise ValueError("Public library differs from the manifest")
    with ZipFile(BytesIO(library)) as archive:
        if sorted(archive.namelist()) != ["library.json", "preview.jpg"]:
            raise ValueError("Unexpected library members")
        card = json.loads(archive.read("library.json"))
        if card["revision"] != manifest["revision"]:
            raise ValueError("Inconsistent library revision")
        card["revision"] = revision
        library = bundle({"library.json": encoded_json(card), "preview.jpg": archive.read("preview.jpg")})
    library_name = f"library-{pin(library)['sha256']}.zip"
    manifest.update(
        revision=revision,
        generatedAtUtc=datetime.fromtimestamp(timestamp, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        files=sorted(files.values(), key=lambda row: row["path"]),
        assets=sorted(assets, key=lambda row: row["name"]),
        library={"name": library_name, "revision": revision, **pin(library)},
    )
    output.mkdir()
    for name, data in new_assets.items():
        (output / name).write_bytes(data)
    (output / library_name).write_bytes(library)
    (output / "previous-manifest.json").write_bytes(old)
    (output / "pack-manifest.json").write_bytes(encoded_json(manifest))


if __name__ == "__main__":
    stage(Path(sys.argv[1]), sys.argv[2])
