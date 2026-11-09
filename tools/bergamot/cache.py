#!/usr/bin/env python3
"""Exact, verified reuse of independently source-built Bergamot outputs in CI."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
from types import SimpleNamespace

import runtime

SCHEMA = 1
FILES = (*runtime.OUTPUTS, "provenance.json")
RECEIPT = "cache-receipt.json"


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8")


def fingerprint(root, environment):
    root = Path(root)
    paths = [root / ".gitmodules", root / ".github/workflows/bergamot-runtime.yml"]
    for directory in (root / "tools/bergamot", root / "assets/translation"):
        paths.extend(p for p in directory.rglob("*") if p.is_file()
                     and "__pycache__" not in p.parts
                     and p.suffix in (".py", ".json", ".js", ".cjs", ".cmake"))
    inputs = {}
    for path in sorted(set(paths)):
        if path.is_symlink():
            raise ValueError("Linked cache input is not accepted")
        inputs[path.relative_to(root).as_posix()] = hashlib.sha256(
            path.read_bytes().replace(b"\r\n", b"\n")).hexdigest()
    material = {"schema": SCHEMA, "inputs": inputs, "environment": environment}
    return {"key": "bergamot-verified-v1-" + hashlib.sha256(canonical(material)).hexdigest(),
            "material": material}


def environment():
    packages = subprocess.check_output(
        ["dpkg-query", "-W", "-f=${binary:Package}=${Version}\n"], text=True)
    return {"architecture": platform.machine(),
            "compiler": subprocess.check_output(["emcc", "--version"], text=True).strip(),
            "packagesSha256": hashlib.sha256("\n".join(sorted(packages.splitlines())).encode()).hexdigest()}


def verify_runtime(directory):
    directory = Path(directory)
    if directory.is_symlink() or not directory.is_dir():
        raise ValueError("Runtime directory missing or linked")
    if {p.name for p in directory.iterdir()} != set(FILES):
        raise ValueError("Unexpected runtime file set")
    if any(not p.is_file() or p.is_symlink() for p in directory.iterdir()):
        raise ValueError("Linked or non-regular runtime output")
    runtime.verify(SimpleNamespace(runtime_dir=directory, lock=runtime.HERE / "runtime-lock.json"))


def restore(state, entry, destination):
    entry, destination = Path(entry), Path(destination)
    # Validate everything before copying even one byte into the build's runtime directory.
    try:
        if entry.is_symlink() or not entry.is_dir():
            raise ValueError("Cache absent or linked")
        if {p.name for p in entry.iterdir()} != {"runtime", RECEIPT}:
            raise ValueError("Unexpected cache file set")
        receipt = entry / RECEIPT
        if receipt.is_symlink() or not receipt.is_file():
            raise ValueError("Invalid cache receipt")
        expected = {"schema": SCHEMA, "fingerprint": state,
                    "independentRebuildCompared": True}
        if json.loads(receipt.read_text(encoding="utf-8")) != expected:
            raise ValueError("Cache identity or verification receipt mismatch")
        verify_runtime(entry / "runtime")
    except (OSError, ValueError, KeyError, TypeError):
        print("Bergamot cache miss: absent or validation rejected; rebuild from source")
        return False
    runtime.ensure_disjoint(entry, destination)
    if destination.exists():
        raise ValueError("Runtime output must be a new directory")
    shutil.copytree(entry / "runtime", destination)
    print("Restored exact, lock-verified source-built Bergamot runtime")
    return True


def pack(state, original, rebuilt, entry):
    original, rebuilt, entry = map(Path, (original, rebuilt, entry))
    runtime.ensure_disjoint(original, rebuilt, entry)
    verify_runtime(original)
    verify_runtime(rebuilt)
    if any((original / name).read_bytes() != (rebuilt / name).read_bytes() for name in FILES):
        raise ValueError("Independent build outputs differ")
    # A new cache entry only; never overwrite a previously restored untrusted directory.
    entry.mkdir(parents=True, exist_ok=False)
    shutil.copytree(original, entry / "runtime")
    (entry / RECEIPT).write_bytes(canonical({"schema": SCHEMA, "fingerprint": state,
                                          "independentRebuildCompared": True}))


def output(name, value):
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as stream:
        stream.write(name + "=" + value + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    key = commands.add_parser("key")
    key.add_argument("--state", required=True, type=Path)
    for name in ("restore", "pack"):
        command = commands.add_parser(name)
        command.add_argument("--state", required=True, type=Path)
        command.add_argument("--entry", required=True, type=Path)
        command.add_argument("--runtime", required=True, type=Path)
        if name == "pack":
            command.add_argument("--rebuilt", required=True, type=Path)
    args = parser.parse_args()
    if args.command == "key":
        state = fingerprint(runtime.ROOT, environment())
        args.state.write_bytes(canonical(state))
        output("key", state["key"])
    else:
        state = json.loads(args.state.read_text(encoding="utf-8"))
        if args.command == "restore":
            output("hit", "true" if restore(state, args.entry, args.runtime) else "false")
        else:
            pack(state, args.runtime, args.rebuilt, args.entry)


if __name__ == "__main__":
    main()
