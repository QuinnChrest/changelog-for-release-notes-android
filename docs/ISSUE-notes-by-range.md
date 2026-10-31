# Issue for jthingelstad/yvn-release-notes: read the notes a token wrote, across a date range

Title: **`GET /v1/notes?from=&to=` — list the notes this token wrote across a date range, with their words and files**

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

1. **`GET /v1/notes?from=YYYY-MM-DD&to=YYYY-MM-DD`**, scope `notes:write`
   (same as `getNote`): the notes **this token wrote** on account-local days
   from `from` to `to`, both inclusive (`to` defaults to `from`, so one day is
   one date). Each is the existing `Note` (id, date, version, text, revision,
   written_at, token), so it carries no more than `getNote` does. Order is up
   to the server (oldest first within a day reads best); also `limit`
   (max 100) and a `before`/`next` cursor, like `/v1/activity`, so a long
   range or a busy day pages. No notes in the range: `{"items": [], "next": null}`.
   The client pulls whatever range it needs (a day for Log, a week or month
   for scrolling back) and does the stepping itself.
   **How wide a range to allow is the server's call**: cap the span in days
   (say 31 or 92), the number of notes per page, or both, whatever protects
   it. Refuse a wider one with a stable error `code` (e.g. `range_too_wide`)
   and say the limit in the `message`, and document it in the OpenAPI, so the
   app can ask in pieces. Also refuse `from` after `to` and malformed dates.
2. **Files on a note**: let `Note` carry `files` as a list, not only a count:
   `[{"id", "type": "image/jpeg", "name", "size", "width", "height", "duration"}]`
   plus a way to fetch the bytes with the same token, e.g.
   `GET /v1/notes/{date}/{id}/media/{file_id}` (original) and a
   `?size=thumb` or `/thumb` variant for the list. Scope `notes:write` is
   enough, since it's only files this token wrote. (Keep `files` as the count
   too if changing its type would break clients; a new `file_list` field
   works.)
3. Optional, helps Activity too: **`from`/`to` on `GET /v1/activity`**, the
   same range, so a client can ask for the rows of some days without paging
   from now back to them. Same cap at the server's discretion.

Everything stays inside the existing rule that a token reads back only what
it wrote. This asks for no `notes:read` and exposes no other channel's words.

## Related

A separate issue (`ISSUE-notes-whole-account.md`) asks for a token permission
that reaches every note in the account, not only the ones the token wrote. This
issue doesn't depend on it: the range call works for the notes a token wrote,
and should return more only if that permission exists and the token has it.

## Why this matters

- One request per range instead of paging the log and N `getNote` calls: far
  less data on a phone and on the server.
- Works for a token with only `notes:write`, so a client doesn't have to ask
  for `activity` just to show its own day.
- Lets the app show photos and voice memos, which no current call allows.

## Acceptance

- `GET /v1/notes?from=2026-10-28&to=2026-10-30` with a `notes:write` token
  returns only that token's notes on those days, 200 with `items`/`next`;
  `from` alone is that one day.
- A range with none returns an empty list, not 404.
- A range wider than the server's limit, `from` after `to`, or a bad date is
  refused with a stable `code`, and the limit is in the OpenAPI.
- A token that didn't write a note never sees it, whatever its scopes.
- `/v1/openapi.json` and `info.x-changes` describe it (a new minor version).
- Files: a note with a photo lists it and the thumb URL serves the image to the
  same token.
