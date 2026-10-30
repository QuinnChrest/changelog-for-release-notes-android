# Feature tracker

What Changelog for Android does, what's left, and what's built but not yet
checked. The iPhone app (`jthingelstad/changelog-ios`) is the reference;
`AGENTS.md` says how each built piece works.

**At a glance:** 39 built · 1 to do · 3 waiting on someone else · 7 to check

Key: ✅ built and tested · 🔲 to do · ⏳ waiting on someone else · 🔍 built, not yet checked

## To do

| | Feature | Notes |
|---|---|---|
| 🔲 | `kit`'s words in resources | The app's words are in `strings.xml`; `kit`'s (activity rows, the daily prompt, `UpcomingDraft`'s checks) are still English in Kotlin, since `kit` has no Android. Needed only before a translation. |

## Waiting on someone else

| | What | Waiting on |
|---|---|---|
| ⏳ | Sign in with Release Notes (OAuth) instead of pasting a token | Release Notes adding OAuth to `/v1` (its phase 5) |
| ⏳ | Espresso 3.8 stable for the UI tests | Google; the tests use 3.8 alpha because 3.7 doesn't run on Android 17 |
| ⏳ | Play Console internal testing | Quinn: a Play developer account and the upload key. The release build is checked and signs from `signing.properties` (`AGENTS.md`, Release builds). |

## Built, not yet checked

These are written and built; something about them couldn't be seen on the emulator.

| | Feature | What still needs seeing |
|---|---|---|
| 🔍 | HEIC photos become JPEG | No HEIC sample on the emulator |
| 🔍 | A shared video is refused with a message | No video sample on the emulator |
| 🔍 | Reminders and the daily prompt fire at 6 a.m. | The emulator can't jump its clock; scheduled times were checked, not the firing |
| 🔍 | Reminders survive a phone restart | Not rebooted with alarms waiting |
| 🔍 | Sign-in screen in dark mode | Seen in light only |
| 🔍 | The whole pass on a real phone | `docs/PHONE-TEST.md`, by hand, on a real account |
| 🔍 | UI test cycle in CI | Runs on emulators here; CI runs the unit tests and lint only |

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
| ✅ | Waiting to send, with Fix and Discard for a refused note |
| ✅ | Edit and delete notes this phone sent |
| ✅ | Today: activity from every channel, or this phone's notes without the activity scope |

### Files and places
| | Feature |
|---|---|
| ✅ | Photos from the photo picker, and from the camera |
| ✅ | Voice memos (AAC .m4a) |
| ✅ | Files wait on the phone with the note; uploads go as it sends, and are made again if they expire |
| ✅ | Here: where a note was written, the neighbourhood or a business nearby to pick |
| ✅ | "Add where I am": each new draft finds its place, reused for ten minutes |
| ✅ | Share text, links, photos, recordings and PDFs from any app |

### Upcoming
| | Feature |
|---|---|
| ✅ | List of birthdays, anniversaries, trips and events, in the server's own words |
| ✅ | Add, edit and delete a date; "Find the city" for a trip's destination |
| ✅ | Reminders as the phone's own notifications, at each morning's email time |

### Activity and notifications
| | Feature |
|---|---|
| ✅ | Activity screen: filters, "Older" paging, "Since you last looked" |
| ✅ | Daily prompt: "You're 4.5.137 today. What changed?" |
| ✅ | Notices for notes from elsewhere, problems and badges; never this phone's own notes |

### Settings
| | Feature |
|---|---|
| ✅ | Token details and the build (version and commit) |
| ✅ | Pinned tags, shared with the web, in an order you set (Up and Down) |
| ✅ | Links to Release Notes, its settings on the web, the guide, and deleting the account |
| ✅ | Switches for reminders, the daily prompt and each kind of notice |

### Outside the app
| | Feature |
|---|---|
| ✅ | Home-screen widget: today's version and the prompt, a tap to write |
| ✅ | Quick Settings tile: today's version, a tap to write from the shade |

### Look and build
| | Feature |
|---|---|
| ✅ | Release Notes' look, light and dark: its colours, fonts and version style |
| ✅ | Runs on Android 12 through 17 (tested on 16 and 17) |
| ✅ | Words in `strings.xml`, with plurals, ready for translation |
| ✅ | Accessibility: switches labelled for TalkBack, 48dp targets, 4.5:1 text in both themes, every screen at 200% text |
| ✅ | Release build: R8 keeps the JSON working (the UI test cycle passes shrunk), no plain HTTP, no local network permission |
| ✅ | Release signing from a git-ignored `signing.properties` |
| ✅ | MIT licensed, and the repo is public |
| ✅ | Version numbers by Your Version Number's arithmetic from 2026-10-29 |
| ✅ | Tests: unit tests, tests against the dev server, and a UI test cycle on the emulator; CI on every push |
