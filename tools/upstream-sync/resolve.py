"""Mechanically resolve an upstream release merge for the fork (stdlib only, run with python -I)."""

import argparse
import hashlib
import json
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

CATALOG = "patches/src/main/resources/gboard/gboard-port-product-catalog.json"
CATALOG_SHA = "patches/src/main/resources/gboard/gboard-port-product-catalog.sha256"
SETTINGS_DIR = "extensions/extension/src/main/settings-text"
SETTINGS_FILE = re.compile(re.escape(SETTINGS_DIR) + r"/values(-[^/]+)?/gboard_settings_strings\.xml")
SETTINGS_EN = f"{SETTINGS_DIR}/values/gboard_settings_strings.xml"
SETTINGS_JA = f"{SETTINGS_DIR}/values-ja/gboard_settings_strings.xml"
# README.md is the fork's Japanese README; upstream's English README is mirrored to UPSTREAM_README.
UPSTREAM_README = "docs/upstream-README.md"
SEARCH_INDEX = "extensions/extension/src/main/java/dev/jason/gboardpatches/extension/settings/search/GboardSettingsSearchIndexData.java"
# SEARCH_INDEX is generated; upstream's copy is taken and the workflow regenerates it with the fork's settings.
ALLOWED_CONFLICTS = {"CHANGELOG.md", "gradle.properties", "patches-bundle.json", "patches-list.json", CATALOG_SHA,
                     SEARCH_INDEX, "README.md", UPSTREAM_README}
EN_FEATURES = "### ✨ New Features"
ZH_FEATURES = "### ✨ 新功能"
ZH_FIXES = "### 🐛 修復"
EN_FIXES = "### 🐛 Bug Fixes"
EN_PLACEHOLDER_BULLET = "* **Gboard:** some settings strings are not translated into Japanese yet and are shown in English."
ZH_PLACEHOLDER_BULLET = "* **Gboard:** 部分設定文字尚未翻譯成日文，暫以英文顯示。"
JP_HEADING = re.compile(r"^## \[(?P<base>[^\]]+?)-jp\.\d+\]")
BLOCK = re.compile(r'(?ms)^[ \t]*<(?P<tag>string|plurals) name="(?P<name>[^"]+)"[^>]*>.*?</(?P=tag)>[ \t]*\n')
COMMENT = re.compile(r"<!--.*?-->", re.S)
FORK_MARKER = "<!-- Gboard-patches-jp: fork-only entries below."


def git(repo, *args, check=True):
    result = subprocess.run(["git", *args], cwd=repo, capture_output=True, check=False)
    if check and result.returncode != 0:
        raise SystemExit(f"git {' '.join(args)} failed: {result.stderr.decode('utf-8', 'replace')}")
    return result.stdout.decode("utf-8")


def write_text(path, text):
    Path(path).write_bytes(text.encode("utf-8"))


def split_sections(text):
    """Return (preamble, [section, ...]) where each section starts with a '## ' heading line."""
    parts = re.split(r"(?m)^(?=## )", text)
    return parts[0], [p.rstrip("\n") for p in parts[1:]]


def heading_base(section):
    match = re.match(r"## \[([^\]]+)\]", section)
    return match.group(1) if match else None


def section_body(sections, version):
    for section in sections:
        if heading_base(section) == version:
            return section.split("\n", 1)[1].strip("\n") if "\n" in section else ""
    return ""


def append_bullet(body, heading, bullet, before=None):
    lines = body.split("\n") if body else []
    if heading not in lines:
        block = [heading, "", bullet]
        if before in lines:
            at = lines.index(before)
            return "\n".join(lines[:at] + block + [""] + lines[at:])
        if heading == EN_FEATURES:
            return "\n".join(block + ([""] + lines if lines else []))
        return "\n".join(lines + ([""] if lines else []) + block)
    start = lines.index(heading) + 1
    end = next((i for i in range(start, len(lines)) if lines[i].startswith("### ")), len(lines))
    last = max((i for i in range(start, end) if lines[i].strip()), default=start - 1)
    return "\n".join(lines[: last + 1] + ([""] if last == start - 1 else []) + [bullet] + lines[last + 1 :])


def version_key(version):
    return tuple(int(part) for part in version.split("."))


def heading_order(heading):
    """Sort English headings before Chinese ones, features before fixes before anything else."""
    is_zh = bool(re.search(r"[\u3040-\u30ff\u4e00-\u9fff]", heading))
    rank = 0 if heading in (EN_FEATURES, ZH_FEATURES) else 1 if heading in (EN_FIXES, ZH_FIXES) else 2
    return (is_zh, rank)


