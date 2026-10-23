#!/usr/bin/env python3
"""Check the actual F-Droid APK's engine assets and distribution boundaries."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import zipfile

import runtime


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--aapt", required=True)
    parser.add_argument("--abis", required=True)
    args = parser.parse_args()
    expected_abis = set(args.abis.split(","))
    source = (runtime.ROOT / "build.gradle").read_text(encoding="utf-8")
    names = re.findall(r"^\s*versionName\s*=\s*'([^']+)'\s*$", source, re.M)
    codes = re.findall(r"^\s*versionCode\s*=\s*(\d+)\s*$", source, re.M)
    assert len(names) == len(codes) == 1, "Ambiguous source version"
    code = int(codes[0])
    if len(expected_abis) == 1:
        code += {"armeabi-v7a": 1, "arm64-v8a": 2, "x86": 3}[next(iter(expected_abis))]
    badging = subprocess.check_output([args.aapt, "dump", "badging", args.apk], text=True)
    identity = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    assert identity and identity.groups() == ("io.dashchan2", str(code), names[0]), "Wrong APK identity"
    assert "application-debuggable" not in badging, "Debuggable F-Droid release"
    manifest = subprocess.check_output([args.aapt, "dump", "xmltree", args.apk, "AndroidManifest.xml"], text=True)
    assert not re.search(r"android:(debuggable|testOnly)[^\n]*0xffffffff", manifest), "Test APK manifest"
    for name in ("AppLockActivity", "PrivacyResetActivity", "SecuritySessionProvider", "FirebaseInitProvider"):
        assert name not in manifest, "Excluded component: " + name
    assert "ExperimentalImageEditorActivity" in manifest, "Shared photo editor missing"
    assert "content.service.translation.TranslationService" in manifest, "Translation service missing"
    expected = json.loads((runtime.HERE / "runtime-lock.json").read_text(encoding="utf-8"))
    assert expected["sourceRevision"] == runtime.specs()["revision"], "Runtime source lock drift"
    assert expected["recipeSha256"] == runtime.recipe_digest(), "Runtime recipe lock drift"
    native = {}
    machines = {"armeabi-v7a": 40, "arm64-v8a": 183, "x86": 3}
    private = re.compile(rb"[A-Za-z]:\\Users\\[^\\/]+[\\/]|/home/[^/\s]+/|/mnt/[a-z]/Users/[^/\s]+/|"
                         rb"gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|"
                         rb"glpat-[A-Za-z0-9_-]{15,}|-----BEGIN [A-Z ]*PRIVATE KEY-----")
    with zipfile.ZipFile(args.apk) as apk:
        entries = apk.namelist()
        assert len(entries) == len(set(entries)), "Duplicate APK entries"
        provenance = json.loads(apk.read("assets/translation/provenance.json"))
        assert provenance == expected, "APK runtime provenance is not the verified source build"
        for name in runtime.OUTPUTS:
            data = apk.read("assets/translation/" + name)
            assert len(data) == expected["files"][name]["size"], "Wrong runtime asset size: " + name
            assert hashlib.sha256(data).hexdigest() == expected["files"][name]["sha256"], "Wrong runtime asset: " + name
        for name in entries:
            assert "\\" not in name and ".." not in name.split("/"), "Unsafe ZIP path"
            assert not re.search(r"\.(jks|keystore|log)$|(^|/)(local|keystore)\.properties$", name, re.I), "Private APK entry"
            data = apk.read(name)
            assert not private.search(data), "Private marker in APK entry: " + name
            if name.endswith(".dex"):
                for marker in (b"Lcom/google/firebase/", b"Lcom/google/android/gms/",
                               b"Lcom/google/android/material/", b"Lcom/google/mlkit/"):
                    assert marker not in data, "Excluded dependency in DEX: " + marker.decode("ascii")
            match = re.fullmatch(r"lib/([^/]+)/([^/]+\.so)", name)
            if match:
                abi = match.group(1)
                assert abi in expected_abis and data[:4] == b"\x7fELF", "Invalid native ABI/ELF"
                assert struct.unpack_from("<H", data, 18)[0] == machines[abi], "Wrong ELF machine"
                native[abi] = native.get(abi, 0) + 1
        assert set(native) == expected_abis, "APK ABI set differs from build request"
        assert not any(name.startswith("assets/translation/") and name.endswith(("model.bin", "lex.bin", ".spm", ".gz"))
                       for name in entries), "Language model accidentally bundled"
    print(json.dumps({"apk": args.apk.name, "version": names[0], "versionCode": code,
                      "abis": sorted(native), "nativeLibraries": native,
                      "sourceRuntimeVerified": True, "privateMarkers": 0}, sort_keys=True))


if __name__ == "__main__":
    main()
