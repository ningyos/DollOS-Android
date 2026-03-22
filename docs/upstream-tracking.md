# Upstream Tracking

DollOS is based on AOSP 16. This document describes the process for pulling upstream AOSP releases into the DollOS build.

## Overview

Google publishes AOSP release tags on a regular cadence. DollOS must track these releases to receive security patches and platform improvements. Since DollOS uses `local_manifests` (not a full manifest fork), upstream updates are straightforward — only the `repo init` branch/tag needs to change, and conflicts are limited to DollOS's single `frameworks/base` patch.

---

## Current Process (Manual)

The manual process is used until CI automation is in place.

### Step 1 — Check for a New AOSP Release

Monitor the AOSP release tags:

```
https://source.android.com/docs/setup/reference/build-numbers
```

Look for new `android-16.x.x_rN` tags that include security patches for the Pixel 6a (bluejay).

### Step 2 — Update the repo init Tag

```bash
cd ~/Projects/DollOS-build
repo init -b android-16.x.x_rN
```

Replace `android-16.x.x_rN` with the new tag.

### Step 3 — Sync the Source Tree

```bash
repo sync -c -j$(nproc) --no-tags
```

If `repo sync` reports conflicts in DollOS repos, stop and resolve them before proceeding.

### Step 4 — Rebase DollOS Patches

DollOS maintains minimal patches on top of AOSP:

- **`frameworks/base`**: AI Activity and AI Stop buttons in the power menu (if using a forked branch)

If the patch does not apply cleanly to the new AOSP tag, manually resolve the conflict and update the DollOS fork.

### Step 5 — Build

```bash
cd ~/Projects/DollOS-build
source build/envsetup.sh
lunch dollos_bluejay-cur-userdebug
m -j$(nproc)
```

Fix any build errors before proceeding.

### Step 6 — Test

Flash the build to a test device and verify:

- Device boots successfully.
- Verified Boot passes (no orange or red state).
- DollOS-specific features are functional (DollOSService, SetupWizard, AI buttons in power menu).
- No regressions in core system apps.

### Step 7 — Tag the Release

After passing tests, tag the release:

```bash
git tag dollos-<aosp-tag>-<dollos-patch-version>
git push origin --tags
```

---

## Future CI Automation

The following automation is planned once the manual process is stable.

### Release Check (GitHub Action)

A scheduled GitHub Action runs periodically to check whether Google has published a new AOSP release tag.

```
Trigger: schedule (cron)
Steps:
  1. Fetch the latest android-16 tag from AOSP.
  2. Compare against the last known tag stored in the repository.
  3. If a new tag is found, open a tracking issue and proceed to the next step.
```

### Auto-Build on New Release

When the check detects a new upstream release:

```
Steps:
  1. Update repo init to the new tag.
  2. Run repo sync on a hosted build runner.
  3. Attempt the build with `m -j$(nproc)`.
  4. If the build succeeds, upload the artifact for manual QA.
  5. If the build fails, notify the developer (see below).
```

### Developer Notification

The CI pipeline notifies the developer in the following situations:

- A new upstream release is detected.
- Merge conflicts are found (requires human intervention).
- The build fails.
- The build succeeds and artifacts are ready for QA.

Notification channels: GitHub issue comment, email, or a webhook to a chat service (to be configured).

### Limitation

Merge conflicts between upstream changes and DollOS patches cannot be resolved automatically. When conflicts are detected, the CI pipeline stops and waits for a developer to resolve them manually.
