"""Build the All in One skin update by reusing unchanged public release assets."""

from datetime import datetime, timezone
import hashlib
import io
import json
from pathlib import Path
import subprocess
import sys
from urllib.request import urlopen
import zipfile


BASE = "https://github.com/MarkZamore/All-in-One/releases/download/pack-latest/"
JVM = "launcher/jvm-args.txt"
AGENT = "lanminecraft/legacy-skins.jar"


def pin(data):
    return {"sizeBytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}


def fetch(row):
    with urlopen(BASE + row["name"], timeout=30) as response:
        data = response.read(row["sizeBytes"] + 1)
    if pin(data) != {"sizeBytes": row["sizeBytes"], "sha256": row["sha256"]}:
        raise ValueError(f"Public asset hash mismatch: {row['name']}")
    return data


def bundle(entries):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as output:
        for path, data in sorted(entries.items()):
            info = zipfile.ZipInfo(path, (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            output.writestr(info, data)
    return buffer.getvalue()


def git(source, *args):
    return subprocess.check_output(["git", "-C", str(source), *args])


def stage(agent, output):
    source = Path(__file__).resolve().parents[2]
    revision = git(source, "rev-parse", "HEAD").decode().strip()
    timestamp = int(git(source, "show", "-s", "--format=%ct", revision))
    lock = json.loads(git(source, "show", f"{revision}:tools/skin-agent/lock.json"))
    with urlopen(BASE + f"pack-manifest.json?candidate={revision}", timeout=30) as response:
        old_manifest = response.read()
    manifest = json.loads(old_manifest)
    if (manifest["packId"], manifest["minecraftVersion"], manifest["schemaVersion"]) != ("minecraft-14", "1.16.5", 2):
        raise ValueError("Unexpected All in One manifest")
    if manifest["revision"] != lock["baseRevision"]:
        raise ValueError("The live base revision changed. Review the update against the new files.")
    files = {row["path"]: row for row in manifest["files"]}
    launcher = next(row for row in manifest["assets"] if JVM in row["covers"])
    with zipfile.ZipFile(io.BytesIO(fetch(launcher))) as previous:
        launcher_files = {name: previous.read(name) for name in launcher["covers"]}
    new_options = git(source, "show", f"{revision}:{JVM}")
    agent_bytes = agent.read_bytes()
    if pin(agent_bytes) != lock["agent"] or pin(new_options) != lock["jvmArguments"]:
        raise ValueError("Local agent or JVM options differ from their publication lock")
    if AGENT in files or new_options != launcher_files[JVM] + b"-javaagent:lanminecraft/legacy-skins.jar\n":
        raise ValueError("The candidate must add only the Java 8 agent argument and artifact")
    launcher_files[JVM] = new_options
    files[JVM] = {"path": JVM, **pin(new_options)}
    files[AGENT] = {"path": AGENT, **pin(agent_bytes)}
    manifest["assets"].remove(launcher)
    output.mkdir()

    def write_asset(entries, suffix):
        data = bundle(entries)
        entry = pin(data)
        name = f"r-{entry['sha256'][:12]}-{suffix}.zip"
        (output / name).write_bytes(data)
        manifest["assets"].append({"name": name, "kind": "zipRoot", "covers": sorted(entries), **entry})

    write_asset(launcher_files, "launcher")
    write_asset({AGENT: agent_bytes}, "legacy-skins")
    with zipfile.ZipFile(io.BytesIO(fetch(manifest["library"]))) as previous:
        card = json.loads(previous.read("library.json"))
        if card["revision"] != manifest["revision"]:
            raise ValueError("The public library archive has an unexpected revision")
        card["revision"] = revision
        library = bundle({
            "library.json": (json.dumps(card, ensure_ascii=False, indent=2) + "\n").encode(),
            "preview.jpg": previous.read("preview.jpg"),
        })
    library_pin = pin(library)
    library_name = f"library-{library_pin['sha256']}.zip"
    (output / library_name).write_bytes(library)
    manifest.update(revision=revision,
                    generatedAtUtc=datetime.fromtimestamp(timestamp, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
                    files=[files[name] for name in sorted(files)],
                    library={"name": library_name, "revision": revision, **library_pin})
    manifest["assets"].sort(key=lambda item: item["name"])
    if sorted(files) != sorted(path for asset in manifest["assets"] for path in asset["covers"]):
        raise ValueError("The candidate manifest must cover each file exactly once")
    (output / "previous-manifest.json").write_bytes(old_manifest)
    (output / "pack-manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")


if __name__ == "__main__":
    stage(Path(sys.argv[1]), Path(sys.argv[2]))
