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
  Keystore key; `ui/` has the screens (sign-in, Log, Notes, Settings). The app's
  words are in `res/values/strings.xml`, read with `say()` and `sayCount()`
  (in `ChangelogApp.kt`). `kit` has no Android, so its sentences (activity
  rows, notices, the daily prompt, errors, `UpcomingDraft`'s checks) are
  named in `Words.kt` (`Phrase`, `CountPhrase`) and spoken through
  `Words.say`; the app supplies the words (`AppPhrases`, from the `kit_*`
  entries in `strings.xml`, with no `else` so a phrase without words won't
  build). `kit`'s tests use `English`, in its test sources: call
  `English.use()` before reading a sentence. A new sentence in `kit` is a
  new `Phrase`, its `kit_` string, and a line in `AppPhrases` and `English`.
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
one tangerine button on a screen), `QuietButton` (blue words), `Pick` (a choice,
or a thing turned on or off: quiet words, the chosen one in semibold ink
over a 2dp tangerine rule, as a field being typed in; no underlines, which
read as links on a phone; Quinn's call, 2026-11-08), `PaperField`
and `Select` (no box, a 2dp rule that turns tangerine), `Rule` and `Bar`.
No boxes, no shadows, no Material chips or outlined fields: build new
screens from these. The one exception is the tab bar along the bottom
(`FloatingTabs` in `MainActivity.kt`), a rounded bar lifted off the screen
with a shadow, as the iPhone app's: an icon over its word, the one you're on
in blue on a soft pill, with a fade of paper beneath it. Its icons, and the
composer's (photos, camera, record, Here), are outline vectors in
`res/drawable/ic_*.xml`, drawn by hand in one stroke weight and tinted by the
screen: no icon library. A screen that scrolls leaves `LocalBarSpace` at the
end of its list for the bar. On Log the streak sits at the right of the top
bar, and Commit at the right of the composer's icons.

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
- The app reads back the notes this token wrote and the activity log. With
  `notes:all` on the token (the owner ticks it when making the token: "Read
  and change every note", API 1.10.0) it reads, edits and deletes every note
  of the account, whatever channel wrote it. The app never makes the choice
  for the owner, and holds a note's words in memory only, never on disk (no
  backups). Without `notes:all`, the server refuses the rest (`not_yours`).
- Sign out sends `DELETE /v1/token` when the owner chooses to revoke.
- A notification never carries a note's words.
- Location for a note is foreground only, one `getCurrentLocation` when the
  owner asks, once per draft: each Here costs Release Notes a lookup, 100 a
  day. Check in now is the same: one fix when tapped.
- The one use of background location is Record my stops (check-ins, Quinn's
  call, 2026-11-07), off until the owner turns it on in Settings, and only
  then is "Allow all the time" asked for. It's Android's own LocationManager
  (no Play services), three requests delivered to `StopReceiver`: the fused
  provider at balanced power every five minutes, the passive provider (fixes
  other apps got anyway) and GPS every fifteen; `kit`'s `StopDetector` reports only real stops (ten minutes
  within 100 m), so each costs one of the server's 100 lookups a day. Off,
  or signed out, the request is taken back. A Play release needs Play's
  background-location declaration for it.
- No backups: the token's Keystore key can't leave the phone, and the queue
  holds words.
- Dependencies are AndroidX and Kotlin's own libraries only, and MapLibre
  for the check-in map (Quinn's call, 2026-11-07; it brings OkHttp and
  Timber). Ask before adding anything else.
- The check-in map draws OpenFreeMap's vector tiles (OpenStreetMap's data,
  no key), so OpenFreeMap's servers see which tiles are shown (roughly where
  the pins are), as any map would. Its credit, "© OpenMapTiles ©
  OpenStreetMap", stays on screen: both are required (OpenMapTiles' CC BY
  and OpenStreetMap's ODbL); OpenFreeMap's own name is optional, its README
  says, so it's left off. Nothing else goes there.

## Keeping up with the API

