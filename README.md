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

## Attribute index

`get` reads the object's JSON, which is the source of truth. Every `put` also rebuilds
the object's rows in an `attrs` table (`attr`, `v_text` / `v_num` / `v_bool`): one row
per top-level scalar, or per element of a top-level array of scalars. Nested maps and
arrays of maps aren't indexed. For `:append-only` objects the index reflects the latest
revision. Databases created before the index existed are backfilled on open.

## Backends

| Backend | type/realm/time filters | `:attrs` filter |
|---|---|---|
| SQLite (default) | indexed | indexed (`attrs` table) |

The `hyperphor.tunamelt.store/Store` protocol is the extension point for other backends.
