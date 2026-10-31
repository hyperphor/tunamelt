# Tunamelt

## What this is

`spec.md` says "I have no idea" — genuinely underspecified, so this design fills the
gap deliberately rather than asking. The Hyperphor stack (see `hyperphor/stack`) lists
one mid-level component that's called **vital**, has a spec doc (`stack/spec/persistence.md`),
and has no name, no repo, no owning agent: the persistence layer. Everything else
open in the stack notes (Murmur, Pagents, Swarm) already has a name. Tunamelt claims
this slot: **it's the persistence layer.**

[hey the LLM agentified a piece of software, who would have thunk?]

Trivial, always-there save/restore for hand-made objects — prompts, LLM dialogs,
generation logs, Logseq blocks — as JSON with a naming and typing scheme. Not a
database product, not a schema authority. A drawer, like Multitool, except the drawer
holds data instead of functions.

## Performance requirements

Has to support a whole Logseq graph.

Has to enable find-by-propoerty


## Boundary with Alzabo

Alzabo owns *modeled* domain schema — the declarative source of truth for an app's
data model, aimed at Datomic. Tunamelt owns *incidental* objects that need to survive
a process restart but never earned a modeled schema: a prompt, a dialog, a log line, a
Logseq block dump. The type tag on a Tunamelt object (`prompt`, `dialog`, `log`, …) is
a nominal label for storage and retrieval, not a schema in Alzabo's sense — no
required fields, no relations, no migration story. If an object's shape stabilizes
enough that people want to query across its fields, that's the signal it has outgrown
Tunamelt and should move into an Alzabo-modeled store (Datomic, etc). Tunamelt doesn't
grow field-level validation to compete with that — it stays the schemaless catch-all.

## Naming

```
hyperphor://<realm>/<type>/<id>
```

- `realm` — which app/dataset owns this (`okc`, `exobrain`, `ammdi`, …). Lets one
  backend serve multiple consumers without collision.

