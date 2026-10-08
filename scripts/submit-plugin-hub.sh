#!/usr/bin/env bash
# Submit a tested source commit through RuneLite's documented manifest PR flow.
# Uses the existing gh login; credentials never enter a file or the command line.
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
    echo 'Usage: scripts/submit-plugin-hub.sh PR_BODY_FILE [patch|minor|major]' >&2
    exit 2
fi
body_file=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
[[ -f "$body_file" ]] || { echo 'PR body file does not exist' >&2; exit 2; }
bump=${2:-patch}
case "$bump" in patch|minor|major) ;; *) echo 'Choose patch, minor or major' >&2; exit 2 ;; esac
repo_root=$(git rev-parse --show-toplevel)
cd "$repo_root"
[[ $(git symbolic-ref --short HEAD) == main ]] || { echo 'Run from main' >&2; exit 1; }
[[ -z $(git status --porcelain) ]] || { echo 'Commit and security-scan source changes first' >&2; exit 1; }
git fetch origin --tags
git pull --ff-only
[[ $(git rev-parse HEAD) == $(git rev-parse origin/main) ]] || {
    echo 'Push the reviewed source commits before submitting' >&2; exit 1;
}

# gh emits only explicitly selected fields. User API: use login, ignore profile
# details. PR list: use url only; an existing PR is reused without incrementing.
owner=$(gh api user --jq .login)
existing=$(gh pr list --repo runelite/plugin-hub --author "$owner" --head gok-irl-xp \
    --state open --json url --jq '.[].url')
if [[ -n "$existing" ]]; then
    echo "An update PR is already open: $existing"
    echo 'Update its manifest commit for review fixes; keep the submitted version.'
    exit 0
fi
# The fork must already exist and belong to the authenticated user. Inspect the
# typed parent.full_name field; ignore unrelated repository metadata.
[[ $(gh api "repos/$owner/plugin-hub" --jq .parent.full_name) == runelite/plugin-hub ]] || {
    echo 'Create a plugin-hub fork for the authenticated account first' >&2; exit 1;
}

gradle=(./gradlew --gradle-user-home .gradle-user-home)
version=$("${gradle[@]}" -q printPluginVersion)
# A successful submission gets a tag. Without one, this version is prepared but
# not submitted, so the first 1.0.0 and failed submission retries keep their number.
if git show-ref --verify --quiet "refs/tags/plugin-hub-v$version"; then
    "${gradle[@]}" bumpPluginVersion "-PreleaseBump=$bump"
    version=$("${gradle[@]}" -q printPluginVersion)
    "${gradle[@]}" check
    git diff --check
    # This task changes only parsed metadata's version; source was scanned and
    # committed before this workflow. Stage that file explicitly, preserving hooks.
    git add runelite-plugin.properties
    git commit -m "Prepare IRL XP $version for Plugin Hub"
    git push origin main
else
    "${gradle[@]}" check
fi
source_commit=$(git rev-parse HEAD)

# A fresh clone isolates the submission branch from any existing fork checkout.
# All git file arguments below are relative to their active working directory.
release_temp=$(mktemp -d)
trap 'rm -rf "$release_temp"' EXIT
cd "$release_temp"
gh repo clone "$owner/plugin-hub" plugin-hub -- --depth 1
cd plugin-hub
git remote add upstream https://github.com/runelite/plugin-hub.git
git fetch origin '+refs/heads/*:refs/remotes/origin/*' --depth 1
git fetch upstream master --depth 1
git switch -c gok-irl-xp upstream/master
[[ -f plugins/gok-irl-xp ]] || { echo 'Plugin Hub manifest is missing' >&2; exit 1; }
# The Hub manifest schema is repository + full source commit. This plugin needs
# no optional manifest fields; write exactly the documented required properties.
printf 'repository=https://github.com/mataeo-eh/GOK-IRL-XP.git\ncommit=%s\n' "$source_commit" > plugins/gok-irl-xp
git diff --check
git add plugins/gok-irl-xp
git commit -m "Update gok-irl-xp to $version"
# Lease protection prevents silently overwriting a concurrent fork update.
git push --force-with-lease -u origin gok-irl-xp
gh pr create --repo runelite/plugin-hub --base master --head "$owner:gok-irl-xp" \
    --title "Update gok-irl-xp to $version" --body-file "$body_file"

# Tag only once creation succeeds. This reserves the version even if the PR is
# later closed, while review fixes stay on the same version and in the same PR.
cd "$repo_root"
git tag -a "plugin-hub-v$version" "$source_commit" -m "IRL XP $version submitted to Plugin Hub"
git push origin "refs/tags/plugin-hub-v$version"
