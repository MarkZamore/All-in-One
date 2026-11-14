"""Update All in One's Java 8 agent and retain every other public payload."""

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


def stage(agent, output, source_revision=None):
    source = Path(__file__).resolve().parents[2]
    revision = git(source, "rev-parse", "--verify", source_revision or "HEAD").decode().strip()
    timestamp = int(git(source, "show", "-s", "--format=%ct", revision))
    lock = json.loads(git(source, "show", f"{revision}:tools/skin-agent/lock.json"))
    with urlopen(BASE + f"pack-manifest.json?candidate={revision}", timeout=30) as response:
        old_manifest = response.read()
    manifest = json.loads(old_manifest)
    if (manifest["packId"], manifest["minecraftVersion"], manifest["schemaVersion"]) != ("minecraft-14", "1.16.5", 2):
        raise ValueError("Unexpected All in One manifest")
    if manifest["revision"] != lock["baseRevision"]:
        raise ValueError("The live base revision changed. Review the update against the new files.")
    if pin(old_manifest)["sha256"] != lock["baseManifestSha256"]:
        raise ValueError("The public manifest does not match the known base bytes")
    files = {row["path"]: row for row in manifest["files"]}
    launcher = next(row for row in manifest["assets"] if JVM in row["covers"])
    previous_agent = next(row for row in manifest["assets"] if AGENT in row["covers"])
    if previous_agent["kind"] != "zipRoot" or previous_agent["covers"] != [AGENT]:
        raise ValueError("The current release must ship the agent as one independent asset")
    with zipfile.ZipFile(io.BytesIO(fetch(previous_agent))) as previous:
        if previous.namelist() != [AGENT] or {"path": AGENT, **pin(previous.read(AGENT))} != files[AGENT]:
            raise ValueError("The public agent bytes do not match the public manifest")
    with zipfile.ZipFile(io.BytesIO(fetch(launcher))) as previous:
        published_options = previous.read(JVM)
    new_options = git(source, "show", f"{revision}:{JVM}")
    agent_bytes = agent.read_bytes()
    if pin(agent_bytes) != lock["agent"] or pin(new_options) != lock["jvmArguments"]:
        raise ValueError("Local agent or JVM options differ from their publication lock")
    if new_options != published_options or {"path": JVM, **pin(published_options)} != files[JVM]:
        raise ValueError("The candidate must retain the deployed JVM arguments")
    if {"path": AGENT, **pin(agent_bytes)} == files[AGENT]:
        raise ValueError("The candidate agent is unchanged")
    files[AGENT] = {"path": AGENT, **pin(agent_bytes)}
    manifest["assets"].remove(previous_agent)
    output.mkdir()

    def write_asset(entries, suffix):
        data = bundle(entries)
        entry = pin(data)
        name = f"r-{entry['sha256'][:12]}-{suffix}.zip"
        (output / name).write_bytes(data)
        manifest["assets"].append({"name": name, "kind": "zipRoot", "covers": sorted(entries), **entry})

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
    stage(Path(sys.argv[1]), Path(sys.argv[2]), *sys.argv[3:])
