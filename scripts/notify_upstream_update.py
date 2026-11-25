#!/usr/bin/env python3
# ==============================================================================
# Script: scripts/notify_upstream_update.py
# Purpose: Notify repository owner of upstream adblock-rust engine updates
#          (or patch conflicts) via GitHub Issue without triggering builds.
# ==============================================================================

import argparse
import subprocess


def main():
    parser = argparse.ArgumentParser(description="Create GitHub Issue notification for upstream updates.")
    parser.add_argument("--old", required=True, help="Previous submodule short hash")
    parser.add_argument("--new", required=True, help="New submodule short hash")
    parser.add_argument("--patch-failed", action="store_true", help="Whether patches/adblock-rust.patch failed to apply")
    args = parser.parse_args()

    old_hash = args.old
    new_hash = args.new

    if args.patch_failed:
        title = f"⚠️ Upstream Brave adblock-rust Patch Conflict ({new_hash})"
        search_query = f"Upstream Brave adblock-rust Patch Conflict ({new_hash})"
        body = f"""### ⚠️ Upstream Brave `adblock-rust` Patch Conflict Detected

Upstream `brave/adblock-rust` has new engine changes, but `patches/adblock-rust.patch` did **not** apply cleanly:
- **Current Pin (Kept Safe on `main`)**: `{old_hash}`
- **Conflicting Upstream Commit**: `{new_hash}`
- **Upstream Diff**: https://github.com/brave/adblock-rust/compare/{old_hash}...{new_hash}

To protect `main` from broken builds, the submodule was **not** bumped automatically.

---
### 🛠️ Next Steps
Update `patches/adblock-rust.patch` to match upstream `{new_hash}`, commit the submodule bump, and run a test build."""
    else:
        title = f"🔔 Upstream Brave adblock-rust Engine Updated ({new_hash})"
        search_query = f"Upstream Brave adblock-rust Engine Updated ({new_hash})"
        body = f"""### Upstream Brave adblock-rust Engine Update Detected

The native Brave `adblock-rust` engine (`src/`, `Cargo.toml`, or `data/`) has been updated in the upstream Brave repository:
- **Previous Commit**: `{old_hash}`
- **New Commit**: `{new_hash}`
- **Upstream Diff**: https://github.com/brave/adblock-rust/compare/{old_hash}...{new_hash}

The submodule reference and `rust_engine/Cargo.lock` in `main` have been verified against `patches/adblock-rust.patch` and committed automatically.

---
### 🛠️ Next Steps
When you are ready to produce a new build with this engine update, trigger the build workflow or prompt your AI assistant:
- **Test Build**: Say `build prerelease app`
- **Full Release**: Say `build release app`

*(Automated builds are disabled to keep you in full control of releases.)*"""

    try:
        search_res = subprocess.check_output(
            [
                "gh", "issue", "list",
                "--state", "open",
                "--search", search_query,
                "--json", "number",
                "--jq", ".[0].number"
            ],
            text=True
        ).strip()
        if search_res:
            print(f"Issue #{search_res} already exists for this update. Skipping duplicate.")
            return
    except Exception as e:
        print(f"Warning checking issues: {e}")

    try:
        subprocess.run(["gh", "issue", "create", "--title", title, "--body", body], check=True)
        print("Successfully created notification issue.")
    except Exception as e:
        print(f"Error creating notification issue: {e}")


if __name__ == "__main__":
    main()
