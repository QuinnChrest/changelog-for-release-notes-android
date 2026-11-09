---
name: release
description: Make a GitHub release of Changelog for Android from main, as AGENTS.md's Releases section says (Quinn's call, 2026-11-08). Use when asked to release, cut, ship or publish a version, or after a push to main that needs its release. Picks the number by semantic versioning, builds and checks the signed APK, drafts the notes for Quinn's approval, tags the commit, publishes, and takes the APK off the release before so only the latest can be downloaded.
---

# Release

Every push to `main` is a release: a git tag, a GitHub release named for it with notes in plain words, and the
signed `Changelog-x.y.z.apk`. Only the newest release offers an APK; older ones keep their notes and tag. The
tags are the history: `git log vA..vB` and GitHub's `compare/vA...vB` show what changed between any two.

Run each step and stop on any failure. Publishing is outward-facing: nothing is tagged, pushed or published
until Quinn has approved the number and the notes in chat.

## 1. Where things stand

```bash
git switch main && git pull --ff-only
git status --short                                   # must be clean
git describe --tags --abbrev=0                       # the last release, e.g. v1.1.0
git log --oneline <last>..HEAD                       # what this release holds
gh release list --limit 3
```

If there are no commits since the last tag, there is nothing to release: say so and stop. Release only what is on
`origin/main`. `main` is protected: work reaches it only by a pull request whose `build` check passes, so
unmerged work goes through a PR first, never a push. Tags aren't covered by that rule; pushing one is fine.

## 2. The number

From the commits since the last tag (read their messages, and the diff where a message isn't clear):

- **Major** (2.0.0): a break. Quinn and Jamie's call, never assumed: ask.
- **Minor** (1.2.0): anything new reaches the person using the app, a feature or a change they see. The patch
  goes back to 0. Features and fixes together are a minor.
- **Patch** (1.1.1): fixes alone, nothing they see or do changes.

Changes to CI, docs, tests or tooling alone reach no one: they still get a release (every push to main does), as
a patch.

The number is the release's only: the tag `vX.Y.Z` and the APK's file name. Never touch `versionName` or
`versionCode`; `app/build.gradle.kts` stamps them.

## 3. Test and build

```bash
./gradlew :kit:test :app:assembleDebug :app:lintDebug --console=plain
./gradlew :app:assembleRelease --console=plain
```

`signing.properties` and its keystore must be at the repo's root (they're Quinn's and never in git); without them
the APK comes out unsigned. Stop and say so.

Check the signature is the one users already have:

```bash
BT=$(ls -d "$LOCALAPPDATA/Android/Sdk/build-tools/"* | sort -V | tail -1)
"$BT/apksigner.bat" verify --print-certs app/build/outputs/apk/release/app-release.apk | grep -i "SHA-256"
```

It must be `7d95e717004895fb44dc72e0ee3a1e04309c72e8d3c5045817789a19a30f9c5d`. Anything else won't install over the
last release: stop and tell Quinn.

Copy the APK to the session's scratchpad as `Changelog-X.Y.Z.apk`.

## 4. The notes

Write them for someone using the app, not a developer, in the layout of the releases before
(`gh release view <last> --json body --jq .body`):

- `## What's new`: a bold lead and one or two sentences for each thing they'll notice. Skip what they won't.
- `## What's fixed`: one line each, saying what used to go wrong.
- `## Worth knowing`: what it needs (a token scope, Release Notes' version), what it costs (battery, size,
  lookups), anything that changes by itself on update. Leave the section out when there's nothing.
- `## Install`: as the last release's, with the new file name and "installs over <last>".
- The signing certificate's SHA-256 line.
- Last: `Every change since <last>: https://github.com/QuinnChrest/changelog-for-release-notes-android/compare/<last>...vX.Y.Z`

No pictures. Never a real note, version, place or account detail. A release with no change anyone sees says so in
one line ("Nothing changes in the app: …") above Install.

Write the notes to the scratchpad, then show Quinn the number and the notes in chat and wait for a yes.

## 5. Tag and publish

```bash
git tag -a vX.Y.Z -m "Changelog X.Y.Z" <commit>      # the commit the APK was built from: HEAD of main
git push origin vX.Y.Z
gh release create vX.Y.Z <scratchpad>/Changelog-X.Y.Z.apk --title "Changelog X.Y.Z" --notes-file <scratchpad>/notes-X.Y.Z.md --verify-tag
gh release view vX.Y.Z --json assets --jq '.assets[] | .name + " " + .state'   # "uploaded"
```

## 6. Only the latest offers an APK

Take the APK off the release before, and say where to go instead. Its tag and notes stay.

```bash
gh release delete-asset <last> Changelog-<last without v>.apk --yes
gh release view <last> --json body --jq .body > <scratchpad>/old-notes.md
```

Put this line at the very top of `old-notes.md`, then `gh release edit <last> --notes-file <scratchpad>/old-notes.md`:

```
> Superseded: install the [latest release](https://github.com/QuinnChrest/changelog-for-release-notes-android/releases/latest). This one's APK has been taken down; its code is at the tag.
```

Any release older than that should already be done. If `gh release list` shows another with an APK still on it,
ask Quinn before taking it off: deleting an asset can't be undone, though the build can be made again from its tag.

## 7. Report

The release's link, its number and why, the certificate check, and what was taken off the release before. Quinn's
phone runs a debug build of the same code (see memory), so nothing is installed there by this skill.
