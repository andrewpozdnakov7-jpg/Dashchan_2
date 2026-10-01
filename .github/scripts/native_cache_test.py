import json
from pathlib import Path
import tempfile
import unittest

import native_cache as cache


class NativeCacheTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.repo = self.root / "repo"
        self.sources = self.root / "sources"
        self.entry = self.root / "slooop-native-cache"
        self.abis = ["arm64-v8a"]
        for name in ("dav1d", "ffmpeg", "yuv"):
            self.write(self.sources / name / "source.c", b"source")
        self.write(self.repo / "build.gradle", b"versionCode = 100\n"
                   b"tasks.register('prepareBuiltinWebmSources', Exec) {\nversion='1'\n}\n"
                   b"tasks.register('syncBuiltinWebmPlayerHeaders', Copy) {}\n")
        for name in ("Dashchan-Webm/shared-build.sh", "Dashchan-Webm/shared-prepare.sh",
                     ".github/scripts/native_cache.py"):
            self.write(self.repo / name, b"recipe")
        self.environment = {"ndk": "29", "meson": "1", "runner_image": "image"}

    @staticmethod
    def write(path, data):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)

    def key(self):
        return cache.fingerprint(self.repo, self.sources, self.abis, self.environment)

    def outputs(self):
        for name in cache.required_files(self.abis):
            category, relative = name.split("/", 1)
            target = cache.output_locations(self.repo)[category] / relative
            data = b"header/symbol"
            if name.endswith(".so"):
                data = b"\x7fELF\x02\x01" + b"\x00" * 12 + (183).to_bytes(2, "little")
            self.write(target, data)

    def test_path_independent(self):
        import shutil
        before = self.key()
        other = self.root / "another-zip"
        shutil.copytree(self.repo, other)
        self.assertEqual(before, cache.fingerprint(other, self.sources, self.abis, self.environment))

    def test_source_content_invalidates(self):
        before = self.key()
        self.write(self.sources / "ffmpeg/source.c", b"patched")
        self.assertNotEqual(before, self.key())

    def test_recipe_invalidates(self):
        before = self.key()
        self.write(self.repo / "Dashchan-Webm/shared-build.sh", b"new compiler flags")
        self.assertNotEqual(before, self.key())

    def test_abi_invalidates(self):
        before = self.key()
        self.abis = ["x86"]
        self.assertNotEqual(before, self.key())

    def test_environment_invalidates(self):
        before = self.key()
        self.environment["ndk"] = "30"
        self.assertNotEqual(before, self.key())

    def test_app_version_does_not_invalidate(self):
        before = self.key()
        gradle = self.repo / "build.gradle"
        gradle.write_text(gradle.read_text().replace("versionCode = 100", "versionCode = 110"))
        self.assertEqual(before, self.key())

    def test_absent_cache_is_miss(self):
        self.assertFalse(cache.restore(self.repo, self.entry, self.key(), self.abis))

    def test_round_trip(self):
        self.outputs()
        key = self.key()
        cache.pack(self.repo, self.entry, key, self.abis)
        self.assertTrue(cache.restore(self.repo, self.entry, key, self.abis))

    def test_wrong_key_is_miss(self):
        self.outputs()
        cache.pack(self.repo, self.entry, self.key(), self.abis)
        self.assertFalse(cache.restore(self.repo, self.entry, "different", self.abis))

    def test_damaged_library_is_miss(self):
        self.outputs()
        key = self.key()
        cache.pack(self.repo, self.entry, key, self.abis)
        with (self.entry / "payload/libraries/dav1d/arm64-v8a/libdav1d.so").open("ab") as stream:
            stream.write(b"corruption")
        self.assertFalse(cache.restore(self.repo, self.entry, key, self.abis))

    def test_wrong_elf_abi_rejected_even_with_matching_hash(self):
        self.outputs()
        key = self.key()
        cache.pack(self.repo, self.entry, key, self.abis)
        name = "libraries/dav1d/arm64-v8a/libdav1d.so"
        lib = self.entry / "payload" / name
        lib.write_bytes(lib.read_bytes()[:18] + (3).to_bytes(2, "little"))
        manifest = json.loads((self.entry / "manifest.json").read_text())
        manifest["files"][name] = cache.digest(lib)
        (self.entry / "manifest.json").write_text(json.dumps(manifest))
        self.assertFalse(cache.restore(self.repo, self.entry, key, self.abis))

    def test_missing_avfilter_is_rejected(self):
        self.outputs()
        (cache.output_locations(self.repo)["libraries"] / "ffmpeg/arm64-v8a/libavfilter.so").unlink()
        with self.assertRaises(ValueError):
            cache.pack(self.repo, self.entry, self.key(), self.abis)

    def test_malformed_manifest_is_miss(self):
        self.write(self.entry / "manifest.json", b"not json")
        self.assertFalse(cache.restore(self.repo, self.entry, self.key(), self.abis))

    def test_symlink_rejected(self):
        link = self.sources / "ffmpeg/link"
        try:
            link.symlink_to(self.sources / "dav1d/source.c")
        except OSError:
            self.skipTest("Symlink privilege unavailable")
        with self.assertRaises(ValueError):
            self.key()


if __name__ == "__main__":
    unittest.main()
