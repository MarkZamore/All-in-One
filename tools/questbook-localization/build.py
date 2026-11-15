"""Generate the language layer from the pinned author ZIP and reviewed Russian text."""

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
from zipfile import ZipFile


SOURCE = Path(__file__).resolve().parents[2]
TABLES = "tools/questbook-localization/ru"
QUESTS = "config/ftbquests/quests/"
LANG = "kubejs/assets/all_in_one_quests/lang/"
OWNER = "launcher/pack-owned.txt"
RECIPE = "tools/overlay.json"
LOCK = "tools/upstream-lock.json"
STRING = r'"(?:[^"\\]|\\.)*"'
QUOTED = re.compile(STRING)
FIELDS = re.compile(
    rf'(?m)^[ \t]*(?:title|subtitle|description|text|lock_message):[ \t]*'
    rf'(?P<values>{STRING}|\[\s*(?:{STRING}\s*,?\s*)*\])'
)
MARKUP = re.compile(r"(?<!\\)[&§][0-9a-fk-or]|https?://[^\s\"<>]+", re.IGNORECASE)


def pin(data):
    return {"sizeBytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode()


def unique_pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate entry: {key}")
        result[key] = value
    return result


def read_json(path):
    return json.loads(path.read_bytes(), object_pairs_hook=unique_pairs)


def quest_files(archive):
    result = {}
    for member in archive.infolist():
        path = member.filename.removeprefix("overrides/")
        if member.is_dir() or not member.filename.startswith("overrides/" + QUESTS):
            continue
        if not re.fullmatch(QUESTS + r"(?:chapter_groups|data|(?:chapters|reward_tables)/[a-z0-9_.-]+)\.snbt", path):
            raise ValueError(f"Review unsupported quest data: {path}")
        if path in result:
            raise ValueError(f"Duplicate author quest file: {path}")
        result[path] = archive.read(member)
    if not result:
        raise ValueError("No author quest files found")
    return dict(sorted(result.items()))


def text_tokens(source):
    for field in FIELDS.finditer(source):
        for quote in QUOTED.finditer(source, *field.span("values")):
            yield quote, json.loads(quote.group())


def key_for(path, text):
    stem = PurePosixPath(path).stem
    digest = hashlib.sha256(text.encode()).hexdigest()[:20]
    return f"all_in_one_quests.{stem}.{digest}"


def rewrite(path, data, translations, english, russian):
    source = data.decode("utf-8")
    tokens = list(text_tokens(source))
    required = {text for _, text in tokens if text.strip()}
    provided = {text for text in translations if text.strip()}
    if required != provided:
        raise ValueError(f"{path}: missing {sorted(required - provided)!r}, stale {sorted(provided - required)!r}")
    pieces = []
    cursor = 0
    for quote, text in tokens:
        if not text.strip():
            if translations.get(text, text) != text:
                raise ValueError(f"{path}: whitespace-only text has changed")
            continue
        translated = translations[text]
        if not isinstance(translated, str) or not translated.strip() or MARKUP.findall(text) != MARKUP.findall(translated):
            raise ValueError(f"{path}: invalid translation or changed markup: {text!r}")
        key = key_for(path, text)
        for value, locale in ((text, english), (translated, russian)):
            value = value.replace("%", "%%")
            if locale.setdefault(key, value) != value:
                raise ValueError(f"Conflicting translations for {key}")
        pieces.extend((source[cursor:quote.start()], json.dumps("{" + key + "}")))
        cursor = quote.end()
    pieces.append(source[cursor:])
    return "".join(pieces).encode(), sum(1 for _, text in tokens if text.strip())


def generate(archive_path, source=SOURCE):
    lock = read_json(source / LOCK)
    recipe = read_json(source / RECIPE)
    with archive_path.open("rb") as stream:
        archive_pin = {"sizeBytes": archive_path.stat().st_size, "sha256": hashlib.file_digest(stream, "sha256").hexdigest()}
    if archive_pin != {key: lock["client"][key] for key in archive_pin} or archive_pin["sha256"] != recipe["authorArchiveSha256"]:
        raise ValueError("Expected the official client ZIP from the current locks")
    with ZipFile(archive_path) as archive:
        originals = quest_files(archive)
    inputs = {path: pin(data) for path, data in originals.items()}
    declared = {row["path"]: {key: row[key] for key in ("sizeBytes", "sha256")}
                for row in lock["overrides"] if row["path"].startswith(QUESTS)}
    if inputs != declared:
        raise ValueError("Quest inventory differs from the pinned client")

    english, russian, outputs = {}, {}, {}
    entries, reward_patches, used_tables = [], [], set()
    occurrences = 0
    for path, data in originals.items():
        table = source / TABLES / (PurePosixPath(path).stem + ".json")
        if table.is_file():
            used_tables.add(table)
        rewritten, count = rewrite(path, data, read_json(table) if table.is_file() else {}, english, russian)
        occurrences += count
        if rewritten == data:
            continue
        if path.startswith(QUESTS + "reward_tables/"):
            # The general overlay already supports exact byte replacements for reward tables.
            source_text = data.decode()
            replacements = []
            for quote, content in text_tokens(source_text):
                if content.strip():
                    field = source_text[source_text.rfind("\n", 0, quote.start()) + 1:quote.end()]
                    replacement = field[:len(field) - len(quote.group())] + json.dumps("{" + key_for(path, content) + "}")
                    if source_text.count(field) != 1:
                        raise ValueError(f"Ambiguous reward text in {path}")
                    replacements.append({"from": field, "to": replacement})
            reward_patches.append({"path": path, "before": pin(data), "after": pin(rewritten),
                                   "replacements": replacements})
        else:
            outputs[path] = rewritten
            entries.append({"path": path, "before": inputs[path], "inputs": {path: inputs[path]}, "output": pin(rewritten)})
    if used_tables != set((source / TABLES).glob("*.json")):
        raise ValueError("Translation tables reference missing author files")

    for pattern in ("ftb-quests-forge-", "ftb-library-forge-", "kubejs-forge-"):
        rows = [row for row in lock["files"] if PurePosixPath(row["path"]).name.startswith(pattern) and row["required"]]
        if len(rows) != 1:
            raise ValueError(f"Expected one pinned localization dependency: {pattern}")
        row, = rows
        inputs[row["path"]] = {key: row[key] for key in ("sizeBytes", "sha256", "projectId", "fileId")}
    for locale, texts in (("en_us", english), ("ru_ru", russian)):
        path = LANG + locale + ".json"
        if any(row["path"].casefold() == path for row in lock["overrides"]):
            raise ValueError(f"Author now ships a language file at {path}")
        outputs[path] = json_bytes(texts)
        entries.append({"path": path, "inputs": inputs, "output": pin(outputs[path])})

    previous = {row["path"] for row in recipe.get("bookLocalization", [])}
    previous_rewards = {row["path"] for row in recipe["patches"] if row["path"].startswith(QUESTS + "reward_tables/")}
    if previous - outputs.keys() or previous_rewards - {row["path"] for row in reward_patches}:
        raise ValueError("Remove retired translation outputs from the source and ownership before rebuilding")
    recipe["patches"] = [row for row in recipe["patches"] if row["path"] not in previous_rewards] + reward_patches
    recipe["bookLocalization"] = entries
    owned = set((source / OWNER).read_text(encoding="utf-8").splitlines())
    owned.update(outputs)
    owned.update(row["path"] for row in reward_patches)
    outputs[OWNER] = ("\n".join(sorted(owned)) + "\n").encode()
    for row in recipe["additions"]:
        row.update(pin((source / row["path"]).read_bytes()))
    outputs[RECIPE] = json_bytes(recipe)
    shape = Counter(PurePosixPath(path).parent.name for path in originals)
    print(f"{shape['chapters']} chapters, {shape['reward_tables']} reward tables, "
          f"{occurrences} text occurrences, {len(russian)} keys")
    return outputs


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    for name, data in generate(args.archive).items():
        target = SOURCE / name
        if args.check:
            if not target.is_file() or target.read_bytes() != data:
                raise ValueError(f"Committed translation differs: {name}")
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)


if __name__ == "__main__":
    main()
