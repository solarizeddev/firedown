# Facebook fixtures (desktop /watch/, logged out, 2026-11-24)

Derived from real response bodies (sanitized): the shapes, nesting and key names are Facebook's; every value that
could identify a post, a person or a file (ids, texts, names, URLs, tracking) is replaced with a neutral one.

- `watch-document.html`: the /watch/ document's feed (`video_home_sections`), each clip a `Video` media node with its
  text (`savable_description`), author (`owner`), picture (`image`) and length, and **no files**.
- `playback-bodies.json`: the `/api/graphql/` playback response sent when a clip plays (hover): `data.video` with the
  picture and size, the files in its `videoDeliveryLegacyFields` child, and **no text or author**.
