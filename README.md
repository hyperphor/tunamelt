# Tunamelt

Minimal, schemaless persistence for hand-made objects — prompts, LLM dialogs,
generation logs, Logseq blocks — stored as JSON under `hyperphor://<realm>/<type>/<id>`
URIs. A drawer for data, like Multitool is a drawer for functions. See `design/design.md`.

## Usage

```clojure
(require '[hyperphor.tunamelt.core :as t])

(t/configure! {:store "tunamelt.db" :realm "okc"})   ; or env TUNAMELT_DB / TUNAMELT_REALM

(t/put "hyperphor://okc/prompt/summarize" {:text "Summarize: {{doc}}"} {:mode :append-only})
(t/put "dialog" {:turns [...]})                       ; generated uuid, configured realm
(t/get "hyperphor://okc/prompt/summarize")
(t/find "log" {:realm "okc" :since #inst "2026-10-01" :attrs {:ok false} :limit 20})
(t/history "hyperphor://okc/prompt/summarize")
(t/put-many [[uri obj] ...])                          ; one transaction, for bulk loads
(t/delete uri)
(t/types)                                             ; => ("dialog" "log" "prompt")
(t/types {:counts? true :realm "okc"})                ; => {"log" 12, "prompt" 3}
(t/refs uri)                                          ; => {:parent ["hyperphor://..."] :refs [...]}
(t/referrers uri {:attr :refs})                       ; backlinks: objects whose :refs hold uri
(t/search "heidegger tech" {:type "block" :limit 20})  ; full-text, best first, with :snippet
```

## Semantics

- **Modes** are fixed at creation: `:mutable` (default, overwrite in place) or
  `:append-only` (each put adds a revision; `history` walks them). Changing mode throws.
- **JSON round-trip**: keys come back as keywords (namespaces kept, e.g. `:block/uuid`);
  keyword and `#inst` values come back as strings.
- **find** filters by type, plus optional realm, time range (`:since`/`:until` on last
  update), and top-level attribute equality via `:attrs`. Newest first.
  - A scalar matches if any of the object's values for that attribute equals it, so
    array-valued attributes are multi-valued: `{:tags "a"}` matches `{:tags ["a" "b"]}`.
  - A vector must all be present: `{:tags ["a" "b"]}`.
  - `nil` matches objects without that attribute.
  - Values are compared as `get` returns them, so `:done`, `"done"`, an `#inst` and its
    ISO string all match.

## Relations

No declared relation types: any attribute value (or array element) that is a `hyperphor://`
URI is a reference. `refs` lists an object's outgoing references; `referrers` finds incoming
ones across all types, optionally limited by `:attr` or `:type`, using the attribute index.

## Attribute index

`get` reads the object's JSON, which is the source of truth. Every `put` also rebuilds
the object's rows in an `attrs` table (`attr`, `v_text` / `v_num` / `v_bool`): one row
per top-level scalar, or per element of a top-level array of scalars. Nested maps and
arrays of maps aren't indexed. For `:append-only` objects the index reflects the latest
revision. Databases created before the index existed are backfilled on open; the same
goes for the full-text index.

## Full-text search

`search` is separate from `find`. It matches against an object's text: its top-level
string values and string array elements, but not `hyperphor://` URIs. Each object has one
row in an FTS5 index, so results come one per object, ranked by bm25, each with `:rank` and
a `:snippet` that marks matches with «». By default every word in the query must match as
a word prefix: `heidegger` matches "Heideggerian". Matching is case-insensitive and
ignores diacritics: `cafe` matches "Café". There's no stemming. Pass `:raw? true` to use
FTS5 query syntax directly (`"exact phrase"`, `OR`, `NOT`, `NEAR`); malformed raw queries
throw. `:type`/`:realm` filters apply after the text match.

## Backends

| Backend | type/realm/time filters | `:attrs` filter | `search` |
|---|---|---|---|
| SQLite (default) | indexed | indexed (`attrs` table) | FTS5 (`fts` table); type/realm filtered after match |

The `hyperphor.tunamelt.store/Store` protocol is the extension point for other backends.
