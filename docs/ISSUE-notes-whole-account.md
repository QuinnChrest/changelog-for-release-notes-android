# Issue for jthingelstad/yvn-release-notes: a token permission to read and change every note

Title: **A token permission to read, add, change and delete any note in the account, not only the ones the token wrote**

## What a client wants

The website shows one journal: every note, whatever put it there (the web,
email, a phone app). A client app can't. Its token reads and changes only the
notes that token wrote, so a note typed on the website can't be seen or fixed
from the phone, and the other way round. A person with a phone app and a
browser has one journal and gets two views of it. A client should be able to
be a full view of the journal, if the owner chooses.

## What the API gives a client today

The consent page offers four permissions:

1. Add notes, and change or delete the notes it adds (`notes:write`)
2. Add files to the notes it adds (`media:write`)
3. Read and change your Upcoming dates and their reminders (`upcoming`)
4. Read the activity log, without any words from it (`activity`)

`GET`, `PUT`, `DELETE` and append on `/v1/notes/{date}/{id}` are all limited to
a note this token wrote ("Read a note this token wrote"). There is no
permission that reaches a note from another channel, so the activity log can
say a note arrived from the web, but not what it says.

## What would fix it

1. **A new permission**, worded for the owner on the consent page, for example
   "Read, add, change and delete any of your notes, from any channel". Name it
   whatever fits (`notes:all`, or `notes:read` plus a write-all counterpart if
   reading and changing should be asked for separately; that split is the
   server's call, and keeping them apart lets a client ask only to read).
2. **What it unlocks**, for a token that has it:
   - `GET /v1/notes/{date}/{id}`, `PUT`, `DELETE` and `append` work on any note
     in the account, with the same revision checks as today.
   - The date-range list (`ISSUE-notes-by-range.md`) returns every note in
     the range, not only the token's own.
   - Each `Note` says what wrote it (the existing `token` field, plus a
     `channel` such as `web`, `mail`, `api`), so a client can show where a
     note came from, and filter to its own with a `mine=true` parameter.
3. **Files**: the same permission covers listing and fetching the files of any
   note, and adding files to any note (with the file issue's `media:write`
   rules).

## Why it is a separate permission

A token is a credential a person hands to one app, and the narrow default
(this app writes its own notes) is a good one: a lost or over-trusted token
can't expose a whole journal. This permission is the opposite, a full view of
the journal, so it should be:

- unticked by default, and worded plainly on the consent page;
- visible in the owner's list of tokens as a broad one, so it's easy to see
  and revoke;
- logged in the activity log like any other write, with the token's name.

Clients will ask for it only when the owner turns on something that needs it
(Changelog for Android would offer "Show notes from everywhere" in Settings
and ask for a wider token then), and work with the narrow one otherwise.

## Acceptance

- With the new permission, a client can read, edit and delete a note written on
  the website, and sees it in the range list.
- Without it, behaviour is exactly as now: only the token's own notes.
- A token with the permission never reaches another account's notes.
- The OpenAPI and `info.x-changes` describe it; `GET /v1/me` lists it in the
  token's scopes.
