"""Signing and trusted-cache routing contracts; no APK build or signing secrets."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]


class TranslatorWorkflowTest(unittest.TestCase):
    def test_github_only_signing_survives_a_skipped_optional_ancestor(self):
        source = (ROOT / ".github/workflows/android-signed-candidate.yml").read_text(encoding="utf-8")
        sign = source[source.index("\n  sign:\n"):]
        expression = " ".join(re.search(r"    if: >-\n((?:      .+\n)+)", sign)[1].split())
        expected = {"always()", "!cancelled()", "needs.build.result == 'success'", "github.ref == 'refs/heads/master'"}
        self.assertEqual(set(expression.split(" && ")), expected)
        # With an explicit status function, the skipped ancestor no longer adds
        # an implicit success() gate. The successful build and master guards remain.
        for ancestor in ("success", "skipped", "failure", "cancelled"):
            for build in ("success", "skipped", "failure", "cancelled"):
                for cancelled in (True, False):
                    for branch in ("refs/heads/master", "refs/pull/1/merge"):
                        values = {"always()": True, "!cancelled()": not cancelled,
                                  "needs.build.result == 'success'": build == "success",
                                  "github.ref == 'refs/heads/master'": branch == "refs/heads/master"}
                        result = all(values[x] for x in expression.split(" && "))
                        self.assertEqual(result, build == "success" and not cancelled and branch == "refs/heads/master")
        self.assertIn("name: release-signing", sign)
        self.assertIn("if [ \"$REF_PROTECTED\" != 'true' ]", source)
        self.assertIn("Signing confirmation does not match SIGN CANDIDATE", source)

    def test_real_fdroid_signing_forces_an_independent_source_build(self):
        source = (ROOT / ".github/workflows/android-signed-candidate.yml").read_text(encoding="utf-8")
        job = source[source.index("\n  bergamot:\n"):source.index("\n  build:\n")]
        self.assertIn("inputs.include_fdroid_splits", job)
        self.assertIn("force_source_build: true", job)

    def test_cache_is_exact_checked_and_only_written_from_protected_master(self):
        source = (ROOT / ".github/workflows/bergamot-runtime.yml").read_text(encoding="utf-8")
        self.assertNotIn("restore-keys:", source)
        for name in ("Package independently verified translator cache", "Save trusted translator cache"):
            step = source[source.index("      - name: " + name):].split("\n      - name:", 1)[0]
            self.assertIn("github.ref == 'refs/heads/master'", step)
            self.assertIn("github.ref_protected", step)
            self.assertIn("github.event_name != 'pull_request'", step)
        self.assertEqual(source.count("if: ${{ !inputs.force_source_build }}"), 2)
        self.assertIn("--rebuilt build/bergamot/rebuilt", source)
        self.assertIn("diff -r build/bergamot/runtime build/bergamot/rebuilt", source)
        upload = source[source.index("      - name: Upload source-built engine and provenance"):]
        self.assertNotIn("if: always()", upload)
        self.assertIn("if-no-files-found: error", upload)


if __name__ == "__main__":
    unittest.main()
