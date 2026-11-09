# Feature tracker

What Changelog for Android does, what's left, and what's built but not yet
checked. The iPhone app (`jthingelstad/changelog-ios`) is the reference;
`AGENTS.md` says how each built piece works.

**At a glance:** 53 built · 4 to do · 2 waiting on someone else · 19 to check

Key: ✅ built and tested · 🔲 to do · ⏳ waiting on someone else · 🔍 built, not yet checked

## To do

| | Feature | Notes |
|---|---|---|
| 🔲 | Sign in with Release Notes (OAuth) instead of pasting a token | The server has it since API 1.3.0: authorization code with PKCE for the resource `/v1`, a private-use callback (`com.yourversionnumber.changelog:/oauth`), the owner names the token and ticks its scopes, no refresh token; sign out at `/oauth/revoke` or `DELETE /v1/token`. Needs the registration, a browser hop (Custom Tabs, which is AndroidX) and the token exchange. Pasting a token stays for the dev server. |
| 🔲 | Notes for another day | The API takes a `date` back to the birthday and the queue already carries one; Log has no day picker, so every note files to today. |
| 🔲 | "Where": search for a place by name | `GET /v1/places?q=` is the web's city search; Log only has Here. |

## Waiting on someone else

| | What | Waiting on |
|---|---|---|
| ⏳ | Espresso 3.8 stable for the UI tests | Google; the tests use 3.8 alpha because 3.7 doesn't run on Android 17 |
| ⏳ | Play Console internal testing | Quinn: a Play developer account and the upload key. The release build is checked and signs from `signing.properties` (`AGENTS.md`, Release builds). |

## Built, not yet checked

These are written and built; something about them couldn't be seen on the emulator.

