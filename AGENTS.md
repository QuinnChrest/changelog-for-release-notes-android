# Changelog for Android

Changelog is a fast way to send notes into Release Notes
(`jthingelstad/yvn-release-notes`), the daily journal where each day is a
release of you. It talks only to Release Notes' public `/v1` API, designed in
that repo's `docs/API.md`. That document is the contract: when the app needs
something the API doesn't do, change the design there first (an issue on
yvn-release-notes, or tell Jamie), never work around it here.

Changelog for iPhone (`jthingelstad/changelog-ios`) is the reference client.
This app is its Android pair: same package name
(`com.yourversionnumber.changelog`), same behaviour, same words where it can.
The client guide at https://notes.yourversionnumber.com/guide/api/clients/
says how every client behaves.

`CLAUDE.md` points here. Keep the rules in this file.

## Layout

- `kit/` is a plain Kotlin (JVM) module with everything that isn't a screen
  and doesn't need Android: `Api` (every `/v1` call, `ApiException` with the
  API's stable codes), `Outbox` (the offline queue, one JSON file),
  `Hashtags` and `TagRow` (a port of Release Notes'
  `packages/domain/tags.ts`, by way of the iPhone app's `Hashtags.swift`;
  keep all three in step), `TokenFormat`, and `YourVersionNumber` (only for
  Changelog's own build number). It has its own tests: `./gradlew :kit:test`.
- `app/` is the Compose app: `AppGraph` (in `ChangelogApp.kt`) holds the
  shared state for the screens and for `SendWorker`; `AppModel` is the
  screens' ViewModel; `TokenStore` keeps the token sealed by an Android
  Keystore key; `ui/` has the screens (sign-in, Log, Settings).
- `SendWorker` sends the Outbox through WorkManager with a network
  constraint, oldest first, backing off while Release Notes can't be reached
  and waiting out `Retry-After`.
- `MainActivity` takes `ACTION_SEND` text and links from other apps into the
  draft.

## Rules

- Never commit real notes, real versions, real account details, tokens, or
  screenshots of a real account. Examples use the dev server's fictional
  account.
- Never test against production. Run the Release Notes dev server
  (`tools/dev-server.ts`, port 8790) and point a debug build at it ("Dev
  server" on the sign-in screen). No agent, script or test writes to a real
  account. Only debug builds allow plain HTTP, and only to the dev server's
  addresses (`app/src/debug/res/xml/network_security_config.xml`).
- Never log, print or put in an error message a token or a note's text.
- Every write goes through the Outbox with an idempotency key made once,
  when the write is saved, so a resend never makes a second note. Send
  `written_at` with every note.
- Versions and reminders come from the server (`GET /v1/me`,
  `GET /v1/upcoming/reminders`), never worked out on the phone.
- Act on an error's `code`, show its `message`. On a 401, stop sending,
  keep the queue and ask for a new token.
- The app reads back only notes this token wrote, and the activity log. It
  never asks for `notes:read`.
- Sign out sends `DELETE /v1/token` when the owner chooses to revoke.
- A notification never carries a note's words.
- Location is foreground only, one `getCurrentLocation` when the owner asks,
  once per draft: each Here costs Release Notes a lookup, 100 a day.
- No backups: the token's Keystore key can't leave the phone, and the queue
  holds words.
- Dependencies are AndroidX and Kotlin's own libraries only. Ask before
  adding anything else.

## Building

JDK 17 or later, Android SDK 37.

```sh
./gradlew :kit:test            # the kit's tests, no Android needed
./gradlew :app:assembleDebug   # a debug APK
./gradlew :app:installDebug    # onto a running emulator or phone
```

Versions count from Changelog for Android's first day, 2026-10-29, by Your
Version Number's arithmetic (decades.years.days, the day in Chicago), like
Release Notes' and the iPhone app's own. `app/build.gradle.kts` stamps it as
`versionName`, the commit count as `versionCode`, and the short commit as
`BuildConfig.COMMIT`, which the foot of Settings shows. Never set them by
hand.

## The dev server

In a clone of `jthingelstad/yvn-release-notes`, with Node 24:

```sh
npm ci
npm run build
node --import tsx tools/dev-server.ts --fake-places
```

On Windows, clone it with `git clone -c core.autocrlf=false` (its site
build rejects CRLF front matter). `npm run build` then still fails on the
guide pages (Windows path separators), but only after the app bundle is
built, and the dev server runs fine.

It listens on 8790 and prints a token for its made-up account; each start is
a clean slate with a new token. A debug build's "Dev server" is
`http://10.0.2.2:8790` (the emulator's view of this computer). On a phone
over USB, run `adb reverse tcp:8790 tcp:8790` and build with
`-Pchangelog.devServer=http://localhost:8790`. An upload form's `url` on the
dev server is a path: put the server's address in front of it.

## What's next

Done: sign-in by token, Log (version, tag row with a sticky tag, Commit),
the Outbox and its sender, Waiting to send (fix, discard), deleting a note
this phone sent, sharing text in, Settings and sign-out.

To come, in about this order (the iPhone app has each one):

1. Edit a sent note (`GET` then `PUT` with its revision).
2. Activity: `GET /v1/activity`, "Older" paging, and Today from every
   channel on Log.
3. Pinned tags in Settings (`PUT /v1/tags/pinned`, through the Outbox).
4. Files: photos (as JPEG), voice memos (`MediaRecorder`, MPEG_4 + AAC) and
   PDFs, kept with the queued note, posted as it is sent; share sheet for
   files (`ACTION_SEND` with streams, `ACTION_SEND_MULTIPLE`).
5. Here: one `FusedLocationProviderClient.getCurrentLocation` (needs Play
   services; ask Quinn before adding it), with the nearby businesses to pick.
6. Upcoming: list, add, change and remove, with a dry run.
7. Notifications: `POST_NOTIFICATIONS`, Upcoming reminders scheduled from
   each day's `send_at`, the daily prompt, activity notices from WorkManager
   periodic work.
8. A dev-server test cycle like the iPhone app's `ui-test-cycle.sh`.

`docs/PHONE-TEST.md` is the pass by hand each build gets on a real phone.
