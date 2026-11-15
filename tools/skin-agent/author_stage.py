"""Carry the pack-owned Java 8 skin agent through the verified author build."""

import argparse
from pathlib import Path
import sys

from stage_update import AGENT, JVM, pin


def checked_agent(source, author, tools):
    tools.source_policy(source, author)
    if (author["pack"]["minecraftVersion"], author["pack"]["loader"].get("type")) != ("1.16.5", "forge"):
        raise ValueError("Review the skin agent for a changed author platform")
    if any(row["path"].casefold() == AGENT.casefold() for row in [*author["overrides"], *author.get("files", [])]):
        raise ValueError("The author release supplies a conflicting skin agent")
    lock = tools.prepare.read_local_json(source / "tools/skin-agent/lock.json")
    agent = tools.prepare.overlay_bytes(source / AGENT)
    jvm = tools.prepare.overlay_bytes(source / JVM)
    if pin(agent) != lock["agent"] or pin(jvm) != lock["jvmArguments"] or f"-javaagent:{AGENT}" not in jvm.decode().splitlines():
        raise ValueError("All in One skin agent and JVM arguments must match the pack lock")
    return agent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--launcher-source", type=Path, required=True)
    parser.add_argument("--stage", type=Path, required=True)
    parser.add_argument("--client-archive", type=Path)
    parser.add_argument("--verified-dependencies", type=Path)
    parser.add_argument("--manifest", type=Path, help="Verify an existing stage and manifest instead of creating a stage")
    args = parser.parse_args()
    sys.path.insert(0, str(args.launcher_source.resolve() / "tools/PackData/CurseForge"))
    import release_overlay as tools

    source = Path(__file__).resolve().parents[2]
    lock_path = source / tools.COMPLETE_LOCK
    author = tools.read_lock(lock_path, "complete")
    tools.same_author(author, tools.read_lock(source / tools.CLIENT_LOCK, "client-only"))
    agent = checked_agent(source, author, tools)
    if not args.manifest:
        args.pack_source = source
        args.output = args.stage
        tools.stage_pack(args, author, lock_path)

    target = args.stage / "game" / AGENT
    tools.prepare.no_links(target)
    if not args.manifest:
        target.parent.mkdir(exist_ok=True)
        with target.open("xb") as output:
            output.write(agent)
        print(f"Verified author archive and All in One skin agent: {args.stage}")
        return

    agent_pin = pin(agent)
    with target.open("rb") as installed:
        tools.prepare.exact_bytes(installed, agent_pin["sizeBytes"], expected={"sha256": agent_pin["sha256"]})
    policy, excluded = tools.verify_stage(args.stage, source, author)
    policy[AGENT] = agent_pin
    recipe, _, _, _ = tools.source_policy(source, author)
    mod_metadata = tools.prepare.jar_metadata_patches(recipe, author, source)
    book_jars = tools.prepare.book_jar_localizations(recipe, author, source, mod_metadata)
    tools.verify_manifest(tools.prepare.read_local_json(args.manifest), policy, author, excluded, [*mod_metadata, *book_jars])
    print(f"Verified author release and All in One skin agent: {args.manifest}")


if __name__ == "__main__":
    main()