[Maybe "hyperphor:" should not be baked in . they are close to URIs now. So
http://ammdi.hyperphor/<type>/<id> makes more sense I guess. Especially since most of these objects will be web-accessible.]

- `type` — nominal tag (`prompt`, `dialog`, `log`, `block`, `trace`). Free-form string,
  not a registered schema — new types cost nothing to introduce.
- `id` — UUID, generated on put unless the caller supplies one (needed for
  content-addressed or human-chosen ids, e.g. a Logseq block's existing UUID).

The URI is the object's identity and its lookup key. No separate index needed for
get-by-id.

## API

```clojure
(put uri obj)              ; upsert; obj is any EDN/JSON-able value
(put type obj)              ; put with a generated uuid, realm from config
(get uri)                   ; => obj or nil
(find type {:since t :realm r :attrs {...}})
                             ; => seq of {:uri :obj :meta}, newest first
(delete uri)
```

`find` is deliberately narrow: filter by type, realm, and time range always; by
top-level attribute equality as a bonus (implemented as a JSON containment query
where the backend supports it, a linear scan where it doesn't — document which, per
backend, rather than pretending it's uniform). No query language. If you need more
than that, the object belongs in a real schema store, not Tunamelt — that's the same
line as the Alzabo boundary, restated for querying instead of storage.

## Versioning

Chosen per object at creation time (persistence.md's own suggestion), stored as
`:tunamelt/mode` in the object's metadata, not negotiated later:

- `:mutable` (default) — `put` overwrites in place. Fine for logs and one-shot dialogs
  nobody needs history on.
- `:append-only` — `put` on an existing uri writes a new timestamped revision;
  `get` returns latest, `find` (or a `history` call) can walk revisions. For things
  like a prompt template people iterate on and might want to diff or roll back.

No object is silently upgraded between modes. Two modes cover the stated use cases
(prompts want history, logs don't) without building a general version-control system.

## Backend

"Trivial, always there" and "scaling mostly not a concern" argue against reaching for
DynamoDB/Mongo as the default — that's infra to stand up and pay for before the first
object is saved. Small, pluggable backend protocol instead:

- **Default**: SQLite file (or plain EDN-file-per-object under a directory if even
  that's too much) — zero config, works on a laptop, works in a single-dyno Heroku
  deploy alongside the rest of Ops' hosting conventions.
- **Pluggable**: same protocol backed by DynamoDB or Postgres JSONB for a consumer
  that actually needs shared/concurrent access beyond one process.

One protocol (`put`/`get`/`find`/`delete` at the storage layer), swappable
implementation. Depends only on Multitool, matching Way/Alzabo/Voracious.

## First consumers

- **Ellum** — store prompts and LLM dialogs/responses instead of each caller
  inventing its own log table.
- **NLFlame** — SQL-generation logs, queryable by realm and time for debugging bad
  generations.
- **Exobrain / GardenParty(AMMDI)** — Logseq blocks that need to round-trip.
- **Murmur/Swarm** (not yet built) — swarm.md names "execution trace" as a first-class
  subsystem; when that framework exists, Tunamelt is where a trace gets written
  (`type: trace`), not a bespoke store built into the framework.

## Stack registration (proposed, not applied)

`spec/components.md`, mid-level infrastructure row:

| Component | Link | Description | Depends on | Problem it solves |
|---|---|---|---|---|
| **Tunamelt** | hyperphor/tunamelt | Minimal, schemaless persistence layer for hand-made objects (prompts, LLM dialogs, logs, Logseq blocks) — JSON values under `hyperphor://realm/type/id` URIs, pluggable storage backend. | Multitool | Every component needs to save/restore ad hoc objects; this is the one place that's always there, instead of a bespoke table or file format per consumer. |

`spec/the-stack.md`: replace "Some kind of persistence layer, this is vital" with
`Tunamelt` under Mid level infrastructure.

`.claude/agents/tunamelt.md` stub, matching the `way.md`/`alzabo.md` shape:

```markdown
---
name: tunamelt
description: Owns hyperphor/tunamelt, the schemaless persistence layer for hand-made
  objects (prompts, dialogs, logs, Logseq blocks). Use when a component needs to
  save/restore objects durably, or when negotiating the Tunamelt/Alzabo boundary
  between incidental storage and modeled schema.
tools: Read, Grep, Glob, Bash, WebFetch
---

You are the owning agent for **Tunamelt** (`com.hyperphor/tunamelt`,
https://github.com/hyperphor/tunamelt), the Hyperphor Stack's persistence layer.

## What Tunamelt is

Trivial save/restore for hand-made objects as JSON under `hyperphor://realm/type/id`
URIs. Not a schema authority, not a query language — a drawer for data, like
Multitool is a drawer for functions. Depends only on Multitool; storage backend is
pluggable (SQLite default, Dynamo/Postgres for shared-access consumers).

## Your job

1. **Hold the line against schema creep.** The moment a consumer wants field-level
   validation, relations, or migrations on a stored type, that's Alzabo's job, not
   Tunamelt's — push the request there rather than growing a second schema system.
2. **Keep `find` narrow.** Type/realm/time filtering and top-level attribute equality
   only. A consumer asking for joins or a query language wants a modeled store.
3. **Don't let versioning modes multiply.** Two modes (`:mutable`, `:append-only`)
   cover the stated needs; a third mode needs a real use case, not speculation.
4. **Mind backend parity.** `find`'s attribute-filter is a native query on some
   backends and a linear scan on others — document per-backend behavior rather than
   letting callers assume uniform performance.

## Stack context

Mid-level infrastructure, depends on **Multitool**. Consumed by Ellum, NLFlame,
Exobrain, GardenParty/AMMDI, and (once built) Murmur/Swarm for execution traces.
Full map in `spec/components.md` of the `hyperphor/stack` repo.
```

## Open questions left for the author

- SQLite-vs-EDN-files as the zero-config default is a coin flip; SQLite gets you
  `find`'s attribute filter via JSON1 for free, which tips it.
