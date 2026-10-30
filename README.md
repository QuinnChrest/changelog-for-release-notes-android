# Changelog for Android

A fast Android app for sending notes into
[Release Notes](https://notes.yourversionnumber.com), the daily journal from
[Your Version Number](https://yourversionnumber.com/) where every day you're
a new release of yourself. It is not a second Release Notes: you read, search
and look back on the web. Changelog is for getting things in, in two taps.

The Android pair of [Changelog for iPhone](https://github.com/jthingelstad/changelog-ios).

- **Log** a line, with one-tap tags. The button says Commit.
- **Never lose a note.** Everything goes through an offline queue, and a
  resend never makes a duplicate.
- **Check** what happened: the activity log, from every channel, without
  the words. Edit or delete what this phone sent.
- **Photos and voice memos** on a note: from the photo picker or the
  camera, recorded in the app. They wait on the phone with the note until
  it is sent. Files need a token with "Add files" ticked.
- **Share** text, a link, photos, a recording or a PDF from any app: it
  becomes one note.
- **Upcoming**: the birthdays, anniversaries, trips and events ahead, to
  add, change and remove, each in the line the email gives it.
- **Reminders** on the phone, off until you turn them on: each comes when
  that morning's email is due, in its words.
- **A daily prompt** with the day's version, and **activity notices** from
  elsewhere, each off until you turn it on.
- **Where you are** on a note, the neighbourhood or a business nearby, and
  **pinned tags** shared with the web. See "What's next" in [AGENTS.md](AGENTS.md).

It talks only to Release Notes' public `/v1` API, with a token you make in
Release Notes' Settings, under API tokens. The API is designed in
[`docs/API.md`](https://github.com/jthingelstad/yvn-release-notes/blob/main/docs/API.md)
in the Release Notes repo.

## Building

JDK 17+, Android SDK 37, or just open the folder in Android Studio.

```sh
./gradlew :kit:test
./gradlew :app:assembleDebug
```

To try it without touching real notes, run the Release Notes dev server
(details in [AGENTS.md](AGENTS.md#the-dev-server)), choose "Dev server" on the
sign-in screen of a debug build, and paste the token it prints.

Changelog numbers its builds the way Release Notes does, by Your Version
Number's arithmetic from its own start on 29 October 2026 to the day a build
is made, in Chicago: 0.0.0 on the first day, 0.1.0 a year on. The foot of
Settings shows it with the commit.
