"""Optional ordinary-CI cache. Signing and local builds do not invoke this helper."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess


SCHEMA = 1
MACHINES = {"armeabi-v7a": 40, "arm64-v8a": 183, "x86": 3}
ROOTS = ("libraries/dav1d", "libraries/ffmpeg", "libraries/yuv", "external")


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def inventory(root):
    result = {}
    if not root.is_dir() or root.is_symlink():
        raise ValueError("Missing or linked cache/source directory")
    for path in sorted(root.rglob("*")):
        if ".git" in path.relative_to(root).parts:
            continue
        if path.is_symlink():
            raise ValueError("Symlinks are not accepted in native cache inputs/outputs")
        if path.is_file():
            result[path.relative_to(root).as_posix()] = digest(path)
    if not result:
        raise ValueError("Empty cache/source directory")
    return result


def fingerprint(repo, sources, abis, environment):
    text = (repo / "build.gradle").read_text(encoding="utf-8")
    start = text.index("tasks.register('prepareBuiltinWebmSources'")
    end = text.index("tasks.register('syncBuiltinWebmPlayerHeaders'")
    inputs = {
        "schema": SCHEMA,
        "abis": sorted(abis),
        "environment": environment,
        "recipe": text[start:end],
        "scripts": {name: digest(repo / name) for name in (
            "Dashchan-Webm/shared-build.sh", "Dashchan-Webm/shared-prepare.sh",
            ".github/scripts/native_cache.py")},
        "sources": {name: inventory(sources / name) for name in ("dav1d", "ffmpeg", "yuv")},
    }
    return hashlib.sha256(json.dumps(inputs, sort_keys=True).encode()).hexdigest()


def environment(repo):
    properties = (repo / "gradle.properties").read_text()
    version = re.search(r"^androidNdkVersion=(\S+)$", properties, re.M).group(1)
    sdk = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ["ANDROID_HOME"])
    ndk = sdk / "ndk" / version
    result = {"ndk": version, "ndk_package": digest(ndk / "source.properties"),
              "os": platform.system(), "machine": platform.machine(),
              "runner_image": os.environ.get("ImageVersion", "unknown")}
    for name, command in {
        "clang": [str(ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin/clang"), "--version"],
        "meson": ["meson", "--version"], "ninja": ["ninja", "--version"],
        "make": ["make", "--version"], "nasm": ["nasm", "-v"],
    }.items():
        result[name] = subprocess.check_output(command, text=True).strip()
    return result


def required_files(abis):
    required = []
    for abi in abis:
        required += [f"libraries/dav1d/{abi}/libdav1d.so", f"libraries/yuv/{abi}/libyuv.so",
                     f"libraries/dav1d/{abi}/include/dav1d/dav1d.h",
                     f"external/ffmpeg/include/{abi}/libavcodec/avcodec.h",
                     f"external/yuv/include/libyuv.h", f"external/yuv/symbols/{abi}/libyuv.c"]
        for lib in ("avcodec", "avformat", "avfilter", "avutil", "swresample", "swscale"):
            required += [f"libraries/ffmpeg/{abi}/lib{lib}.so",
                         f"external/ffmpeg/symbols/{abi}/lib{lib}.c"]
    return required


def payload_inventory(payload, abis):
    files = {}
    for name in ROOTS:
        files.update({f"{name}/{relative}": value for relative, value in inventory(payload / name).items()})
    for name in required_files(abis):
        if name not in files or (payload / name).stat().st_size == 0:
            raise ValueError(f"Missing native output: {name}")
    for name in files:
        if name.startswith("libraries/"):
            parts = name.split("/")
            # dav1d installs its public headers beside the library, not in external/.
            if (len(parts) > 4 and parts[1] == "dav1d" and parts[2] in abis
                    and parts[3] == "include" and name.endswith(".h")):
                continue
            if len(parts) != 4 or parts[2] not in abis or not name.endswith(".so"):
                raise ValueError(f"Unexpected native ABI/output: {name}")
            with (payload / name).open("rb") as stream:
                header = stream.read(20)
            if len(header) != 20 or header[:4] != b"\x7fELF" or header[5] != 1:
                raise ValueError("Invalid ELF output")
            if int.from_bytes(header[18:20], "little") != MACHINES[parts[2]]:
                raise ValueError("ELF ABI mismatch")
    return files


def output_locations(repo):
    build = repo / "Dashchan-Webm/build"
    return {"libraries": build / "intermediates/shared_libraries", "external": build / "outputs/external"}


def pack(repo, cache, key, abis):
    # Cache is a dedicated disposable runner directory, never a repository or user home.
    if cache.name != "slooop-native-cache" or cache.is_symlink():
        raise ValueError("Invalid cache destination")
    if cache.exists():
        shutil.rmtree(cache)
    payload = cache / "payload"
    locations = output_locations(repo)
    for name in ROOTS:
        category, _, suffix = name.partition("/")
        source = locations[category] / suffix
        inventory(source)  # Reject links before copying.
        shutil.copytree(source, payload / name)
    files = payload_inventory(payload, abis)
    (cache / "manifest.json").write_text(json.dumps({"key": key, "files": files}, sort_keys=True))


def restore(repo, cache, key, abis):
    try:
        if cache.is_symlink() or (cache / "manifest.json").is_symlink():
            raise ValueError("Linked cache")
        manifest = json.loads((cache / "manifest.json").read_text())
        if manifest["key"] != key:
            raise ValueError("Input key mismatch")
        files = payload_inventory(cache / "payload", abis)
        if manifest["files"] != files:
            raise ValueError("Output checksum mismatch")
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f"Native cache MISS: {error}")
        return False
    locations = output_locations(repo)
    for name in ROOTS:
        category, _, suffix = name.partition("/")
        target = locations[category] / suffix
        if target.exists():
            shutil.rmtree(target)
        shutil.copytree(cache / "payload" / name, target)
    print("Native cache HIT: verified source key, output hashes and ELF ABIs")
    return True


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("operation", choices=("key", "pack", "restore"))
    args = parser.parse_args()
    repo = Path.cwd()
    abis = sorted(set(os.environ["NATIVE_ABIS"].split(",")))
    if not abis or any(abi not in MACHINES for abi in abis):
        raise ValueError("Invalid ABI list")
    key = fingerprint(repo, Path(os.environ["CI_NATIVE_SOURCES"]), abis, environment(repo))
    cache = Path(os.environ["RUNNER_TEMP"]) / "slooop-native-cache"
    if args.operation == "key":
        print(f"Native cache input SHA-256: {key}")
        result = f"key=slooop-native-v{SCHEMA}-{key}\n"
    elif args.operation == "restore":
        result = f"hit={str(restore(repo, cache, key, abis)).lower()}\n"
    else:
        pack(repo, cache, key, abis)
        print("Native cache packed after successful build and checks")
        result = ""
    if result:
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as stream:
            stream.write(result)


if __name__ == "__main__":
    main()
