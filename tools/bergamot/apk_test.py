"""Synthetic APK regression tests; these do not replace checking built APKs."""
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import re
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).parent))
SPEC = importlib.util.spec_from_file_location("check_apk", Path(__file__).with_name("check-apk.py"))
checker = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(checker)


class ApkContractTest(unittest.TestCase):
    def check_fixture(self, change=None, offset=0, abis="armeabi-v7a,arm64-v8a,x86"):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            entries = {"assets/translation/" + name: name.encode("ascii") for name in checker.runtime.OUTPUTS}
            expected = {"sourceRevision": checker.runtime.specs()["revision"], "recipeSha256": "fixture",
                        "files": {name: {"sha256": hashlib.sha256(name.encode("ascii")).hexdigest(),
                                         "size": len(name)} for name in checker.runtime.OUTPUTS}}
            entries["assets/translation/provenance.json"] = json.dumps(expected).encode("utf-8")
            for abi in abis.split(","):
                elf = bytearray(64)
                elf[:4] = b"\x7fELF"
                struct.pack_into("<H", elf, 18, {"armeabi-v7a": 40, "arm64-v8a": 183, "x86": 3}[abi])
                entries["lib/" + abi + "/libfixture.so"] = elf
            manifest = "ExperimentalImageEditorActivity content.service.translation.TranslationService"
            if change:
                manifest = change(entries, manifest)
            (root / "runtime-lock.json").write_text(json.dumps(expected), encoding="utf-8")
            apk = root / "fixture.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                for name, data in entries.items():
                    archive.writestr(name, data)
            source = (checker.runtime.ROOT / "build.gradle").read_text(encoding="utf-8")
            version = re.findall(r"^\s*versionName\s*=\s*'([^']+)'\s*$", source, re.M)[0]
            code = int(re.findall(r"^\s*versionCode\s*=\s*(\d+)\s*$", source, re.M)[0]) + offset
            badging = "package: name='io.dashchan2' versionCode='" + str(code) + "' versionName='" + version + "'"
            with patch.object(sys, "argv", ["check-apk.py", str(apk), "--aapt", "fixture", "--abis", abis]), \
                    patch.object(checker.runtime, "HERE", root), \
                    patch.object(checker.runtime, "recipe_digest", return_value="fixture"), \
                    patch.object(checker.subprocess, "check_output", side_effect=[badging, manifest]), \
                    contextlib.redirect_stdout(io.StringIO()):
                checker.main()

    def test_universal_and_split_codes(self):
        self.check_fixture()
        self.check_fixture(offset=2, abis="arm64-v8a")

    def test_runtime_substitution_rejected(self):
        def change(entries, manifest):
            entries["assets/translation/bergamot-translator.wasm"] = b"substituted"
            return manifest
        with self.assertRaisesRegex(AssertionError, "runtime asset"):
            self.check_fixture(change)

    def test_excluded_component_rejected(self):
        with self.assertRaisesRegex(AssertionError, "Excluded component"):
            self.check_fixture(lambda entries, manifest: manifest + " AppLockActivity")

    def test_excluded_dependency_rejected(self):
        def change(entries, manifest):
            entries["classes.dex"] = b"Lcom/google/mlkit/fixture;"
            return manifest
        with self.assertRaisesRegex(AssertionError, "Excluded dependency"):
            self.check_fixture(change)

    def test_local_path_rejected(self):
        def change(entries, manifest):
            entries["assets/fixture.txt"] = b"/home/private-test-fixture/build"
            return manifest
        with self.assertRaisesRegex(AssertionError, "Private marker"):
            self.check_fixture(change)

    def test_wrong_elf_rejected(self):
        def change(entries, manifest):
            entries["lib/arm64-v8a/libfixture.so"][18:20] = b"\x28\x00"
            return manifest
        with self.assertRaisesRegex(AssertionError, "Wrong ELF machine"):
            self.check_fixture(change)


if __name__ == "__main__":
    unittest.main()
