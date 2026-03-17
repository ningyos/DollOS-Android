# Upstream Tracking

DollOS is a fork of GrapheneOS (AOSP 16). This document describes the process for pulling upstream GrapheneOS releases into the DollOS fork.

## Overview

GrapheneOS publishes tagged releases on a regular cadence. DollOS must track these releases to receive security patches, driver updates, and base OS improvements. Merge conflicts that touch DollOS-specific patches require human review and resolution; they cannot be automated safely.

---

## Current Process (Manual)

The manual process is used until CI automation is in place.

### Step 1 — Check for a New GrapheneOS Release

Monitor the GrapheneOS release page:

```
https://grapheneos.org/releases
```

Alternatively, watch the GrapheneOS GitHub organization for new tags on the manifest repository.

### Step 2 — Update the Manifest Branch

Edit the DollOS manifest to point to the new GrapheneOS release tag or branch. Update the `revision` attributes in `.repo/manifests/default.xml` (or the equivalent manifest file) to the new upstream tag.

### Step 3 — Sync the Source Tree

```bash
cd ~/dollos-build
repo sync -c -j$(nproc) --no-tags
```

If `repo sync` reports conflicts, stop and resolve them before proceeding.

### Step 4 — Resolve Conflicts

For each project that reports a merge conflict:

1. Navigate into the project directory.
2. Run `git status` to identify conflicting files.
3. Edit the conflicting files to integrate both the upstream change and the DollOS-specific patch.
4. Mark the conflict resolved with `git add <file>`.
5. Commit the resolution with a message referencing the upstream tag.

Conflict resolution requires human judgment. Do not automate or skip this step.

### Step 5 — Build

```bash
cd ~/dollos-build
source build/envsetup.sh
lunch aosp_bluejay-user
m -j$(nproc)
```

Fix any build errors before proceeding.

### Step 6 — Test

Flash the build to a test device and verify:

- Device boots successfully.
- Verified Boot passes (no orange or red state).
- OTA update mechanism works.
- DollOS-specific features are functional.
- No regressions in core system apps.

### Step 7 — Tag the Release

After passing tests, tag the DollOS release in the manifest repository:

```bash
git tag dollos-<upstream-tag>-<dollos-patch-version>
git push origin --tags
```

---

## Future CI Automation

The following automation is planned once the manual process is stable.

### Daily Release Check (GitHub Action)

A scheduled GitHub Action runs daily to check whether GrapheneOS has published a new release tag.

```
Trigger: schedule (cron, daily)
Steps:
  1. Fetch the latest tag from the GrapheneOS manifest repository.
  2. Compare against the last known tag stored in the repository.
  3. If a new tag is found, open a tracking issue and proceed to the next step.
```

### Auto-Build on New Release

When the daily check detects a new upstream release:

```
Steps:
  1. Update manifest revision to the new tag.
  2. Run repo sync on a hosted build runner.
  3. Attempt the build with `m -j$(nproc)`.
  4. If the build succeeds, upload the artifact for manual QA.
  5. If the build fails, notify the developer (see below).
```

### Developer Notification

The CI pipeline notifies the developer in the following situations:

- A new upstream release is detected.
- Merge conflicts are found during `repo sync` (requires human intervention).
- The build fails.
- The build succeeds and artifacts are ready for QA.

Notification channels: GitHub issue comment, email, or a webhook to a chat service (to be configured).

### Limitation

Merge conflicts between upstream changes and DollOS patches cannot be resolved automatically. When conflicts are detected, the CI pipeline stops and waits for a developer to resolve them manually using the process described in Step 4 above.
