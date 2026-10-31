# Notes on persistence layer

## Needs

Just needs to be trivial to save and restore random objects. Trivial but it needs to be always there, rather than having to be reinvented each time.

### Things that you might want to store
- Prompts
- LLM responses and dialogs
- logs in general (eg SQL generation)
- Logseq blocks


## Abstract interface

I guess, just JSON? With a typing and naming scheme.\

### Naming
OK minimal proposal. Things get urls in whatever schema
hyperphor://repo/prompt/<uuid>

Probably not that exactly, but you get the ideas. 

### Query

## Implementation

Could be a minimal wrap of some database service (eg DynamoDB? Altthough not sure how schemaless it is. Or MongodDB which everybody hates)

## Scaling

Mostly not a concern, this is for "hand-made" obects

## Immutability/versioning

Not sure but need to at least have a stated philosopjy.

Could ave multiple theories that are selected at object creation time, rather than locking in.

