# Change-based checks

GitHub CI selects suites from the changed paths. It compares a pull request with its merge base and a push with the previous commit from the event. Renames include both source and destination paths, and deletions count as changes.

| Changed files | Required work |
|---|---|
| Android source, resources, Gradle configuration | Android Python regressions, debug/release builds, Kotlin tests, lint, manifest and dependency checks, release APK size |
| Vercel, auth or Redis source and backend tests | Backend types, tests, Vercel configuration, bundles and entry check |
| Shared backend code, backend dependencies, build files or SAM template | Backend checks plus SAM build regressions and AWS template validation/build |
| Repository tooling | Python tool regressions; app guards also select Android |
| Markdown/docs and design reference images | Patch whitespace and the separate credential scan |
| Workflows, shared verification/security scripts, unknown files or unavailable Git history | Full suite |

Manual **Run workflow** selects the full suite. CI checks related components together because changing a shared API can break a caller in an untouched file. It does not try to map individual functions to tests.

The Android job runs `scripts/test_wake_trial.py` even when `FRIDAY_SKIP_TOOL_TESTS=1`. These tests check app source, debug components and manifest boundaries, so app-only changes need them. The separate tooling job runs the full Python suite when tooling changes. Changes to `scripts/wake_trial_checks.py` also select Android verification because the release manifest guard imports it.

**Required checks** reports one final result. It fails if selection fails or a selected job fails, gets cancelled or gets skipped. Set that status and the separate **credentials** status as required checks in GitHub's branch rule. Existing required status names may need updating. This repository change does not alter GitHub branch settings.

The credential workflow still scans committed history on pushes and pull requests. The local `.githooks` still protect commits and pushes. Path selection never skips those checks.

Vercel's ignored-build command skips an app/docs-only change. Backend code, configuration, test and dependency changes deploy as before. Missing history, a first deployment, and a redeploy of the same commit run the build. Vercel executes `npm run verify` as its build command, so failed backend tests stop that deployment. Its Git integration runs independently of GitHub's status checks; branch protection controls merges, and Vercel's own build controls deployment success.

Tests in `scripts/test_ci_changes.py` cover scope selection and renamed/deleted files. `scripts/test_ci_result.py` verifies that cancelled, skipped or missing required jobs cannot report success. Backend script tests cover deployment skipping, unavailable history and verification-command enforcement. The workflow uses existing pinned GitHub actions and standard Python/Node tooling; it requires no additional GitHub secret.

Each CI job has its own runner. The AWS job installs dependencies, runs `npm run test:sam`, then builds the SAM artifact. Its Makefile generates the bundle before copying `handler.js`; it needs no output from the backend job. SAM regressions run the real bundler in temporary workspaces with no existing `dist` directory and check that compilation failures cannot package a stale handler. Only the AWS job runs these Make tests. Vercel keeps its Node-only verification.

Phone/debug helpers and recording evaluation tools live in ignored `.local/tools/` on the owner's machine. Their helper tests run there too. CI keeps the credential, change-selection, dependency, manifest and APK checks in `scripts/`; it does not import local tools or require a connected phone. Moving a tool out of the working tree does not remove its earlier versions from Git history.