def merge_bodies(bodies):
    """Merge changelog section bodies (newest first) into one, grouping bullets under shared headings."""
    groups = {}
    for body in bodies:
        heading = None
        for line in body.split("\n"):
            if line.startswith("### "):
                heading = line.strip()
                groups.setdefault(heading, [])
            elif heading and line.strip():
                groups[heading].append(line)
    ordered = sorted(groups, key=heading_order)
    return "\n\n".join(f"{h}\n\n" + "\n".join(groups[h]) for h in ordered if groups[h])


def released_since(upstream_sections, previous, version):
    """Bodies of upstream stable releases newer than `previous` up to `version`, newest first."""
    picked = []
    for section in upstream_sections:
        base = heading_base(section)
        if not base or not re.fullmatch(r"\d+\.\d+\.\d+", base):
            continue
        if version_key(base) <= version_key(version) and (previous is None or version_key(base) > version_key(previous)):
            picked.append(section.split("\n", 1)[1].strip("\n") if "\n" in section else "")
    return picked


def last_fork_base(fork_text):
    """Upstream version of the newest fork release in the fork's changelog, e.g. '4.0.0'."""
    for section in split_sections(fork_text)[1]:
        match = JP_HEADING.match(section)
        if match and re.fullmatch(r"\d+\.\d+\.\d+", match.group("base")):
            return match.group("base")
    return None


def new_section_body(upstream_sections, version, has_placeholders, previous=None):
    bodies = released_since(upstream_sections, previous, version) if previous else []
    body = merge_bodies(bodies) if len(bodies) > 1 else section_body(upstream_sections, version)
    if has_placeholders:
        body = append_bullet(body, EN_FEATURES, EN_PLACEHOLDER_BULLET, before=None)
        body = append_bullet(body, ZH_FEATURES, ZH_PLACEHOLDER_BULLET, before=ZH_FIXES)
    return body or f"{EN_FEATURES}\n\n* **Gboard:** sync with upstream {version}."


def splice_changelog(upstream_text, fork_text, new_heading, new_body):
    preamble, sections = split_sections(upstream_text)
    fork_only = [s for s in split_sections(fork_text)[1] if JP_HEADING.match(s)]
    upstream_bases = {heading_base(s) for s in sections}
    orphans = [s for s in fork_only if JP_HEADING.match(s).group("base") not in upstream_bases]
    merged = [f"{new_heading}\n\n{new_body}", *orphans]
    for section in sections:
        merged += [s for s in fork_only if JP_HEADING.match(s).group("base") == heading_base(section)]
        merged.append(section)
    return preamble + "\n\n".join(merged) + "\n"


def render_bundle(upstream_bundle_text, description, version, slug, created_at):
    bundle = json.loads(upstream_bundle_text)
    bundle.update(
        created_at=created_at,
        description=description,
        download_url=f"https://github.com/{slug}/releases/download/v{version}/patches-{version}.mpp",
        signature_download_url="",
        version=version,
    )
    return json.dumps(bundle, ensure_ascii=False, indent=2)


def set_gradle_version(text, version):
    updated, count = re.subn(r"(?m)^version = .*$", f"version = {version}", text)
    if count != 1:
        raise SystemExit("gradle.properties: expected exactly one 'version = ' line")
    return updated


def split_blocks(xml, path=SETTINGS_EN):
    """Return (head, {name: block}, [name, ...], tail) for the top-level string/plurals entries.

    Comments and blank lines between entries stay with the entry below them.
    """
    blocks, order, pos, head = {}, [], 0, None
    for match in BLOCK.finditer(xml):
        gap = xml[pos:match.start()]
        if head is None:
            head, gap = gap, ""
        elif COMMENT.sub("", gap).strip():
            raise SystemExit(f"{path}: unexpected text between entries: {gap.strip()[:80]!r}")
        if match.group("name") in blocks:
            raise SystemExit(f"{path}: duplicate entry {match.group('name')}")
        blocks[match.group("name")] = gap + match.group(0)
        order.append(match.group("name"))
        pos = match.end()
    return head if head is not None else xml[:pos], blocks, order, xml[pos:]


def entry_names(xml, path=SETTINGS_EN):
    return split_blocks(xml, path)[2]


