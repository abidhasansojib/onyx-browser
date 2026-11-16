#!/usr/bin/env python3
# ==============================================================================
# Script: scripts/generate_release_notes.py
# Purpose: Generate clean, categorized GitHub release notes from Git commits
#          with architecture guidance and cryptographic checksums.
# ==============================================================================

import argparse
import os
import re
import subprocess
import sys


def parse_args():
    parser = argparse.ArgumentParser(description="Generate categorized release notes.")
    parser.add_argument("--tag", required=True, help="Current release tag (e.g. v1.3.45)")
    parser.add_argument("--repo", default="abidhasansojib/onyx-browser", help="GitHub repository (owner/repo)")
    parser.add_argument("--output", default="release_notes.md", help="Output Markdown file path")
    parser.add_argument("--apks-dir", default=None, help="Directory containing packaged APKs & SHA256SUMS.txt")
    return parser.parse_args()


def get_git_output(cmd):
    try:
        return subprocess.check_output(cmd, text=True, stderr=subprocess.DEVNULL).strip()
    except Exception:
        return ""


def main():
    args = parse_args()
    tag = args.tag
    repo = args.repo
    output_path = args.output
    apks_dir = args.apks_dir

    # Find previous tag to establish range
    tag_list_raw = get_git_output(["git", "tag", "-l", "v*", "--sort=-v:refname"])
    tag_list = [t.strip() for t in tag_list_raw.splitlines() if t.strip()]

    # Prefer previous full release tag if current is a release, otherwise any previous tag
    prev_tag = None
    for t in tag_list:
        if t != tag:
            prev_tag = t
            break

    if prev_tag:
        rev_range = f"{prev_tag}..HEAD"
        compare_url = f"https://github.com/{repo}/compare/{prev_tag}...{tag}"
    else:
        rev_range = "HEAD~20..HEAD"
        compare_url = None

    raw_commits_output = get_git_output(["git", "log", rev_range, "--pretty=format:%h%x09%s%x09%H"])
    raw_commits = [c for c in raw_commits_output.splitlines() if c.strip()]

    # If rev_range yielded 0 commits (e.g. freshly tagged HEAD), fall back to last commit
    if not raw_commits:
        last_commit = get_git_output(["git", "log", "-n", "1", "--pretty=format:%h%x09%s%x09%H"])
        if last_commit:
            raw_commits = [last_commit]

    categories = {
        "feat": {"title": "🚀 Features & Enhancements", "items": []},
        "adblock": {"title": "🛡️ Shields & Adblocking", "items": []},
        "fix": {"title": "🐛 Bug Fixes", "items": []},
        "perf": {"title": "⚡ Performance & Stability", "items": []},
        "ui": {"title": "🎨 UI & Appearance", "items": []},
        "arch": {"title": "🔧 Architecture & Refactoring", "items": []},
        "docs": {"title": "📚 Documentation", "items": []},
        "maint": {"title": "🧹 Build & Maintenance", "items": []},
        "upstream": {"title": "🤖 Upstream Synchronization", "items": []},
        "other": {"title": "📋 Other Changes", "items": []},
    }

    upstream_syncs = []

    for line in raw_commits:
        parts = line.split("\t")
        if len(parts) < 3:
            continue
        short_hash, subject, full_hash = parts[0], parts[1], parts[2]
        link = f"[{short_hash}](https://github.com/{repo}/commit/{full_hash})"
        entry = f"* {subject} ({link})"

        m = re.match(r"^(\w+)(?:\(([^)]+)\))?!?:\s*(.+)$", subject)
        if m:
            prefix = m.group(1).lower()
            scope = m.group(2).lower() if m.group(2) else ""
            if scope == "upstream" or "auto-sync" in subject.lower():
                upstream_syncs.append((subject, link))
            elif scope in ["adblock", "shields", "filters"] or "adblock" in subject.lower() or "filter" in subject.lower():
                categories["adblock"]["items"].append(entry)
            elif prefix == "feat":
                categories["feat"]["items"].append(entry)
            elif prefix == "fix":
                if scope in ["ui", "theme", "menu", "toolbar"]:
                    categories["ui"]["items"].append(entry)
                else:
                    categories["fix"]["items"].append(entry)
            elif prefix == "perf":
                categories["perf"]["items"].append(entry)
            elif scope in ["ui", "theme", "appearance", "menu", "toolbar"]:
                categories["ui"]["items"].append(entry)
            elif prefix in ["refactor"] or scope == "arch":
                categories["arch"]["items"].append(entry)
            elif prefix == "docs":
                categories["docs"]["items"].append(entry)
            elif prefix in ["chore", "build", "ci", "test"]:
                categories["maint"]["items"].append(entry)
            else:
                categories["other"]["items"].append(entry)
        else:
            categories["other"]["items"].append(entry)

    # Process upstream syncs (collapsing identical subjects to avoid release note spam)
    if upstream_syncs:
        grouped = {}
        for subj, link in upstream_syncs:
            clean_subj = re.sub(r"\s*\([a-f0-9]+\)$", "", subj).strip()
            grouped.setdefault(clean_subj, []).append(link)
        for clean_subj, links in grouped.items():
            if len(links) > 1:
                joined_links = ", ".join(links)
                categories["upstream"]["items"].append(f"* {clean_subj} ({len(links)} updates: {joined_links})")
            else:
                categories["upstream"]["items"].append(f"* {clean_subj} ({links[0]})")

    out = []
    out.append(f"## What's Changed in {tag}\n")

    has_content = False
    for cat_key, cat in categories.items():
        if cat["items"]:
            has_content = True
            out.append(f"### {cat['title']}\n")
            out.extend(cat["items"])
            out.append("")

    if not has_content and raw_commits:
        out.append("### 📋 Changes\n")
        for line in raw_commits[:10]:
            parts = line.split("\t")
            out.append(f"* {parts[1]} ([{parts[0]}](https://github.com/{repo}/commit/{parts[2]}))")
        out.append("")

    if compare_url:
        out.append(f"**Full Changelog**: {compare_url}\n")

    # Architecture Guidance Section
    out.append("### 📱 Architecture Guide")
    out.append("| Architecture | Best Suited For |")
    out.append("| :--- | :--- |")
    out.append("| **arm64-v8a** | Recommended for 99% of modern Android phones & tablets (64-bit ARM) |")
    out.append("| **armeabi-v7a** | Legacy 32-bit Android devices |")
    out.append("| **x86_64** | Android Emulators, ChromeOS, and Intel devices |")
    out.append("| **universal** | All architectures combined in one package |")
    out.append("")

    # Checksums Section (if SHA256SUMS.txt exists in package_apks)
    if apks_dir and os.path.isdir(apks_dir):
        sha256_file = os.path.join(apks_dir, "SHA256SUMS.txt")
        if os.path.isfile(sha256_file):
            try:
                with open(sha256_file, "r", encoding="utf-8") as f:
                    sums = f.read().strip()
                if sums:
                    out.append("### 🔐 Checksums (SHA-256)")
                    out.append("```text")
                    out.append(sums)
                    out.append("```")
                    out.append("")
            except Exception:
                pass

    content = "\n".join(out)
    with open(output_path, "w", encoding="utf-8") as f:
        f.write(content)

    print(f"Successfully generated release notes at {output_path}")


if __name__ == "__main__":
    main()
