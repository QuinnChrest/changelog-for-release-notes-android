# A pass on a real phone

Each build that goes to a phone gets a pass by hand, after the dev server.
It mirrors Changelog for iPhone's `docs/PHONE-TEST.md`; items marked
*(later)* wait for that feature here.

A pass against a real account is the account's owner using the app by
hand, never an agent or a script. Its notes are real and go in the next
morning's email; delete any that shouldn't stay.

## Before you start

On the web, Settings, API tokens, make a token named for the phone
("Changelog on my Pixel") with all four ticked: Add notes, Add files,
Upcoming and the activity log.

## The list

1. **Sign in.** "Paste token", then "Sign in". Log opens on today's version.
2. **A note.** A line with a #tag, then Commit. It shows under Today and
   on the web. Edit it (change a word, Save), then Delete it.
3. **Tags.** Tap a tag in the row to add it, tap again to take it off.
   Touch and hold one: it goes on every note until Settings, "Stop adding
   #… to every note".
4. **Photos.** "Photos" (two or three) and "Camera" on one note, then
   Commit. The web note has every photo. A HEIC from the library arrives
   as a JPEG.
5. **A voice memo.** "Record", allow the microphone, ten seconds, Stop,
   Commit. It plays on the web.
6. **Share.** From Chrome, a link; from Photos, three or four big photos at
   once; from Files, a PDF; from a recorder app, a recording. Each makes
   exactly one note. A video too, to see what it says: Release Notes
   doesn't keep video.
7. **Offline.** Airplane mode, a note with a photo, Commit: it waits under
   "Waiting to send". Airplane mode off: it sends, once.
8. **The build.** The foot of Settings names the version and the commit
   (`Changelog 0.0.0, build 1a2b3c4`).
9. **Activity.** Rows from every channel; a filter; "Older" loads more.
10. **Upcoming.** "Add a date" (a birthday), Save, then edit it and delete
    it. A trip: "Find the city" names it. The web's Upcoming agrees at each
    step.
11. **Reminders.** Settings, "Remind me on this phone", allow
    notifications. A reminder comes when the morning's email is due, in
    its words; tapping it opens Upcoming. Restart the phone: it still
    comes.
12. **Prompt and notices.** "Ask me each morning" on: it comes at send
    time. "Notes from elsewhere" on, then a note from the web's agent or
    another token: a notice within the hour. Later: pinned tags, Here.
13. **Last, sign out.** Settings, "Sign out", then "Sign out and revoke this
   token". The token shows as revoked on the web, and anything still
   waiting stays on the phone.