| | Feature | What still needs seeing |
|---|---|---|
| 🔍 | HEIC photos become JPEG | `MediaTest` runs a BMP through the same path (it comes out a JPEG); no real HEIC to hand |
| 🔍 | Reminders and the daily prompt fire at 6 a.m. | The emulator can't jump its clock; scheduled times were checked, not the firing |
| 🔍 | Reminders survive a phone restart | Not rebooted with alarms waiting |
| 🔍 | The whole pass on a real phone | `docs/PHONE-TEST.md`, by hand, on a real account |
| 🔍 | UI test cycle in CI | Runs on emulators here; CI runs the unit tests and lint only |
| 🔍 | A voice memo's transcript under it | API 1.14.0 (jthingelstad/yvn-release-notes#301). Seen on the emulator against the dev server's made-up transcript: "Its words are still being written." on Log, then the words, read again without a refresh, and whole in the open note. Not yet a real transcript on a real account |
| 🔍 | Check-in rows in Activity, and its Check-ins filter | API 1.18.0. Worded as the iPhone app ("Checked in at …", named, deleted, remembered places) and tested in `ActivityTextTest`; no check-in has been sent to the dev server to see a row on screen. The app sends and lists them now (see below) |
| 🔍 | Take a file off a note | API 1.17.0 (`DELETE /v1/notes/{date}/{id}/media/{n}`, as `OutboxOperation.RemoveFile`). The editor lists the note's files, each with a Remove that asks first; a note with no words keeps its last file and says so. `DevServerTest` takes one of two off on the dev server and is refused the last (`text_required`); not yet tapped through on the emulator |
| 🔍 | Search in Notes | API 1.15.0 (`GET /v1/search`): words, or a `#tag` alone for its page, newest first and paged, each note with its day and version; Back or Clear returns to the days. `DevServerTest` finds a note by a word and by its tag with a `notes:all` token; the screen not yet seen on the emulator |
| 🔍 | On this day in Notes | API 1.15.0 (`GET /v1/on-this-day`): under today's notes, each earlier year's day with its first notes and "3 more on this day" to open it. Read on the dev server, whose made-up account has no earlier years to show, so the section has only been empty there |
| 🔍 | Activity rows open their notes, as the iPhone app's | A note's added, edited, files, transcript and description rows open the note (with `notes:all`/`notes:read`, or a note this token wrote); a filed reply opens its day in Notes; a note deleted since says so. `ArchiveTest` covers which rows open; not yet tapped on the emulator |
| 🔍 | Week and day lived on Log and the widget, Upcoming's countdowns and months | "Week 2,369 — Day 16,583" from `/v1/me`'s `lived` (API 1.13.0) under the version and on the widget; Upcoming says "Tomorrow", "In 5 days", "3 weeks 2 days" and heads each month ("January 2027" when not this year's). Tested in `ArchiveTest`; not yet seen on screen |
| 🔍 | Map of check-ins, as the iPhone app's | Map in Activity: MapLibre over OpenFreeMap's vector tiles recoloured in the app's paper, ink and cobalt (light and dark), a cobalt pin per place, the chosen one tangerine, opening its stops ("Today, 8:05 AM to 9:15 AM, 70 min"). Seen on the Android 17 emulator against the dev server with made-up check-ins, debug and shrunk, light and dark; `CheckInsTest` and `DevServerTest` read and group them. Not yet on a real phone or a real account's check-ins |
| 🔍 | Check in now | A tap in Activity's top bar or the map's: one fix, queued as a `tap` through the Outbox. Seen on the Android 17 emulator against the dev server (the check-in arrived at the emulator's spot); `DevServerTest` sends one. Not yet on a real phone |
| 🔍 | Record my stops (automatic check-ins) | Settings switch, off by default, asking "Allow all the time" as it goes on; Android's own location every five minutes in the background, `StopDetector` reporting a stop after ten minutes within 100 m and its end when the phone moves on, through the Outbox. Fused, passive and GPS fixes, late ones passed over. `StopDetectorTest` and `DevServerTest` pass. On the emulator the first version (fused only) heard one fix in 25 minutes and found no stop; the three-source version hasn't finished a run there yet. Not yet a day on a real phone: battery, and how often Android really wakes it |
| 🔍 | Notes on the map | Notes with a place of their own (`GET /v1/map/pins`) as cobalt rings beside the check-ins' dots, "Check-ins" and "Notes" picked at the top; a ring lists its notes and each opens. Seen on the emulator with the dev server's notes (its made-up places all sit in one town) |
| ✅ | Home-screen widget: today's version (cobalt, tangerine dots), the weeks and days lived, and the prompt, a tap to write |
| 🔍 | A bigger widget: Today beside Coming up | 4×2: the same Today with the streak, and the next Upcoming dates in the app's own words and look (as many as its height holds, up to four, each line, then its day and countdown). Light and dark drawn apart and switched by the phone's theme. Seen on the Android 17 emulator in light and dark with the dev server's made-up dates. Not yet on a real phone |
| 🔍 | Ignore this location, on any remembered place; Not at home moved to it | API 1.20.0. Settings, Remembered places: a place ignored keeps no new check-in (`kept: false`, done in the Outbox; a tap says "Not checked in.") and hides its own until unignored; Delete existing check-ins here counts first and asks again if the count changed. A home kept on the phone by an earlier build moves to the remembered place within 100 m of it, ignored, or a new Home there, once signed in with `activity` and `notes:all`; until then stops within 150 m of it stay on the phone, as before. `PlacesTest` and `DevServerTest` (dev server, 1.20.0) cover the calls and the move; the screen not yet seen on the emulator, nor a home moved on a real phone |
| 🔍 | A photo's small copy on Log, and `notes:all` on a real account | The dev server's fixture photo has no small copy, so the thumbnail path ran only in a unit test; the whole-account list was seen on the dev server with a `notes:all` token, not on production |

## Built

### Signing in and out
| | Feature |
|---|---|
| ✅ | Sign in with a pasted token, kept sealed in the Android Keystore |
| ✅ | Debug builds can use the Release Notes dev server (asks for local network access on Android 17) |
| ✅ | Sign out, keeping or revoking the token; anything waiting stays on the phone |
| ✅ | A token that stops working signs out and keeps the queue |

### Writing a note (Log)
| | Feature |
|---|---|
| ✅ | Today's version, the date and the streak, from the server |
| ✅ | Tag row: pinned, then recent, then Keep a Changelog's words; tap to add or remove |
| ✅ | A sticky tag on every note (touch and hold) |
| ✅ | Commit saves to the phone first, then sends: works offline, never sends twice |
| ✅ | "Filed in 4.5.137." or "Unlocked: …" after a send |
| ✅ | Waiting to send, with Fix and Discard for a refused note; a write that sends at once never shows there, one tried, refused or kept over four seconds does |
| ✅ | Tap a note on Log to open it full screen (`NoteViewer`): all its words and files, where it came from, and Edit and Delete where this token may change it; Delete asks first. Rows on Log carry no buttons, and a source line too long for the row moves its version down whole |
| ✅ | Edit as the box on Log is: words, the tag row, and Photos, Camera and Record (files added to the note after the edit, `OutboxOperation.Attach`, `POST /v1/notes/{date}/{id}/media`); no Here, a note keeps its place |
| ✅ | Log shows today's notes with their words and files (photos by their small copy, voice memos to play, PDFs to open), oldest first (`GET /v1/notes?from=&to=`, API 1.9.0); offline, it falls back to what this phone sent. Days before are in Notes |
| ✅ | Notes, a tab as the iPhone app's: today's notes, then the days before, newest first and paged (`GET /v1/days`, API 1.15.0), each with its version, counts, place and Paused; a day opens with its version, place, weather and every note (`GET /v1/days/{date}`); a note opens full screen to read, edit or delete, as on Log. Needs `notes:all` or `notes:read`, and says how to get it otherwise. Seen on the emulator against the dev server |
| 🔲 | Notes: search, on this day, and a way to upgrade the token in the app | The API has search and on this day (1.15.0); the iPhone app upgrades an old token by OAuth from the tab, and can lock reading behind Face ID |
| ✅ | A photo on a note opens in the app, full screen, to pinch or double-tap to zoom and drag (`PhotoViewer`); a photo the phone can't read offers to open it outside |
| ✅ | Every note, from any channel, with a token that has `notes:all` (API 1.10.0, 1.11.0): each says where it came from, and Edit and Delete work on any of them; without it, only this token's own |
| ✅ | Log shows notes only, never activity rows: activity is on its own tab. Today shows nothing until its first read is back, so nothing stands in and is swapped out |

### Files and places
| | Feature |
|---|---|
| ✅ | Photos from the photo picker, and from the camera |
| ✅ | Voice memos (AAC .m4a) |
| ✅ | A voice memo on Log or a note plays with play and pause and a line to tap or drag anywhere along (before it's loaded, a tap starts it from there), with where it is and its length once loaded; at the end it pauses back at the start |
| ✅ | Files wait on the phone with the note; uploads go as it sends, and are made again if they expire |
| ✅ | Here: where a note was written, the neighbourhood or a business nearby to pick |
| ✅ | "Add where I am": each new draft finds its place, reused for ten minutes |
| ✅ | Share text, links, photos, recordings and PDFs from any app, landing on Log wherever the app was left |
| ✅ | A shared video is refused with a message (`MediaTest`) |

### Upcoming
| | Feature |
|---|---|
| ✅ | List of birthdays, anniversaries, trips and events, in the server's own words |
| ✅ | Add, edit and delete a date; "Find the city" for a trip's destination |
| ✅ | Reminders as the phone's own notifications, at each morning's email time |

### Activity and notifications
| | Feature |
|---|---|
| ✅ | Activity screen: filters, "Older" paging, pull down to refresh, "Since you last looked"; coming back shows each filter's last rows at once (memory only) while it reads again, and "Nothing here yet" only once a read comes back empty |
| ✅ | Daily prompt: "You're 4.5.137 today. What changed?" |
| ✅ | Notices for notes from elsewhere, problems and badges; never this phone's own notes |

### Settings
| | Feature |
|---|---|
| ✅ | Token details and the build (version and commit) |
| ✅ | Pinned tags, shared with the web, in an order you set (Up and Down) |
| ✅ | Links to Release Notes, its settings on the web, the guide, and deleting the account |
| ✅ | Switches for reminders, the daily prompt and each kind of notice |
| ✅ | Appearance: same as the phone, light or dark, kept on the phone |
| ✅ | Settings is the fourth tab, along the bottom |
| ✅ | A tab bar lifted off the screen, with an icon over each word, as the iPhone app's |
| ✅ | Log: photos, camera, record and Here as icons with Commit at their right; the streak at the right of the top bar |

### Outside the app
| | Feature |
|---|---|
| ✅ | Quick Settings tile: today's version, a tap to write from the shade |

### Look and build
| | Feature |
|---|---|
| ✅ | Release Notes' look, light and dark: its colours, fonts and version style (every screen seen in both) |
| ✅ | Runs on Android 12 through 17 (tested on 16 and 17) |
| ✅ | Every word in `strings.xml`, with plurals, ready for translation: the app's own, and `kit`'s through its phrasebook (`Words.kt`) |
| ✅ | Accessibility: switches labelled for TalkBack, 48dp targets, 4.5:1 text in both themes, every screen at 200% text |
| ✅ | Release build: R8 keeps the JSON working (the UI test cycle passes shrunk), no plain HTTP, no local network permission |
| ✅ | Release signing from a git-ignored `signing.properties` |
| ✅ | MIT licensed, and the repo is public |
| ✅ | Version numbers by Your Version Number's arithmetic from 2026-10-29 |
| ✅ | Checked against the live API's OpenAPI file (1.8.0): the client reads every field it uses, tags work in any script, activity rows are told apart by token id, and a note's words are made well-formed before sending |
| ✅ | Picks (filters, Appearance, the map's, tags, kinds) in one style: quiet words, the chosen one in ink over a tangerine rule; no underlines |
| ✅ | Tests: unit tests, tests against the dev server, and a UI test cycle on the emulator; CI on every push |
