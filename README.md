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
  **pinned tags** shared with the web.

[FEATURES.md](FEATURES.md) tracks what's built and what's left.

It talks only to Release Notes' public `/v1` API, with a token you make in
Release Notes' Settings, under API tokens. The API is designed in
[`docs/API.md`](https://github.com/jthingelstad/yvn-release-notes/blob/main/docs/API.md)
in the Release Notes repo.

## Install it on your phone

Changelog isn't on Google Play yet, so you install it yourself ("sideload").
It needs Android 12 or later and a Release Notes account. There are two ways
to get the app; either one ends with an APK on your phone.

### Option 1: download a release

1. On the phone, open the
   [Releases page](https://github.com/QuinnChrest/changelog-for-release-notes-android/releases)
   and download the newest `.apk`.
2. Open it from your browser's downloads or from Files. Android asks whether
   to let that app install unknown apps: allow it, then tap Install. You can
   turn the permission off again afterwards in Settings → Apps → Special app
   access → Install unknown apps.
3. If Play Protect says it hasn't seen the app before, choose "Install
   anyway". That is the usual message for any app that isn't from Play.

No computer or USB cable is needed.

### Option 2: build it yourself

With JDK 17+ and the Android SDK 37, in a clone of this repo:

```sh
./gradlew :app:assembleDebug
```

Copy `app/build/outputs/apk/debug/app-debug.apk` to the phone and install it
as in steps 2 and 3 above, or with USB debugging on, run
`adb install -r app/build/outputs/apk/debug/app-debug.apk`. The debug build
works the same as a release, but its sign-in screen also has a "Dev server"
choice, which you can ignore. To make a release build signed with your own
key instead, see [Release builds](AGENTS.md#release-builds); an unsigned
release APK won't install.

### Sign in

In Release Notes, open Settings → API tokens and make a token. Tick what you
want the app to do: write notes, plus "Add files" for photos and recordings,
and "Read and change every note" to see and edit notes from your other
channels. In Changelog, paste the token. It stays on the phone, sealed by a
key that never leaves it.

### Updating

Install the newer APK over the old one (the same key must sign both, so don't
switch between a downloaded release and one you built yourself without
removing the app first). Your token and any notes still waiting to send are
kept. Android doesn't update sideloaded apps by itself, so check the Releases
page now and then.

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

## License

[MIT](LICENSE). The fonts are under the SIL Open Font License, in
[docs/fonts](docs/fonts/OFL.txt).
