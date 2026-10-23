#!/usr/bin/env python3
"""Pinned, offline-at-build-time Bergamot source build for the F-Droid flavour."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
SOURCE = ROOT / "third_party/mozilla-translations"
SPECS = HERE / "sources.json"
OUTPUTS = ("bergamot-translator.js", "bergamot-translator.wasm")


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def specs():
    return json.loads(SPECS.read_text(encoding="utf-8"))


def run(command, **kwargs):
    return subprocess.run([str(arg) for arg in command], check=True, **kwargs)


def git(directory, *args):
    return subprocess.check_output(
        ["git", "-C", str(directory), *args], text=True, encoding="utf-8"
    ).strip()


def recipe_digest():
    value = hashlib.sha256()
    for name in ("sources.json", "runtime.py", "factory.js", "reproducible.cmake"):
        value.update(name.encode("ascii") + b"\0")
        # Git checkouts on Windows must not change the build recipe identity.
        value.update((HERE / name).read_bytes().replace(b"\r\n", b"\n"))
    return value.hexdigest()


def check_sources():
    expected = specs()
    if git(SOURCE, "rev-parse", "HEAD") != expected["revision"]:
        raise ValueError("Bergamot source revision mismatch")
    if git(SOURCE, "status", "--porcelain", "--untracked-files=no", "--ignore-submodules=none"):
        raise ValueError("Bergamot sources or submodule revisions have local modifications")
    for name, revision in expected["submodules"].items():
        if git(SOURCE / name, "rev-parse", "HEAD") != revision:
            raise ValueError("Bergamot dependency revision mismatch: " + name)
        if git(SOURCE / name, "status", "--porcelain", "--untracked-files=no"):
            raise ValueError("Modified Bergamot dependency: " + name)


def prepare(_args):
    """Fetch only source code, before F-Droid's source scanner runs."""
    expected = specs()
    entry = git(ROOT, "ls-tree", "HEAD", "third_party/mozilla-translations")
    # Worktree development may have the new gitlink staged but not committed yet.
    staged = git(ROOT, "ls-files", "--stage", "third_party/mozilla-translations")
    if expected["revision"] not in (entry + staged):
        raise ValueError("The source manifest does not match the repository gitlink")
    run(["git", "-C", ROOT, "submodule", "update", "--init", "--checkout", "--depth", "1",
         "--", "third_party/mozilla-translations"])
    # Do not fetch training tools, test model binaries, emsdk installers, CUDA,
    # unrelated inference backends. Only the required ONNX SGEMM source is used.
    run(["git", "-C", SOURCE, "sparse-checkout", "set", "--no-cone",
         "/LICENSE", "/.gitmodules", "/inference/**", "!/inference/wasm/tests/**"])
    for name in expected["submodules"]:
        run(["git", "-C", SOURCE, "submodule", "update", "--init", "--checkout", "--depth", "1",
             "--", name])
    check_sources()
    print("Verified pinned Bergamot sources (no runtime binaries downloaded)")


def ensure_disjoint(*paths):
    resolved = [Path(path).resolve() for path in paths]
    for index, path in enumerate(resolved):
        for other in resolved[index + 1:]:
            if path == other or path in other.parents or other in path.parents:
                raise ValueError("Source, build and runtime directories must be separate")
    return resolved


