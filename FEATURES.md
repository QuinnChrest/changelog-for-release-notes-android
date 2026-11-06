# Feature tracker

What Changelog for Android does, what's left, and what's built but not yet
checked. The iPhone app (`jthingelstad/changelog-ios`) is the reference;
`AGENTS.md` says how each built piece works.

**At a glance:** 52 built · 5 to do · 2 waiting on someone else · 7 to check

Key: ✅ built and tested · 🔲 to do · ⏳ waiting on someone else · 🔍 built, not yet checked

## To do

| | Feature | Notes |
|---|---|---|
| 🔲 | Sign in with Release Notes (OAuth) instead of pasting a token | The server has it since API 1.3.0: authorization code with PKCE for the resource `/v1`, a private-use callback (`com.yourversionnumber.changelog:/oauth`), the owner names the token and ticks its scopes, no refresh token; sign out at `/oauth/revoke` or `DELETE /v1/token`. Needs the registration, a browser hop (Custom Tabs, which is AndroidX) and the token exchange. Pasting a token stays for the dev server. |
| 🔲 | Notes for another day | The API takes a `date` back to the birthday and the queue already carries one; Log has no day picker, so every note files to today. |
| 🔲 | "Where": search for a place by name | `GET /v1/places?q=` is the web's city search; Log only has Here. |
| 🔲 | Take a file off a note | `/v1` can add files to a note but not remove one, so the editor shows a note's files and leaves them. Needs an API change first (an issue on yvn-release-notes). |

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
| ✅ | Home-screen widget: today's version and the prompt, a tap to write |
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
| ✅ | Tests: unit tests, tests against the dev server, and a UI test cycle on the emulator; CI on every push |
