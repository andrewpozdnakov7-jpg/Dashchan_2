import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest import mock

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
        for name in ("dav1d", "ffmpeg"):
            self.write(self.sources / name / "source.c", b"source")
        self.write(self.repo / "build.gradle", b"versionCode = 100\n"
                   b"tasks.register('prepareBuiltinWebmSources', Exec) {\nversion='1'\n}\n"
                   b"tasks.register('syncBuiltinWebmPlayerHeaders') {}\n")
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
                machine = cache.MACHINES[name.split('/')[2]]
                data = b"\x7fELF\x02\x01" + b"\x00" * 12 + machine.to_bytes(2, "little")
            self.write(target, data)

    def test_path_independent(self):
        import shutil
        before = self.key()
        other = self.root / "another-zip"
        shutil.copytree(self.repo, other)
        self.assertEqual(before, cache.fingerprint(other, self.sources, self.abis, self.environment))

    def test_player_uses_only_ffmpeg_frame_converter(self):
        repo = Path(__file__).resolve().parents[2]
        video = (repo / "jni/src/player/player_video_software.c").read_text(encoding="utf-8")
        self.assertIn("sws_getCachedContext(", video)
        self.assertIn("sws_scale(", video)
        self.assertNotIn("I420ToABGR", video)
        self.assertNotIn("useLibyuv", video)
        for name in ("src/com/mishiranu/dashchan/media/VideoPlayer.java",
                     "jni/src/player/Android.mk", "build.gradle", "gradle.properties",
                     "Dashchan-Webm/build.gradle", "Dashchan-Webm/shared-prepare.sh",
                     "Dashchan-Webm/shared-build.sh"):
            text = (repo / name).read_text(encoding="utf-8")
            self.assertNotIn("libyuv", text.lower(), name)
            self.assertNotIn('"yuv"', text, name)
        self.assertFalse((repo / "jni/src/player/yuv/Android.mk").exists())

    def test_source_content_invalidates(self):
        before = self.key()
        self.write(self.sources / "ffmpeg/source.c", b"patched")
        self.assertNotEqual(before, self.key())

    def test_jni_uses_generated_headers_without_source_tree_writes(self):
        repo = Path(__file__).resolve().parents[2]
        build = (repo / "build.gradle").read_text(encoding="utf-8")
        makefile = (repo / "jni/src/player/ffmpeg/Android.mk").read_text(encoding="utf-8")
        self.assertIn('DASHCHAN_WEBM_EXTERNAL=${builtinWebmExternal.absolutePath}', build)
        self.assertIn('variant.lifecycleTasks.registerPreBuild(', build)
        self.assertIn("'libavutil/ffversion.h'", build)
        self.assertNotIn('afterEvaluate', build)
        self.assertNotIn("into file('jni/", build)
        self.assertIn('$(DASHCHAN_WEBM_EXTERNAL)/ffmpeg/include/$(TARGET_ARCH_ABI)', makefile)
        self.assertIn('$(error ffmpeg8 requires', makefile)
        self.assertFalse((repo / 'jni/src/player/ffmpeg8').exists())
        self.assertTrue((repo / 'jni/src/player/ffmpeg/include/arm64-v8a/libavcodec/avcodec.h').is_file())

    def test_version_header_is_required_in_native_cache(self):
        self.outputs()
        cache.pack(self.repo, self.entry, self.key(), self.abis)
        header = self.entry / 'payload/external/ffmpeg/include/arm64-v8a/libavutil/ffversion.h'
        header.unlink()
        self.assertFalse(cache.restore(self.repo, self.entry, self.key(), self.abis))

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

    def test_legacy_converter_outputs_are_not_packed(self):
        self.outputs()
        locations = cache.output_locations(self.repo)
        self.write(locations["libraries"] / "yuv/arm64-v8a/libyuv.so", b"old library")
        self.write(locations["external"] / "yuv/include/libyuv.h", b"old header")
        key = self.key()
        cache.pack(self.repo, self.entry, key, self.abis)
        files = json.loads((self.entry / "manifest.json").read_text())["files"]
        self.assertFalse(any("yuv" in name for name in files))
        self.assertFalse((self.entry / "payload/libraries/yuv").exists())
        self.assertFalse((self.entry / "payload/external/yuv").exists())
        self.assertTrue(cache.restore(self.repo, self.entry, key, self.abis))

    def test_universal_outputs_can_be_restored_between_split_builds(self):
        self.abis = sorted(cache.MACHINES)
        self.outputs()
        key = self.key()
        cache.pack(self.repo, self.entry, key, self.abis)
        for abi in self.abis:
            # A split's JNI build must never change the frozen universal payload.
            library = cache.output_locations(self.repo)["libraries"] / f"ffmpeg/{abi}/libavcodec.so"
            expected = library.read_bytes()
            library.write_bytes(b"changed during build")
            self.assertTrue(cache.restore(self.repo, self.entry, key, self.abis))
            self.assertEqual(expected, library.read_bytes())

    def test_universal_cache_is_not_silently_a_single_abi_entry(self):
        self.abis = sorted(cache.MACHINES)
        self.outputs()
        key = self.key()
        cache.pack(self.repo, self.entry, key, self.abis)
        self.assertFalse(cache.restore(self.repo, self.entry, key, ["arm64-v8a"]))

    def test_required_restore_fails_closed(self):
        with mock.patch('sys.argv', ['native_cache.py', 'restore-required']), \
                mock.patch.dict('os.environ', {'NATIVE_ABIS': 'arm64-v8a',
                    'CI_NATIVE_SOURCES': str(self.sources), 'RUNNER_TEMP': str(self.root)}), \
                mock.patch.object(cache, 'environment', return_value={}), \
                mock.patch.object(cache, 'fingerprint', return_value='test-key'):
            with self.assertRaisesRegex(SystemExit, 'Validated native outputs required'):
                cache.main()

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


class FfmpegGitFallbackTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.upstream = self.root / "upstream"
        self.parent = self.root / "application"
        for directory in (self.upstream, self.parent):
            directory.mkdir()
            self.git(directory, "init", "-q")
        (self.parent / "application.txt").write_text("synthetic application\n")
        self.commit(self.parent)
        self.target = self.parent / "build/ffmpeg"
        repo = Path(__file__).resolve().parents[2]
        source = (repo / "Dashchan-Webm/shared-prepare.sh").read_text(encoding="utf-8")
        function = "prepare_git_source() {" + source.split("prepare_git_source() {", 1)[1].split(
            "\nsources_dav1d=", 1)[0]
        self.script = self.root / "prepare-fixture.sh"
        self.script.write_text('set -e -o pipefail\n' + function + '\nprepare_git_source "$@"\n', encoding="utf-8")
        self.bash = os.environ.get("SLOOOP_TEST_BASH") or shutil.which("bash")
        if not self.bash:
            self.skipTest("Bash unavailable; exercised by Linux CI")

    def git(self, directory, *arguments):
        return subprocess.check_output(["git", "-C", str(directory), *arguments], text=True).strip()

    def commit(self, directory):
        self.git(directory, "add", ".")
        self.git(directory, "-c", "user.name=Release Fixture", "-c", "user.email=test@example.invalid",
                 "commit", "-qm", "Synthetic release fixture")
        return self.git(directory, "rev-parse", "HEAD")

    def prepare(self, release="8.1.3", archive_version=None, wrong_commit=False, stale=False):
        (self.upstream / "RELEASE").write_text(release + "\n")
        if archive_version is not None:
            (self.upstream / "VERSION").write_text(archive_version + "\n")
        revision = self.commit(self.upstream)
        self.git(self.upstream, "tag", "n8.1.3")
        arguments = ["ffmpeg", "8.1.3", "refs/tags/n8.1.3", "0" * 40 if wrong_commit else revision,
                     self.upstream.as_posix(), self.target.as_posix()]
        if stale:
            self.target.mkdir(parents=True)
            (self.target / ".dashchan-version").write_text("ffmpeg:8.1.3\n")
            (self.target / "RELEASE").write_text("8.1.3\n")
            text = self.script.read_text().replace('prepare_git_source "$@"',
                'download_and_extract() { return 1; }\nprepare_source_with_git_fallback "$@"')
            self.script.write_text(text)
            arguments[2:2] = ["https://example.invalid/not-requested", "synthetic-checksum"]
        result = subprocess.run([self.bash, self.script.as_posix(), *arguments],
                                cwd=self.parent, text=True, capture_output=True)
        return result

    def test_git_snapshot_inside_application_has_release_version_file(self):
        result = self.prepare()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("8.1.3\n", (self.target / "VERSION").read_text())
        self.assertEqual("ffmpeg:8.1.3\n", (self.target / ".dashchan-version").read_text())
        # The ancestor is a real unrelated Git repository, as on a CI checkout.
        self.assertEqual(self.git(self.parent, "rev-parse", "HEAD"), self.git(self.target, "rev-parse", "HEAD"))

    def test_wrong_pinned_commit_does_not_publish_version_or_marker(self):
        self.assertNotEqual(0, self.prepare(wrong_commit=True).returncode)
        self.assertFalse(self.target.exists())

    def test_old_prepared_git_snapshot_without_version_is_revalidated(self):
        result = self.prepare(stale=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("8.1.3\n", (self.target / "VERSION").read_text())

    def test_wrong_release_cannot_be_relabelled_as_the_pin(self):
        self.assertNotEqual(0, self.prepare(release="8.1.2").returncode)
        self.assertFalse(self.target.exists())

    def test_conflicting_existing_version_cannot_be_overwritten(self):
        self.assertNotEqual(0, self.prepare(archive_version="other-source").returncode)
        self.assertFalse(self.target.exists())


if __name__ == "__main__":
    unittest.main()