def build(args):
    check_sources()
    expected = specs()
    compiler = subprocess.check_output(["emcc", "--version"], text=True)
    match = re.search(r"emcc .*? (\d+\.\d+\.\d+)", compiler.splitlines()[0])
    if not match or match.group(1) != expected["emscriptenVersion"]:
        raise ValueError("Use the pinned Debian Emscripten " + expected["emscriptenVersion"])
    source, build_dir, output_dir = ensure_disjoint(SOURCE, args.build_dir, args.output_dir)
    if build_dir.exists() and any(build_dir.iterdir()):
        raise ValueError("Use a new empty build directory; stale objects are not accepted")
    if output_dir.exists() and any(output_dir.iterdir()):
        raise ValueError("Use a new empty runtime output directory")
    build_dir.mkdir(parents=True, exist_ok=True)
    output_dir.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    env.update({"SOURCE_DATE_EPOCH": str(expected["sourceDateEpoch"]),
                "TZ": "UTC", "LC_ALL": "C.UTF-8", "GIT_CONFIG_COUNT": "1",
                "GIT_CONFIG_KEY_0": "core.abbrev", "GIT_CONFIG_VALUE_0": "40"})
    run(["emcmake", "cmake", "-S", source / "inference", "-B", build_dir, "-G", "Ninja",
         "-DCMAKE_BUILD_TYPE=Release", "-DCOMPILE_WASM=ON", "-DWORMHOLE=OFF",
         "-DGIT_SUBMODULE=OFF", "-DUSE_INTGEMM=ON", "-DUSE_ONNX_SGEMM=ON",
         "-DUSE_RUY=OFF", "-DUSE_SIMD_UTILS=OFF", "-DUSE_FBGEMM=OFF",
         "-DUSE_THREADS=OFF", "-DCOMPILE_TESTS=OFF", "-DCOMPILE_UNIT_TESTS=OFF",
         "-DCOMPILE_PYTHON=OFF", "-DUSE_DOXYGEN=OFF",
         "-DCMAKE_PROJECT_INCLUDE=" + str(HERE / "reproducible.cmake"),
         "-DSLOOOP_BERGAMOT_SOURCE=" + str(source),
         "-DSLOOOP_BERGAMOT_BUILD=" + str(build_dir)], env=env)
    run(["cmake", "--build", build_dir, "--target", "bergamot-translator", "-j", args.jobs], env=env)
    generated = (build_dir / OUTPUTS[0]).read_text(encoding="utf-8")
    factory = (HERE / "factory.js").read_text(encoding="utf-8")
    marker = "/* SLOOOP_GENERATED_EMSCRIPTEN */"
    if factory.count(marker) != 1 or "WebAssembly" not in generated:
        raise ValueError("Unexpected Emscripten output or runtime factory")
    (output_dir / OUTPUTS[0]).write_text(factory.replace(marker, generated), encoding="utf-8", newline="\n")
    shutil.copyfile(build_dir / OUTPUTS[1], output_dir / OUTPUTS[1])
    if (output_dir / OUTPUTS[1]).read_bytes()[:8] != b"\0asm\x01\0\0\0":
        raise ValueError("Compiler did not produce a WebAssembly module")
    manifest = {"schema": 1, "sourceRevision": expected["revision"],
                "dependencyRevisions": expected["submodules"],
                "emscriptenVersion": expected["emscriptenVersion"],
                "recipeSha256": recipe_digest(),
                "files": {name: {"sha256": digest(output_dir / name),
                                  "size": (output_dir / name).stat().st_size} for name in OUTPUTS}}
    (output_dir / "provenance.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8", newline="\n")
    print(json.dumps(manifest, indent=2, sort_keys=True))


def verify(args):
    directory = Path(args.runtime_dir)
    manifest = json.loads((directory / "provenance.json").read_text(encoding="utf-8"))
    expected = specs()
    if (manifest.get("schema") != 1 or manifest.get("sourceRevision") != expected["revision"]
            or manifest.get("dependencyRevisions") != expected["submodules"]
            or manifest.get("emscriptenVersion") != expected["emscriptenVersion"]
            or manifest.get("recipeSha256") != recipe_digest()):
        raise ValueError("Runtime source, recipe or toolchain provenance mismatch")
    for name in OUTPUTS:
        file = directory / name
        info = manifest["files"][name]
        if file.stat().st_size != info["size"] or digest(file) != info["sha256"]:
            raise ValueError("Runtime output checksum mismatch: " + name)
    if args.lock:
        lock = json.loads(Path(args.lock).read_text(encoding="utf-8"))
        if lock != manifest:
            raise ValueError("Runtime does not match the independently rebuilt output lock")
    print("Verified source-built Bergamot runtime")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("prepare").set_defaults(function=prepare)
    compiler = commands.add_parser("build")
    compiler.add_argument("--build-dir", required=True, type=Path)
    compiler.add_argument("--output-dir", required=True, type=Path)
    compiler.add_argument("--jobs", default="2", choices=[str(i) for i in range(1, 17)])
    compiler.set_defaults(function=build)
    verifier = commands.add_parser("verify")
    verifier.add_argument("--runtime-dir", required=True)
    verifier.add_argument("--lock")
    verifier.set_defaults(function=verify)
    args = parser.parse_args()
    try:
        args.function(args)
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        parser.exit(1, "Bergamot: " + str(error) + "\n")


if __name__ == "__main__":
    main()