def merge_settings_xml(base, ours, theirs, path=SETTINGS_EN):
    """Three-way merge of one settings text file per entry.

    Returns (xml, overridden) or None. An entry changed on both sides blocks the English authority;
    in a translation file upstream's version wins and the entry is listed in `overridden`, because
    upstream maintains those translations and re-translates entries whose English text changed.
    """
    empty = "<resources>\n</resources>\n"
    _, base_blocks, _, _ = split_blocks(base or empty, path)
    _, our_blocks, our_order, _ = split_blocks(ours, path)
    head, their_blocks, their_order, tail = split_blocks(theirs, path)
    merged, conflicts = {}, []
    for name in dict.fromkeys(their_order + our_order):
        b, o, t = (blocks.get(name) for blocks in (base_blocks, our_blocks, their_blocks))
        strip = lambda block: COMMENT.sub("", block).strip() if block is not None else None
        if strip(o) == strip(t) or strip(o) == strip(b):
            pick = t
        elif strip(t) == strip(b):
            pick = o
        else:
            conflicts.append(name)
            pick = t
        if pick is not None:
            merged[name] = pick
    if conflicts and path == SETTINGS_EN:
        print(f"{path}: entries changed on both sides: {', '.join(conflicts)}", file=sys.stderr)
        return None
    marker = next((i for i, name in enumerate(our_order) if FORK_MARKER in our_blocks[name]), len(our_order))
    order = [name for name in their_order if name in merged]
    anchor = -1
    for name in our_order[:marker]:
        if name in order:
            anchor = order.index(name)
        elif name in merged:
            anchor += 1
            order.insert(anchor, name)
    # Entries below the fork marker stay together at the end of the file, after upstream's entries.
    order += [name for name in our_order[marker:] if name in merged and name not in order]
    body = "".join(merged[name] for name in order)
    return head + body + tail, conflicts


def missing_translations(en_xml, ja_xml):
    """Names of English entries that have no Japanese translation (shown in English at runtime)."""
    have = set(entry_names(ja_xml, SETTINGS_JA))
    return [name for name in entry_names(en_xml) if name not in have]


# PR body and release notes are posted to GitHub, so they carry no upstream-derived prose
# (links, mentions or references there could notify upstream); details live in CHANGELOG.md.
def pr_body(tag, version, resolved, placeholders, overridden=(), readme_changed=False):
    lines = [
        f"Automated sync of upstream release `{tag}` into the fork, staged as `{version}`.",
        "",
        f"Upstream changes: see the `{version}` section of CHANGELOG.md in this branch.",
        "",
        "## Auto-resolved files",
        "",
        *(f"- `{path}`" for path in resolved),
        "",
        "## Japanese translation needed",
        "",
        "Shown in English until translated in `values-ja/gboard_settings_strings.xml`:",
        "",
        *([f"- `{key}`" for key in placeholders] or ["- none"]),
        "",
        "## Fork translations replaced by upstream",
        "",
        "Both sides changed these entries; upstream's text was taken. Re-apply a fork correction if it is still needed:",
        "",
        *([f"- `{key}`" for key in overridden] or ["- none"]),
        "",
        "## README",
        "",
        (f"Upstream's README changed. `{UPSTREAM_README}` now mirrors it; reflect the changes in the Japanese `README.md`."
         if readme_changed else f"Upstream's README is unchanged; `README.md` and `{UPSTREAM_README}` need no update."),
        "",
    ]
    return "\n".join(lines)


def release_notes(tag, version):
    prerelease = version.rsplit(".", 1)[0] + ".0"
    return (
        f"上流 `{tag}` の同期を端末で試すためのプレリリース版です。\n\n"
        f"タグは `v{prerelease}` ですが、同梱ファイルのバージョンは既に `{version}` になっています。"
        f"同期 PR のマージ後に `v{version}` のタグを付けて正式にリリースします。\n\n"
        f"変更内容は、同期ブランチの CHANGELOG.md にある `{version}` の項目をご覧ください。\n"
    )


