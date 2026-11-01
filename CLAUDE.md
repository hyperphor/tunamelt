# CLAUDE.md

Tunamelt is the Hyperphor stack's schemaless persistence layer. Design: `design/design.md`.

## Build & Test

Leiningen, matching sibling projects:

```bash
lein test
lein test :only hyperphor.tunamelt.core-test/find-test
```

## Layout

```
src/clj/hyperphor/tunamelt/
  core.clj    — public API (put/get/find/delete/history/put-many), config
  uri.clj     — the only place the URI format is defined
  store.clj   — backend protocol
  sqlite.clj  — SQLite backend (next.jdbc + sqlite-jdbc); objects as JSON,
                plus a derived `attrs` index table used by find
```

## Ground rules

- No schema creep: no field validation, relations, or migrations — that's Alzabo's job.
- Keep `find` narrow: type/realm/time + top-level attribute equality (multi-valued via arrays).
- The `attrs` table is derived from the JSON; bump `schema-version` in sqlite.clj when its
  shape changes so existing databases get reindexed on open.
- Two versioning modes only (`:mutable`, `:append-only`).
- Document per-backend `find` performance rather than pretending it's uniform.
