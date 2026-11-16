#!/usr/bin/env python3
# ==============================================================================
# Script: scripts/notify_upstream_update.py
# Purpose: Notify repository owner of upstream adblock-rust engine updates
#          via GitHub Issue without triggering automated builds.
# ==============================================================================

import argparse
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description="Create GitHub Issue notification for upstream updates.")
    parser.add_argument("--old", required=True, help="Previous submodule short hash")
    parser.add_argument("--new", required=True, help="New submodule short hash")
    args = parser.parse_args()

    old_hash = args.old
    new_hash = args.new
    title = f"🔔 Upstream Brave adblock-rust Engine Updated ({new_hash})"

    # Check if issue already exists
    try:
        search_res = subprocess.check_output(
            [
                "gh", "issue", "list",
                "--state", "open",
                "--search", f"Upstream Brave adblock-rust Engine Updated ({new_hash})",
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

    body = f"""### Upstream Brave adblock-rust Engine Update Detected

The native Brave `adblock-rust` engine has been updated in upstream Brave repository:
- **Previous Commit**: `{old_hash}`
- **New Commit**: `{new_hash}`
- **Upstream Diff**: https://github.com/brave/adblock-rust/compare/{old_hash}...{new_hash}

The submodule reference in `main` has been committed automatically.

---
### 🛠️ Next Steps
When you are ready to produce a new build with this engine update, trigger the build workflow or prompt your AI assistant:
- **Test Build**: Say `build prerelease app`
- **Full Release**: Say `build release app`

*(Automated builds are disabled to keep you in full control of releases.)*"""

    try:
        subprocess.run(["gh", "issue", "create", "--title", title, "--body", body], check=True)
        print("Successfully created notification issue.")
    except Exception as e:
        print(f"Error creating notification issue: {e}")


if __name__ == "__main__":
    main()