Release Notes changes its API often and says so. `GET /v1/openapi.json` (no
token needed) is the live contract; `info.x-changes` in it is the ledger of
each version, and `docs/API.md` in yvn-release-notes the design. When it has
moved past the version the app last read (`api_version` in `/v1/me`):

1. read what each version since changed (`x-changes`), and compare the
   schemas with `kit`'s models, which must read every field the spec marks
   optional as optional;
2. keep `Hashtags` in step with `packages/domain/tags.ts`;
3. run `DevServerTest` against the current dev server (`git pull`, restart
   it) before anything else.

It was last read against API 1.18.2 (2026-11-07; the server's own version
hadn't moved since 2026-11-06). Every new field is optional and `kit`
ignores what it doesn't know, so nothing broke. Of what 1.13.0 to 1.18.2
added, the app reads `lived` on `/v1/me` (1.13.0), a recording's
`transcript` and `writing` (1.14.0), `GET /v1/search` and
`GET /v1/on-this-day` (1.15.0), listing check-ins for the map
(`GET /v1/check-ins`, 1.18.0), taking one file off a note (`DELETE
/v1/notes/{date}/{id}/media/{n}`, 1.17.0), a `note.files` row's `removed`
(1.17.0), and the
check-in activity rows (1.18.0: `checkin`, worded with its `label`, which
is the owner's words and so never goes in a notice; `checkin.named`,
`checkin.deleted` and `checkin.place`, kinds the ledger doesn't list but
`packages/domain/activity.ts` does); not yet the rest of a note (`parts`,
`place`, `markup` and the like), `include=urls`, the archive reads beyond
the days, search and on this day (releases, tags, the map, badges and the
mail log, 1.15.0), rich notes and place writes (1.16.0), or naming or
deleting check-ins and remembered places (1.18.0). It sends check-ins
(`POST /v1/check-ins`: Check in now as a `tap`, Record my stops as
`visit`s) and reads `GET /v1/map/pins` for the map's notes.

## Building

JDK 17 or later, Android SDK 37.

```sh
./gradlew :kit:test            # the kit's tests, no Android needed
./gradlew :app:assembleDebug   # a debug APK
./gradlew :app:installDebug    # onto a running emulator or phone
```

A debug build starts on the dev server. One made to be used, not developed
(`-Pchangelog.production`, as the README says for anyone building it themselves)
starts on Release Notes; release builds only ever talk to Release Notes.

Versions count from Changelog for Android's first day, 2026-10-29, by Your
Version Number's arithmetic (decades.years.days, the day in Chicago), like
Release Notes' and the iPhone app's own. `app/build.gradle.kts` stamps it as
`versionName`, the commit count as `versionCode`, and the short commit as
`BuildConfig.COMMIT`, which the foot of Settings shows. Never set them by
hand.

CI (`.github/workflows/build.yml`) runs `./gradlew :kit:test
:app:assembleDebug :app:lintDebug` on every pull request and push to `main`.
It only reads the repo (`contents: read`, and checkout keeps no token), and
each action is pinned to a full commit with its tag in a comment. Dependabot
opens a PR when one has a new release; never move an action back to a bare
tag.

### Releases

Every push to `main` is a release on GitHub (Quinn's call, 2026-11-08):
a tag and a release named for it, with notes that say in plain words what
changed for someone using the app (what's new, what's fixed), no pictures,
then an Install section and the signed `Changelog-x.y.z.apk` built by
`assembleRelease`, with the signing certificate's SHA-256 so it can be
checked. Releases are numbered by semantic versioning from 1.0.0 (the first
under this rule; 0.0.2 to 0.0.7 before it were named for the build number):

- **Major** (2.0.0) for a break: Quinn and Jamie's call, never assumed.
- **Minor** (1.1.0) when anything new reaches the person using the app, a
  feature or a change they see; the patch goes back to 0. A release with
  features and fixes is a minor.
- **Patch** (1.0.1) for fixes alone, with no change to what they see or do.

This number is the release's, on the tag and the APK's file name. The
build's own `versionName` stays Your Version Number's day count, stamped by
`app/build.gradle.kts` and shown at the foot of Settings; never set it by
hand to match.

### Release builds

`./gradlew :app:bundleRelease` makes the Play bundle; `assembleRelease` an
APK. R8 shrinks them. To check what R8 does without a real account, the
`shrunk` build is shrunk as release is but signs in to the dev server as
debug does; TestCycle runs on it:

```sh
./gradlew :app:connectedShrunkAndroidTest -Pchangelog.testBuildType=shrunk -Pandroid.testInstrumentationRunnerArguments.devToken=rn_...
```

(It keeps Kotlin, coroutines and AndroidX whole, which its tests borrow
from the app; Changelog's code and kotlinx.serialization shrink as in
release.)

Signing is Quinn's: make an upload key once, kept out of git (`*.jks` and
`signing.properties` are ignored), and name it in `signing.properties` at
the repo's root. Without that file, release builds come out unsigned.

```sh
keytool -genkeypair -v -keystore changelog-upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
```

```properties
storeFile=changelog-upload.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

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

`kit`'s `DevServerTest` runs the client against it (a note sent twice
under one key, an edit from a stale revision, a note with a file and a
place through the Outbox, Upcoming and its reminders, activity without
words, pins). It skips unless a token is set, and runs only on localhost:

```sh
CHANGELOG_DEV_TOKEN=rn_... ./gradlew :kit:test --tests '*DevServerTest'
```

`app`'s `TestCycle` (Compose UI tests, like the iPhone app's
`ui-test-cycle.sh`) drives the real app on an emulator against it: sign
in, a note committed and checked on the server as filed once, a date added
in Upcoming, Activity and Settings, then everything it made removed. It
skips without a token:

```sh
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.devToken=rn_...
```

### Android 17 and its emulator

Changelog targets Android 17 (API 37), and TestCycle passes there and on
Android 16. Three things to know:

- **Local network.** Android 17 lets an app reach a private address (the
  emulator's 10.0.2.2) only with `ACCESS_LOCAL_NETWORK`, a runtime
  permission Android words as "nearby devices". Debug builds declare it
  (`app/src/debug/AndroidManifest.xml`) and ask as they sign in to the dev
  server; TestCycle grants it. Release builds never need it: Release Notes
  is on the public internet.
- **The emulator image.** The `android-37.0` Google Play x86_64 image
  (revision 7, also listed as 8.0.0, the same bytes) was built for emulator
  37.2.11; on emulator 37.2.13 its SurfaceFlinger aborts every few seconds
  ("Assertion failed: !rcEnc->featureInfo()->hasReadColorBufferDma") and
  the phone restarts its system in a loop. Use the `android-37.1` image
  instead (`system-images/android-37.1/google_apis_playstore_ps16k/x86_64`,
  16 KB pages), with at least 4 GB of RAM; `avdmanager`'s default 1.5 GB is
  too little for Android 17.
- **Espresso.** Android 17 removed `InputManager.getInstance`, which
  Espresso 3.7 (under Compose's UI tests) calls. The tests use the 3.8
  alpha, the first that runs there; move to 3.8 stable when it ships.

It listens on 8790 and prints a token for its made-up account; each start is
a clean slate with a new token. A debug build's "Dev server" is
`http://10.0.2.2:8790` (the emulator's view of this computer). On a phone
over USB, run `adb reverse tcp:8790 tcp:8790` and build with
`-Pchangelog.devServer=http://localhost:8790`. An upload form's `url` on the
dev server is a path: put the server's address in front of it.

## What's next

Done: sign-in by token, Log (version, tag row with a sticky tag, Commit,
today's notes), Notes (the days and their notes),
the Outbox and its sender, Waiting to send (fix, discard), opening a note
full screen to edit or delete it (`ui/NoteViewer.kt`: Edit is the composer's
words, tag row, Photos, Camera and Record, with files added after the edit
as an `OutboxOperation.Attach`; each of a note's own files has a Remove that
asks first and goes at once as an `OutboxOperation.RemoveFile`, apart from
Save, except the last file of a note with no words, which the server keeps;
Delete asks first), Log showing notes only (activity is its own
tab; offline, today falls back to what this phone sent), the Activity screen
(filters, "Older" paging, "Since you last looked"; each filter's last rows
kept in memory in `AppModel.activityShown` so the tab opens on them), Settings and
sign-out. A note's Activity rows open the note, and a filed reply its day in
Notes, where the token may read them (`kit`'s `ActivityItem.opens`). Log
and the widget say "Week 2,369 — Day 16,583" under the version (`lived`),
and Upcoming counts down ("In 5 days", "3 weeks 2 days") under month
headings (`kit`'s `Countdown` and `byMonth`). Activity rows read as in the iPhone app (`kit`'s
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

Log shows today's notes only (`GET /v1/notes?from=&to=`, oldest first, paged
by `next`; `AppModel`'s `loadDay`, `ui/DayNotes.kt`); the days before are in
Notes. A note's files come from its `media`:
a photo shows its small copy when it has one (`size=thumb`, kept in a small
cache for the session), a recording plays with play, pause and a line to scrub along (`VoiceMemo`), with its transcript under it
once Release Notes has kept one (the owner's opt-in; cut to six lines on Log, whole and selectable when
the note is open; "Its words are still being written." while `writing`, and the note read again every ten
seconds for up to five minutes, since a transcript arriving doesn't change `revision`), and anything else opens, each by a
signed address asked for as it's tapped and never kept (ten minutes, fetched
with no token). A photo opens in the app (`ui/PhotoViewer.kt`: full screen,
pinch and double-tap to zoom, decoded down to 2560 px on its longest side);
other files go to what the phone opens them with. A note says where it came
from (`source`, `mine`).

Pinned tags are set in Settings (`PUT /v1/tags/pinned`, through the
Outbox) and come first in the tag row.

Notes (`ui/NotesScreen.kt`) is reading, as the iPhone app's tab of the same
name (Jamie asked for the name): a search field, today's notes, On this day
(`GET /v1/on-this-day`: the same day in earlier years, each with its first
notes and "3 more on this day" to open it), then "Earlier", the days
before newest first (`GET /v1/days`, API 1.15.0, a page of 30 read as the last
row shows), each with its version and "3 notes · 1 file · place · Paused". A
day opens in the tab, Back returning to the list where it was left: its
version, place, weather (with the credit the API asks for) and every note
(`GET /v1/days/{date}`, then `/v1/notes` after its `next`). A note opens in
`NoteViewer`, as from Log, with Edit and Delete where the token may; the open
day's notes live in `AppModel` (`shownDayNotes`) beside Log's, so an edit or
delete shows on both at once. Search (`GET /v1/search`) takes words, or a
`#tag` alone for that tag's page; what it finds stands in for the days,
newest first and paged, until Clear or Back; its notes, and On this day's,
live in `AppModel` too (`found`, `onThisDay`), and `changeShown` changes a
note on every list at once. It needs `notes:all` or `notes:read`
(`readsNotes`); without, the tab says how to make such a token, and there is
no in-app upgrade yet (the iPhone app has one, by OAuth). Everything it reads
is in memory only. No lock on reading yet (the iPhone app's Face ID lock).

The check-in map (`ui/CheckInMap.kt`), as the iPhone app's: Map in
Activity's top bar (with the `activity` scope, which `GET /v1/check-ins`
needs) opens it full screen. Every page of check-ins is read and held in
memory (`AppModel.checkIns`); `kit`'s `CheckInMap.pins` gathers stays into a
pin per remembered place, or per name within 100 m, as the server and the
iPhone app do; a pin opens its stops, worded by `CheckInWords` ("Today,
8:05 AM to 9:15 AM, 70 min"). The map is a MapLibre `MapView` in an
`AndroidView`. Its base is OpenFreeMap's `positron` style recoloured in the
app's own palette, light or dark as the phone is (`paperStyle`, by
OpenMapTiles' layer names): land as the paper, roads as rules, water a wash
of cobalt, place names in ink and road names in lighter ink on a paper
halo, route shields left off; each colour is blended into the paper as a solid one, so crossings
don't darken. Pins are cobalt dots ringed in paper, over everything; the
chosen one is tangerine. No stop's own
page yet, and no naming or deleting stops (the iPhone app has both). The
map also shows notes with a place of their own (`GET /v1/map/pins`, with a
token that reads every note), as cobalt rings under the stops' dots, picked
on or off at the top ("Check-ins", "Notes"); a ring opens its notes, each
opening `NoteViewer`.

Check-ins from this phone (`Stops.kt`), as the iPhone app's: **Check in
now** (Activity's top bar and the map's) takes one fix and queues a `tap`
at that moment; **Record my stops** (Settings, off by default) records in
the background, as the location rule above says. `kit`'s `StopDetector`
reports a stop's arrival once the phone has stayed ten minutes, and its
end, with the same `arrived`, once a fix lands more than 100 m away; the
server takes the two as one stay, so a stop of five hours is one arrival
and one end, never one every ten minutes (the detector's `reported` holds
until the phone leaves, and the server merges a stop that starts within 30
minutes of the last one ending at the same place). Fixes vaguer than 250 m
are passed over. **Not at home** (Settings, under Record my stops): home is
set from one fix where the phone is and kept on the phone only
(`AppGraph.home`, no backups, gone on sign-out); `kit`'s `QuietPlaces` drops
every stop within 150 m of it, arrival and end, before the Outbox. Check in
now isn't filtered: a tap is the owner's choice.
Every report goes through the Outbox (`OutboxOperation.CheckIn`) under its
own key. The detector's state (one spot) is kept in `noBackupFilesDir`,
never logged. A restart or an update drops Android's request, so
`RestoreReceiver` and opening the app ask again.

Two widgets (`VersionWidget.kt`), in the app's own look: **Changelog**
(3×1, as it has always been: the version in Martian Mono, cobalt with
tangerine dots, the weeks and days lived, and "What changed?"; a tap opens
Log to write) and **Changelog: Coming up** (4×2: that same Today, with the
streak, or "Your email is stopped", under it; beside it as many dates in
Upcoming as its height holds, up to four, each in the server's line with
its day and countdown, as the Upcoming tab says them. Today's half opens
Log, Coming up's Upcoming. Tomorrow's version and On this day were tried
there and taken off, Quinn's call, 2026-11-08). A widget can't use the app's fonts, so
`TodayWidgets` draws the words into images, each twice, light and dark; the
layout shows one by `@integer/widget_light` and `widget_dark`, which the
home screen reads in the phone's own theme (a visibility given by integer
is its place in visible, invisible, gone, so gone is 2, not 8). The wide
one is a single picture, so both halves keep one scale, with clear halves
over it for the taps. Inside a `TextPaint`'s `apply`, `density` is the
paint's own (1), not the screen's: name the screen's apart. The next dates
are kept on the phone (`AppGraph.soon`; a date's line is the owner's words,
as a reminder's is), refreshed whenever the app reads Upcoming, as it
opens, and in the 12-hourly refresh; signing out drops them.

Log, Notes, Upcoming, Activity and Settings are the five tabs along the bottom.
Settings' Appearance (`ThemeMode`: same as the phone, light or dark) is about
the phone, so it's kept in `AppGraph` and signing out leaves it. The widget
and the tile follow the phone's own theme.

Here (`Locator`): one fix when the owner taps Here, with Android's own
`LocationManager.getCurrentLocation` (the fused provider where there is
one, then GPS, then network, then a position from the last five minutes),
so no Play services. `POST /v1/places/here` names it at once, and the
nearby businesses are offered to pick, never picked for the owner. A spot
that couldn't be named (offline) is named as the note sends (`prepare`); a
place that can't be named at all is left off, never holding the note.

`FEATURES.md` is the tracker: what's built, what's left, and what's built
but not yet checked. Keep it current with each change: move a row when a
feature lands or is checked, and update the counts at its top.

`docs/PHONE-TEST.md` is the pass by hand each build gets on a real phone.
