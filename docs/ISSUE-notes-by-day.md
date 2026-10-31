# Issue for jthingelstad/yvn-release-notes: read the notes a token wrote, by day

Title: **`GET /v1/notes?date=` — list the notes this token wrote on a day, with their words and files**

## What a client wants

Changelog for Android (and the iPhone app) is a fast way to *write* notes. Its
Log screen should also be a small log of the day: the text posts, photos,
camera shots and voice memos this phone filed on a day, in order, with a way to
step back to earlier days. Not the whole journal (that's the web), just "what I
put in from here, today / yesterday / last Tuesday".

## What the API gives a client today

- `GET /v1/notes/{date}/{id}` (scope `notes:write`) reads **one** note this
  token wrote, but the client has to already know its `id`. The only place a
  client can learn ids is `GET /v1/activity`, and that is:
  - newest first, paged by an opaque `before` cursor, with **no way to ask for a
    day** (no `date`, `since` or `until`). Reaching last Tuesday means paging
    through every row since, then one `getNote` per row (N+1 requests).
  - mixed with every other channel's rows, so a day of ten notes may sit among
    hundreds of mail, badge and status rows.
  - gated by the separate `activity` scope, which a token made only for
    writing doesn't have.
- `Note.files` is a **count** only. There is nothing a client can fetch to show
  a picture or play a voice memo.
- There is no way to list notes at all.

So a client can show the words of a day only by walking the activity log and
fetching notes one at a time, and can never show the files.

## What would fix it (smallest useful change)

1. **`GET /v1/notes?date=YYYY-MM-DD`**, scope `notes:write` (same as
   `getNote`): the notes **this token wrote** on that account-local day,
   oldest first, each as the existing `Note` (id, date, version, text,
   revision, written_at, token), so it carries no more than `getNote` does.
   Also `limit` (max 100) and `before` cursor, like `/v1/activity`, for a
   busy day. Empty day: `{"items": [], "next": null}`.
2. **Files on a note**: let `Note` carry `files` as a list, not only a count:
   `[{"id", "type": "image/jpeg", "name", "size", "width", "height", "duration"}]`
   plus a way to fetch the bytes with the same token, e.g.
   `GET /v1/notes/{date}/{id}/media/{file_id}` (original) and a
   `?size=thumb` or `/thumb` variant for the list. Scope `notes:write` is
   enough, since it's only files this token wrote. (Keep `files` as the count
   too if changing its type would break clients; a new `file_list` field
   works.)
3. Optional, helps Activity too: **`date` (or `since`/`until`) on
   `GET /v1/activity`**, so a client can ask for one day's rows without paging
   from now back to it.

Everything stays inside the existing rule that a token reads back only what
it wrote. This asks for no `notes:read` and exposes no other channel's words.

## Why this matters

- One request per day instead of paging the log and N `getNote` calls: far
  less data on a phone and on the server.
- Works for a token with only `notes:write`, so a client doesn't have to ask
  for `activity` just to show its own day.
- Lets the app show photos and voice memos, which no current call allows.

## Acceptance

- `GET /v1/notes?date=2026-10-30` with a `notes:write` token returns only that
  token's notes for that day, oldest first, 200 with `items`/`next`.
- A date with none returns an empty list, not 404.
- A token that didn't write a note never sees it, whatever its scopes.
- `/v1/openapi.json` and `info.x-changes` describe it (a new minor version).
- Files: a note with a photo lists it and the thumb URL serves the image to the
  same token.
