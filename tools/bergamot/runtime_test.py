import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("bergamot_runtime", Path(__file__).with_name("runtime.py"))
runtime = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runtime)


class RuntimeContractTest(unittest.TestCase):
    def make_runtime(self, directory):
        directory = Path(directory)
        for name in runtime.OUTPUTS:
            (directory / name).write_bytes(b"verified fixture " + name.encode("ascii"))
        (directory / runtime.OUTPUTS[0]).write_text("function loadBergamot(Module) {}", encoding="utf-8")
        (directory / runtime.OUTPUTS[1]).write_bytes(b"\0asm\x01\0\0\0")
        expected = runtime.specs()
        manifest = {"schema": 1, "sourceRevision": expected["revision"],
                    "dependencyRevisions": expected["submodules"],
                    "sourcePatches": {name: patch["afterSha256"] for name, patch in expected["patches"].items()},
                    "emscriptenVersion": expected["emscriptenVersion"],
                    "emscriptenDebianPackage": expected["emscriptenDebianPackage"],
                    "recipeSha256": runtime.recipe_digest(),
                    "files": {name: {"sha256": runtime.digest(directory / name),
                                      "size": (directory / name).stat().st_size}
                              for name in runtime.OUTPUTS}}
        (directory / "provenance.json").write_text(json.dumps(manifest), encoding="utf-8")
        return manifest

    def verify(self, directory, lock=None):
        args = type("Args", (), {"runtime_dir": directory, "lock": lock})()
        runtime.verify(args)

    def test_matching_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            self.make_runtime(directory)
            self.verify(directory)

    def test_tampered_output(self):
        with tempfile.TemporaryDirectory() as directory:
            self.make_runtime(directory)
            (Path(directory) / runtime.OUTPUTS[1]).write_bytes(b"tampered")
            with self.assertRaisesRegex(ValueError, "checksum"):
                self.verify(directory)

    def test_wrong_source_recipe_toolchain_and_dependencies(self):
        with tempfile.TemporaryDirectory() as directory:
            manifest = self.make_runtime(directory)
            for key in ("sourceRevision", "recipeSha256", "emscriptenVersion", "dependencyRevisions",
                        "emscriptenDebianPackage", "sourcePatches"):
                with self.subTest(field=key):
                    changed = copy.deepcopy(manifest)
                    changed[key] = "unexpected"
                    (Path(directory) / "provenance.json").write_text(json.dumps(changed), encoding="utf-8")
                    with self.assertRaisesRegex(ValueError, "provenance"):
                        self.verify(directory)

    def test_expected_lock_cannot_be_substituted(self):
        with tempfile.TemporaryDirectory() as directory:
            manifest = self.make_runtime(directory)
            lock = Path(directory) / "lock.json"
            lock.write_text(json.dumps(manifest), encoding="utf-8")
            self.verify(directory, lock)
            manifest["files"][runtime.OUTPUTS[0]]["sha256"] = "0" * 64
            lock.write_text(json.dumps(manifest), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "output lock"):
                self.verify(directory, lock)

    def test_build_directories_must_be_separate(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                runtime.ensure_disjoint(directory, Path(directory) / "child")
            with self.assertRaises(ValueError):
                runtime.ensure_disjoint(directory, directory)

    def test_wrong_git_source_is_rejected(self):
        with patch.object(runtime, "git", return_value="unexpected"):
            with self.assertRaisesRegex(ValueError, "revision"):
                runtime.check_sources()

    def test_all_source_revisions_are_full_pins(self):
        expected = runtime.specs()
        self.assertRegex(expected["revision"], r"^[0-9a-f]{40}$")
        for revision in expected["submodules"].values():
            self.assertRegex(revision, r"^[0-9a-f]{40}$")

    def test_integration_models_match_android_download_pins(self):
        models = json.loads((runtime.HERE / "models.json").read_text(encoding="utf-8"))
        java = (runtime.ROOT / "src/com/mishiranu/dashchan/content/translation/TranslationModel.java").read_text(
            encoding="utf-8")
        for model in models["directions"]:
            self.assertIn(model["id"], java)
            self.assertIn(model["prefix"], java)
            for file in model["files"]:
                for field in ("name", "output", "sha256", "compressedSha256"):
                    self.assertIn(file[field], java)
                for field in ("size", "compressedSize"):
                    self.assertIn(str(file[field]), java.replace("_", ""))

    def test_source_compatibility_refuses_unknown_edits(self):
        with tempfile.TemporaryDirectory() as directory:
            relative = "inference/marian-fork/src/CMakeLists.txt"
            file = Path(directory) / relative
            file.parent.mkdir(parents=True)
            file.write_text("keep this unrelated edit\n", encoding="utf-8")
            with patch.object(runtime, "SOURCE", Path(directory)):
                with self.assertRaisesRegex(ValueError, "Refusing"):
                    runtime.apply_source_compatibility()
            self.assertEqual(file.read_text(encoding="utf-8"), "keep this unrelated edit\n")


if __name__ == "__main__":
    unittest.main()
