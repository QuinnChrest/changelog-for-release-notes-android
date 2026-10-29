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
4. **Share.** From Chrome, share a link to Changelog: it starts a note.
   Commit makes exactly one note.
5. **Offline.** Airplane mode, a note, Commit: it waits under "Waiting to
   send". Airplane mode off: it sends, once.
6. **The build.** The foot of Settings names the version and the commit
   (`Changelog 0.0.0, build 1a2b3c4`).
7. **Activity.** Rows from every channel; a filter; "Older" loads more.
8. *(later)* Pinned tags, photos, a voice memo, Here, Upcoming,
   notifications.
9. **Last, sign out.** Settings, "Sign out", then "Sign out and revoke this
   token". The token shows as revoked on the web, and anything still
   waiting stays on the phone.
