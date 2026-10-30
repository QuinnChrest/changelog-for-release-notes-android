# Fonts

Release Notes' own type, so Changelog reads like the site and the email:

- `app/src/main/res/font/bricolage_grotesque.ttf`: Bricolage Grotesque, the
  text and headings.
- `app/src/main/res/font/martian_mono.ttf`: Martian Mono, versions.

Both are the latin subsets Release Notes serves (`web/src/fonts/` in
yvn-release-notes), converted from woff2 to TTF with fontTools, since
Android can't read woff2. They are variable fonts (`wght`), so one file
gives every weight. Both are under the SIL Open Font License 1.1, in
`OFL.txt` beside this file.
