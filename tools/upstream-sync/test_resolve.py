import json
import unittest

import resolve

UPSTREAM = """## [2.0.0](https://example.invalid/compare/v1.0.0...v2.0.0) (2026-01-02)

### ✨ New Features

* **Gboard:** add A.

### 🐛 Bug Fixes

* **Gboard:** fix B.

### ✨ 新功能

* **Gboard:** 新增 A。

### 🐛 修復

* **Gboard:** 修復 B。

## [1.0.0](https://example.invalid/compare/v0.9.0...v1.0.0) (2026-01-01)

### ✨ New Features

* **Gboard:** first.
"""

FORK = """## [1.0.0-jp.1](https://example.invalid/compare/v1.0.0...v1.0.0-jp.1) (2026-01-01)

### ✨ New Features

* **Gboard:** fork only.

## [0.5.0-jp.1](https://example.invalid/compare/v0.5.0...v0.5.0-jp.1) (2025-12-01)

### ✨ New Features

* **Gboard:** orphan.

## [1.0.0](https://example.invalid/compare/v0.9.0...v1.0.0) (2026-01-01)

### ✨ New Features

* **Gboard:** first.
"""


class ChangelogTest(unittest.TestCase):
    def test_new_section_adds_placeholder_bullets_in_place(self):
        body = resolve.new_section_body(resolve.split_sections(UPSTREAM)[1], "2.0.0", True)
        self.assertEqual(
            body,
            "### ✨ New Features\n\n* **Gboard:** add A.\n" + resolve.EN_PLACEHOLDER_BULLET
            + "\n\n### 🐛 Bug Fixes\n\n* **Gboard:** fix B.\n\n### ✨ 新功能\n\n* **Gboard:** 新增 A。\n"
            + resolve.ZH_PLACEHOLDER_BULLET + "\n\n### 🐛 修復\n\n* **Gboard:** 修復 B。",
        )

    def test_skipped_upstream_releases_are_merged_into_the_new_section(self):
        upstream = (
            "## [2.0.1](x) (2026-01-03)\n\n### 🐛 Bug Fixes\n\n* fix C.\n\n### 🐛 修復\n\n* 修復 C。\n\n"
            "## [2.0.1-dev.1](x) (2026-01-03)\n\n### 🐛 Bug Fixes\n\n* dev only.\n\n"
            + UPSTREAM
        )
        sections = resolve.split_sections(upstream)[1]
        body = resolve.new_section_body(sections, "2.0.1", False, previous="1.0.0")
        self.assertEqual(
            body,
            "### ✨ New Features\n\n* **Gboard:** add A.\n\n### 🐛 Bug Fixes\n\n* fix C.\n* **Gboard:** fix B."
            "\n\n### ✨ 新功能\n\n* **Gboard:** 新增 A。\n\n### 🐛 修復\n\n* 修復 C。\n* **Gboard:** 修復 B。",
        )
        self.assertEqual(resolve.last_fork_base(FORK), "1.0.0")

    def test_missing_feature_headings_are_created(self):
        body = "### 🐛 Bug Fixes\n\n* fix.\n\n### 🐛 修復\n\n* 修復。"
        body = resolve.append_bullet(body, resolve.EN_FEATURES, "* en")
        body = resolve.append_bullet(body, resolve.ZH_FEATURES, "* zh", before=resolve.ZH_FIXES)
        self.assertEqual(
            body, "### ✨ New Features\n\n* en\n\n### 🐛 Bug Fixes\n\n* fix.\n\n### ✨ 新功能\n\n* zh\n\n### 🐛 修復\n\n* 修復。"
        )

    def test_splice_places_fork_sections_above_their_base(self):
        text = resolve.splice_changelog(UPSTREAM, FORK, "## [2.0.0-jp.1](x) (2026-01-03)", "### ✨ New Features\n\n* new.")
        headings = [line for line in text.splitlines() if line.startswith("## ")]
        self.assertEqual(
            [resolve.heading_base(h) for h in headings], ["2.0.0-jp.1", "0.5.0-jp.1", "2.0.0", "1.0.0-jp.1", "1.0.0"]
        )
        self.assertIn("* new.\n\n## [0.5.0-jp.1]", text)
        self.assertIn("* fork only.\n\n## [1.0.0]", text)
        self.assertTrue(text.endswith("* **Gboard:** first.\n"))
        self.assertNotIn("\n\n\n", text)


def resources(*entries, tail=""):
    return '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n' + "".join(entries) + tail + "</resources>\n"


def string(name, text, gap=""):
    return f'{gap}    <string name="{name}">{text}</string>\n'


MARKER = "    " + resolve.FORK_MARKER + " Keep them at the end of the file. -->\n"