def resolve(repo, tag, slug, out):
    out.mkdir(parents=True, exist_ok=True)
    if subprocess.run(["git", "rev-parse", "-q", "--verify", "MERGE_HEAD"], cwd=repo, capture_output=True).returncode != 0:
        print("No merge in progress (MERGE_HEAD missing); the merge failed before producing conflicts.", file=sys.stderr)
        return 2
    conflicted = [p for p in git(repo, "diff", "--name-only", "--diff-filter=U").splitlines() if p]
    blocked = [p for p in conflicted if p not in ALLOWED_CONFLICTS and not SETTINGS_FILE.fullmatch(p)]
    if blocked:
        write_text(out / "conflicts.txt", "\n".join(blocked) + "\n")
        print("Unresolvable conflicts:\n" + "\n".join(blocked), file=sys.stderr)
        return 2

    upstream_ver = tag.removeprefix("v")
    version = f"{upstream_ver}-jp.1"
    now = datetime.now(timezone.utc)
    root = Path(repo)

    overridden = []
    for path in [p for p in conflicted if SETTINGS_FILE.fullmatch(p)]:
        stages = []
        for stage in (":1", ":2", ":3"):
            shown = subprocess.run(["git", "show", f"{stage}:{path}"], cwd=repo, capture_output=True, check=False)
            stages.append(shown.stdout.decode("utf-8") if shown.returncode == 0 else None)
        # A file deleted on one side is a structural change upstream; leave it to a person.
        result = merge_settings_xml(*stages, path=path) if stages[1] and stages[2] else None
        if result is None:
            write_text(out / "conflicts.txt", path + "\n")
            return 2
        write_text(root / path, result[0])
        overridden += [f"{path}: {name}" for name in result[1]]

    upstream_readme = git(repo, "show", "MERGE_HEAD:README.md")
    readme_changed = git(repo, "show", f"HEAD:{UPSTREAM_README}", check=False) != upstream_readme
    write_text(root / UPSTREAM_README, upstream_readme)
    write_text(root / "README.md", git(repo, "show", "HEAD:README.md"))

    for path in ("gradle.properties", "patches-list.json", SEARCH_INDEX):
        if path in conflicted:
            git(repo, "checkout", "--theirs", "--", path)
    write_text(root / "gradle.properties", set_gradle_version((root / "gradle.properties").read_text("utf-8"), version))

    placeholders = missing_translations((root / SETTINGS_EN).read_text("utf-8"), (root / SETTINGS_JA).read_text("utf-8"))
    write_text(out / "placeholders.txt", "".join(f"{key}\n" for key in placeholders))

    upstream_changelog = git(repo, "show", "MERGE_HEAD:CHANGELOG.md")
    fork_changelog = git(repo, "show", "HEAD:CHANGELOG.md")
    body = new_section_body(split_sections(upstream_changelog)[1], upstream_ver, bool(placeholders),
                            previous=last_fork_base(fork_changelog))
    heading = f"## [{version}](https://github.com/{slug}/compare/v{upstream_ver}...v{version}) ({now:%Y-%m-%d})"
    changelog = splice_changelog(upstream_changelog, fork_changelog, heading, body)
    write_text(root / "CHANGELOG.md", changelog)

    bundle = render_bundle(git(repo, "show", "MERGE_HEAD:patches-bundle.json"), body, version, slug, f"{now:%Y-%m-%dT%H:%M:%S}")
    write_text(root / "patches-bundle.json", bundle)

    catalog = (root / CATALOG).read_bytes()
    if re.search(rb"(?m)^(<{7}|={7}|>{7})( |$)", catalog):
        raise SystemExit(f"{CATALOG} still contains conflict markers")
    json.loads(catalog)
    write_text(root / CATALOG_SHA, hashlib.sha256(catalog).hexdigest())

    resolved = ["CHANGELOG.md", "gradle.properties", "patches-bundle.json", CATALOG_SHA, "README.md", UPSTREAM_README]
    resolved += [p for p in ("patches-list.json", SEARCH_INDEX) if p in conflicted]
    resolved += [p for p in conflicted if SETTINGS_FILE.fullmatch(p)]
    git(repo, "add", "--", *resolved)
    write_text(out / "pr-body.md", pr_body(tag, version, resolved, placeholders, overridden, readme_changed))
    write_text(out / "release-notes.md", release_notes(tag, version))
    print(f"Resolved {tag} as {version}; {len(placeholders)} untranslated Japanese entr(y/ies).")
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream-tag", required=True)
    parser.add_argument("--repo-slug", required=True)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--repo", default=".", help="repository root (default: current directory)")
    args = parser.parse_args(argv)
    if not re.fullmatch(r"v\d+\.\d+\.\d+", args.upstream_tag):
        parser.error("--upstream-tag must look like v1.2.3")
    return resolve(args.repo, args.upstream_tag, args.repo_slug, args.out)


if __name__ == "__main__":
    sys.exit(main())
