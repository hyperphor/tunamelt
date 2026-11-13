# CLAUDE.md

Tunamelt is the Hyperphor stack's schemaless persistence layer. Design: `design/design.md`.

## Build & Test

Leiningen, matching sibling projects:

```bash
lein test
lein test :only hyperphor.tunamelt.core-test/find-test
npx shadow-cljs compile test     # cljs (IndexedDB) tests under node; npm install first
```

## Layout

```
src/cljc/hyperphor/tunamelt/
  uri.cljc    — the only place the URI format is defined
  store.cljc  — backend protocol (cljs methods return Promises)
  codec.cljc  — JSON encoding shared by all backends
src/clj/hyperphor/tunamelt/
  core.clj    — public API (put/get/find/delete/history/put-many/types/refs/referrers/search), config
  sqlite.clj  — SQLite backend (next.jdbc + sqlite-jdbc); objects as JSON,
                plus derived `attrs` (used by find) and FTS5 `fts` (used by search) tables
src/cljs/hyperphor/tunamelt/
  core.cljs   — the same API returning Promises
  idb.cljs    — IndexedDB backend; same JSON, derived `attrs` and token `fts` object stores
test/clj, test/cljs — the cljs tests mirror the JVM ones
```

## Ground rules

- No schema creep: no field validation, declared relation types, or migrations — that's Alzabo's job.
- Relations are just attribute values that are `hyperphor://` URIs; `refs`/`referrers` follow them.
- Keep `find` narrow: type/realm/time + top-level attribute equality (multi-valued via arrays).
  Text search belongs in `search`, not `find`.
- The `attrs` and `fts` tables are derived from the JSON; bump `schema-version` in sqlite.clj when its
  shape changes so existing databases get reindexed on open.
- Two versioning modes only (`:mutable`, `:append-only`).
- Document per-backend `find` performance rather than pretending it's uniform (README, Backends).
- Keep the JVM and cljs APIs and semantics in step; a change to one needs the matching change
  (and test) in the other. IndexedDB schema changes bump `db-version` in idb.cljs.