class SettingsMergeTest(unittest.TestCase):
    BASE = resources(string("a", "A"), string("b", "B"), string("c", "C"))
    OURS = resources(string("a", "A"), string("f", "F"), string("b", "B"), string("c", "C"),
                     string("x", "X", gap=MARKER), string("y", "Y"))

    def test_upstream_edits_keep_fork_entries_and_fork_block_stays_last(self):
        theirs = resources(string("a", "A2"), string("b", "B"), string("c", "C"), string("n", "N"))
        merged, overridden = resolve.merge_settings_xml(self.BASE, self.OURS, theirs)
        expected = resources(string("a", "A2"), string("f", "F"), string("b", "B"), string("c", "C"),
                             string("n", "N"), string("x", "X", gap=MARKER), string("y", "Y"))
        self.assertEqual(merged, expected)
        self.assertEqual(overridden, [])

    def test_fork_edit_is_kept_when_upstream_left_it(self):
        ours = self.OURS.replace(">C<", ">C fork<")
        merged, _ = resolve.merge_settings_xml(self.BASE, ours, self.BASE)
        self.assertIn(">C fork<", merged)

    def test_both_sides_editing_english_is_a_conflict(self):
        ours = self.OURS.replace(">C<", ">C fork<")
        theirs = self.BASE.replace(">C<", ">C upstream<")
        self.assertIsNone(resolve.merge_settings_xml(self.BASE, ours, theirs))

    def test_both_sides_editing_a_translation_takes_upstream(self):
        ours = self.OURS.replace(">C<", ">C fork<")
        theirs = self.BASE.replace(">C<", ">C upstream<")
        merged, overridden = resolve.merge_settings_xml(self.BASE, ours, theirs, path=resolve.SETTINGS_JA)
        self.assertIn(">C upstream<", merged)
        self.assertEqual(overridden, ["c"])

    def test_upstream_deleting_an_entry_drops_it(self):
        theirs = resources(string("a", "A"), string("c", "C"))
        merged, _ = resolve.merge_settings_xml(self.BASE, self.OURS, theirs)
        self.assertNotIn('name="b"', merged)

    def test_missing_translations_lists_untranslated_names(self):
        en = resources(string("a", "A"), string("b", "B"), "    <plurals name=\"p\">\n        <item quantity=\"other\">%d</item>\n    </plurals>\n")
        ja = resources(string("a", "エー"))
        self.assertEqual(resolve.missing_translations(en, ja), ["b", "p"])

    def test_settings_file_pattern(self):
        self.assertTrue(resolve.SETTINGS_FILE.fullmatch(resolve.SETTINGS_EN))
        self.assertTrue(resolve.SETTINGS_FILE.fullmatch(resolve.SETTINGS_JA))
        self.assertTrue(resolve.SETTINGS_FILE.fullmatch(resolve.SETTINGS_DIR + "/values-b+zh+Hant/gboard_settings_strings.xml"))
        self.assertFalse(resolve.SETTINGS_FILE.fullmatch(resolve.SETTINGS_DIR + "/gboard_settings_text.xml"))


class BundleTest(unittest.TestCase):
    def test_render_bundle_matches_upstream_layout(self):
        upstream = '{\n  "created_at": "old",\n  "description": "old",\n  "download_url": "old",\n  "signature_download_url": "",\n  "version": "2.0.0"\n}'
        out = resolve.render_bundle(upstream, "### ✨ 新功能\n\n* 甲", "2.0.0-jp.1", "o/r", "2026-01-03T00:00:00")
        self.assertEqual(
            out,
            '{\n  "created_at": "2026-01-03T00:00:00",\n  "description": "### ✨ 新功能\\n\\n* 甲",\n'
            '  "download_url": "https://github.com/o/r/releases/download/v2.0.0-jp.1/patches-2.0.0-jp.1.mpp",\n'
            '  "signature_download_url": "",\n  "version": "2.0.0-jp.1"\n}',
        )
        self.assertEqual(json.loads(out)["version"], "2.0.0-jp.1")

    def test_gradle_version(self):
        self.assertEqual(resolve.set_gradle_version("a = 1\nversion = 2.0.0\n", "2.0.0-jp.1"), "a = 1\nversion = 2.0.0-jp.1\n")


class PostTextTest(unittest.TestCase):
    def test_posted_texts_contain_no_links_or_mentions(self):
        body = resolve.pr_body("v2.0.0", "2.0.0-jp.1", ["CHANGELOG.md"], ["gboard_patches_a_title"], ["values-ja: b"])
        notes = resolve.release_notes("v2.0.0", "2.0.0-jp.1")
        self.assertIn("- `gboard_patches_a_title`", body)
        self.assertIn("- `values-ja: b`", body)
        self.assertIn("Upstream's README is unchanged", body)
        changed = resolve.pr_body("v2.0.0", "2.0.0-jp.1", [], [], [], True)
        self.assertIn(f"`{resolve.UPSTREAM_README}` now mirrors it", changed)
        self.assertIn("`v2.0.0-jp.0`", notes)
        for text in (body, notes):
            self.assertNotRegex(text, r"https?://|www\.|@|#\d")


if __name__ == "__main__":
    unittest.main()
