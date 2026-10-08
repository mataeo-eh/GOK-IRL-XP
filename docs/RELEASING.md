# Releasing IRL XP to the RuneLite Plugin Hub

The user-visible version lives in `runelite-plugin.properties`. Gradle reads that
same value for local JAR names. Ordinary builds do not change the version.

## Submit a release

1. Complete the pull-first git commit cycle: review and security-scan all source
   changes, run `./gradlew --gradle-user-home .gradle-user-home check`, commit with
   explicit file staging, and push `main`. Resolve upstream changes first.
2. Write a short PR description to a file outside the repository. Describe the
   resulting user behavior, relevant checks, and any limitations.
3. Run the submission workflow from this repository:

   ```sh
   scripts/submit-plugin-hub.sh /tmp/irl-xp-release.md
   ```

The command uses your existing `gh auth login` credentials and existing
`plugin-hub` fork. It pulls `main`, checks for an open `gok-irl-xp` PR, prepares
the version, runs checks, commits and pushes a version change when needed,
creates a fresh Plugin Hub checkout, updates `plugins/gok-irl-xp` to the full
source commit, and opens the upstream PR. It never merges the upstream PR.

## Version policy

- The first numbered submission is **1.0.0**.
- Each subsequent submission defaults to a **patch** increment, such as
  `1.0.0` → `1.0.1`, for fixes and maintenance.
- Choose **minor** for new compatible features and **major** for incompatible
  behavior changes:

  ```sh
  scripts/submit-plugin-hub.sh /tmp/irl-xp-release.md minor
  scripts/submit-plugin-hub.sh /tmp/irl-xp-release.md major
  ```

- Minor increments reset patch to zero; major increments reset minor and patch.
- Successful PR creation records an annotated `plugin-hub-vVERSION` tag in the
  source repository. Tags mark submissions, not approvals or live deployment.
- An open PR is reported and reused without incrementing or opening a duplicate.
  To change its source during review, commit and push the fixes, update that PR's
  manifest `commit` to the new source hash, and keep the submitted version.
- A failed submission before PR creation leaves its prepared version available
  for a retry. If PR creation succeeded but tagging failed, create/push the
  missing submission tag at the manifest's exact source commit before the next
  release. A closed PR still reserves its submitted number.
- Always use this command to open release PRs. Manually opening a GitHub PR
  bypasses the local version preparation; there is no cross-repository GitHub
  Actions credential to configure or store.

## Review and availability

Check the upstream PR's build results and respond to any reviewer requests.
Users receive the update after RuneLite maintainers approve and merge the
manifest and the Hub builds it. A pushed source commit or open PR is not yet a
live client release.

This follows the official [Plugin Hub update process](https://github.com/runelite/plugin-hub#updating-a-plugin).
