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
  update), and top-level scalar attribute equality. Newest first.

## Backends

| Backend | type/realm/time filters | `:attrs` filter |
|---|---|---|
| SQLite (default) | indexed | `json_extract` scan within the type |

The `hyperphor.tunamelt.store/Store` protocol is the extension point for other backends.
