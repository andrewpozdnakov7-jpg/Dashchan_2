import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import cache


class TranslatorCacheTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.recipe = self.root / "recipe"
        self.recipe.mkdir()
        self.original = self.root / "original"
        self.rebuilt = self.root / "rebuilt"
        self.entry = self.root / "cache"
        self.destination = self.root / "restored"
        self.state = {"key": "bergamot-verified-v1-test", "material": {"inputs": {}, "environment": {}}}
        self.addCleanup(patch.stopall)
        patch.object(cache.runtime, "HERE", self.recipe).start()
        self.make_runtime(self.original)
        self.make_runtime(self.rebuilt)

    def make_runtime(self, directory):
        directory.mkdir()
        (directory / "bergamot-translator.js").write_text("function loadBergamot(Module) {}", encoding="utf-8")
        (directory / "bergamot-translator.wasm").write_bytes(b"\0asm\x01\0\0\0")
        (directory / "LICENSES.txt").write_text("source and compiler notices", encoding="utf-8")
        expected = {"revision": "source", "submodules": {}, "patches": {},
                    "emscriptenVersion": "3", "emscriptenDebianPackage": "3-debian"}
        patch.object(cache.runtime, "specs", return_value=expected).start()
        patch.object(cache.runtime, "recipe_digest", return_value="recipe").start()
        manifest = {"schema": 1, "sourceRevision": "source", "dependencyRevisions": {},
                    "sourcePatches": {}, "emscriptenVersion": "3", "emscriptenDebianPackage": "3-debian",
                    "recipeSha256": "recipe", "files": {
                        name: {"size": (directory / name).stat().st_size,
                               "sha256": cache.runtime.digest(directory / name)} for name in cache.runtime.OUTPUTS}}
        (directory / "provenance.json").write_text(json.dumps(manifest), encoding="utf-8")
        (self.recipe / "runtime-lock.json").write_text(json.dumps(manifest), encoding="utf-8")

    def pack(self):
        cache.pack(self.state, self.original, self.rebuilt, self.entry)

    def test_verified_entry_restores_exact_bytes(self):
        self.pack()
        self.assertTrue(cache.restore(self.state, self.entry, self.destination))
        for name in cache.FILES:
            self.assertEqual((self.destination / name).read_bytes(), (self.original / name).read_bytes())

    def test_miss_never_creates_a_runtime_output(self):
        self.assertFalse(cache.restore(self.state, self.entry, self.destination))
        self.assertFalse(self.destination.exists())

    def test_tampered_payload_rejected_before_copy(self):
        self.pack()
        (self.entry / "runtime/bergamot-translator.wasm").write_bytes(b"bad")
        self.assertFalse(cache.restore(self.state, self.entry, self.destination))
        self.assertFalse(self.destination.exists())

    def test_forged_receipt_cannot_replace_the_repository_output_lock(self):
        self.pack()
        target = self.entry / "runtime/bergamot-translator.wasm"
        target.write_bytes(b"\0asm\x01\0\0\0tampered")
        path = self.entry / "runtime/provenance.json"
        manifest = json.loads(path.read_text())
        manifest["files"][target.name] = {"size": target.stat().st_size, "sha256": cache.runtime.digest(target)}
        path.write_text(json.dumps(manifest))
        self.assertFalse(cache.restore(self.state, self.entry, self.destination))
        self.assertFalse(self.destination.exists())

    def test_wrong_environment_or_source_key_rejected(self):
        self.pack()
        wrong = copy.deepcopy(self.state)
        wrong["material"]["environment"] = {"compiler": "different"}
        self.assertFalse(cache.restore(wrong, self.entry, self.destination))

    def test_receipt_must_attest_independent_comparison(self):
        self.pack()
        path = self.entry / cache.RECEIPT
        receipt = json.loads(path.read_text())
        receipt["independentRebuildCompared"] = False
        path.write_text(json.dumps(receipt))
        self.assertFalse(cache.restore(self.state, self.entry, self.destination))

    def test_extra_files_and_directories_are_not_copied(self):
        self.pack()
        (self.entry / "runtime/unexpected").mkdir()
        self.assertFalse(cache.restore(self.state, self.entry, self.destination))
        self.assertFalse(self.destination.exists())

    def test_linked_entries_are_rejected(self):
        self.pack()
        with patch.object(Path, "is_symlink", return_value=True):
            self.assertFalse(cache.restore(self.state, self.entry, self.destination))

    def test_packing_requires_independent_and_lock_matching_outputs(self):
        with self.assertRaises(ValueError):
            cache.pack(self.state, self.original, self.original, self.entry)
        (self.rebuilt / "LICENSES.txt").write_bytes(b"different")
        with self.assertRaises(ValueError):
            self.pack()
        self.assertFalse(self.entry.exists())

    def test_existing_outputs_are_never_overwritten(self):
        self.pack()
        self.destination.mkdir()
        (self.destination / "keep").write_bytes(b"keep")
        with self.assertRaises(ValueError):
            cache.restore(self.state, self.entry, self.destination)
        self.assertEqual((self.destination / "keep").read_bytes(), b"keep")
        with self.assertRaises(FileExistsError):
            self.pack()

    def test_key_ignores_app_version_but_tracks_every_engine_input(self):
        repo = self.root / "repo"
        for name in (".gitmodules", ".github/workflows/bergamot-runtime.yml", "tools/bergamot/runtime.py",
                     "tools/bergamot/runtime-lock.json", "tools/bergamot/sources.json", "assets/translation/bridge.js"):
            file = repo / name
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_bytes(b"pinned\n")
        app = repo / "build.gradle"
        app.write_bytes(b"versionCode = 11600\n")
        environment = {"compiler": "pinned", "packagesSha256": "packages", "architecture": "x86_64"}
        before = cache.fingerprint(repo, environment)
        app.write_bytes(b"versionCode = 11610\n")
        self.assertEqual(before, cache.fingerprint(repo, environment))
        for name in before["material"]["inputs"]:
            file = repo / name
            original = file.read_bytes()
            file.write_bytes(b"changed\n")
            self.assertNotEqual(before["key"], cache.fingerprint(repo, environment)["key"], name)
            file.write_bytes(original.replace(b"\n", b"\r\n"))
            self.assertEqual(before, cache.fingerprint(repo, environment))
            file.write_bytes(original)
        self.assertNotEqual(before["key"], cache.fingerprint(repo, {**environment, "compiler": "new"})["key"])
        self.assertNotEqual(before["key"], cache.fingerprint(repo, {**environment, "packagesSha256": "new"})["key"])


if __name__ == "__main__":
    unittest.main()
