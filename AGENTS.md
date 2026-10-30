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

## The look

Changelog looks like Release Notes (`web/src/styles/site.css` in
yvn-release-notes): type on paper, the email's colours, light and dark as
the phone is. `ui/Theme.kt` holds it: the site's colours (`paper`), its two
fonts (Bricolage Grotesque for words, Martian Mono for versions; OFL, see
`docs/fonts/`), `VersionText` (cobalt, the dots tangerine), `GoButton` (the
one tangerine button on a screen), `QuietButton` (blue words), `Pick` (the
site's tag picks: underlined blue, the chosen one in bold ink), `PaperField`
and `Select` (no box, a 2dp rule that turns tangerine), `Rule` and `Bar`.
No boxes, no shadows, no Material chips or outlined fields: build new
screens from these.

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
the Outbox and its sender, Waiting to send (fix, discard), editing and
deleting a note this token wrote, Today on Log from every channel (with
the `activity` scope; otherwise what this phone sent), the Activity screen
(filters, "Older" paging, "Since you last looked"), Settings and
sign-out. Activity rows read as in the iPhone app (`kit`'s
`ActivityText.kt`).

Files on a note: photos from the system photo picker or the camera app
(no storage or camera permission), voice memos (`Recorder`, MPEG_4 + AAC,
`RECORD_AUDIO` asked on first use), and photos, recordings and PDFs shared
in (`ACTION_SEND`, `ACTION_SEND_MULTIPLE`). `MediaReader` keeps JPEG, PNG,
GIF and WebP as they are and makes anything else (HEIC) a JPEG with its
time and place kept. A file waits in the Outbox's `outbox-files` folder
from the moment it's added; the Outbox's `prepare` asks for each form only
as the note sends, posts the file, and saves the uploads before the note
goes, so a resend is the same request. Uploads found gone are made again
once, under a new key.

Upcoming (its own tab): birthdays, anniversaries, trips and events, each in
the server's own line, added, changed and removed at once rather than
through the Outbox (each is made from a revision, and a refusal is for the
owner to fix there and then), each with a `RequestKey`. "Find the city" is
a dry run. `kit`'s `UpcomingDraft` checks what the phone can before
sending; the server stays the authority.

Reminders on the phone (`PhoneReminders`), off until turned on in
Settings (`POST_NOTIFICATIONS` asked then, on Android 13 and later): each
comes from `GET /v1/upcoming/reminders` at its day's `send_at`, in the
email's words, never worked out here. Each is an inexact alarm with a
ten-minute window (`AlarmManager.setWindow`): no exact-alarm permission,
and not held for hours the way a background job can be. Everything is
replaced with each read (on opening the app, after a date is saved, and
every 12 hours in WorkManager); a read that fails leaves what was
scheduled. The plan is kept so `RestoreReceiver` sets it again after a
restart or an update. A tapped reminder opens Upcoming.

`PhoneNotices` also holds the daily prompt ("You're 4.5.137 today. What
changed?", `kit`'s `DailyPrompt`, the server's version for each day, taken
back once a note is filed today) and activity notices (`ActivityNotices`:
notes from elsewhere, problems, badges; never this token's own rows, never
a note's words), read hourly in WorkManager from `/v1/activity?after=`.
Each is off until turned on in Settings, each kind with its own channel.

After a send, Log says "Filed in 4.5.137." or, when the receipt's
`unlocked` names badges, "Unlocked: …", in an ink capsule that goes by
itself.

Pinned tags are set in Settings (`PUT /v1/tags/pinned`, through the
Outbox) and come first in the tag row.

Here (`Locator`): one fix when the owner taps Here, with Android's own
`LocationManager.getCurrentLocation` (the fused provider where there is
one, then GPS, then network, then a position from the last five minutes),
so no Play services. `POST /v1/places/here` names it at once, and the
nearby businesses are offered to pick, never picked for the owner. A spot
that couldn't be named (offline) is named as the note sends (`prepare`); a
place that can't be named at all is left off, never holding the note.

To come, in about this order (the iPhone app has each one):

1. A dev-server test cycle like the iPhone app's `ui-test-cycle.sh`.

`docs/PHONE-TEST.md` is the pass by hand each build gets on a real phone.
