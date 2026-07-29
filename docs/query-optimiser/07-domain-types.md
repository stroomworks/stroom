# 7. Domain types

**Status:** Experimental. Advisory only — opt-in, and never an obstacle if unset.
**Audience:** analysts and administrators.
**Scope:** what a domain type is, how the optimiser uses it to validate a join key, and how to set them up.
Canonical for the matching rule.
**Companion documents:** [06-joins.md](06-joins.md) for the join clause itself.

---

## What a domain type is

A **domain type** is a semantic label on a field: a `class.attribute` string such as `Host.ipaddress`,
`User.id` or `Asset.serial`. It says what a value *means*, independently of how it is physically stored.

Stroom already carries a `domainType` on index and query fields, and has a `DomainType` catalogue document type
for recording the vocabulary an organisation uses. The optimiser is the first thing to do anything with it.

## What the optimiser uses it for

**One thing: validating a join key.**

When you write `join "B" as b on a.k = b.k`, the binder looks up both fields' domain types and checks they are
compatible. A semantically nonsensical join — an IP address to an asset id, both physically strings — is rejected
at compile time:

```
Join key domain types are incompatible: 'a.k' is Host.ipaddress, 'b.k' is Asset.serial
```

…rather than running to completion and returning nothing, or worse, returning coincidental matches.

That is the whole feature. Domain types do **not** influence which rows match, how the join executes, the cost
estimate, or anything else. They are a compile-time sanity check.

## The matching rule

A domain type is split at its **first** dot into a class part and an attribute part. Two types are compatible
when either can accept the other, where "accept" means both parts match — and a part matches when it is
identical, or when the *accepting* side's part is the wildcard `*`.

| Left | Right | Compatible? |
|---|---|---|
| `Host.ipaddress` | `Host.ipaddress` | yes — identical |
| `Host.ipaddress` | `*.ipaddress` | yes — the wildcard side accepts the specific one |
| `*.id` | `User.id` | yes |
| `Host.*` | `Host.ipaddress` | yes |
| `Host.ipaddress` | `Asset.serial` | **no** |
| `Host.ipaddress` | `Host.hostname` | **no** |
| `User.id` | `Account.id` | **no** — same attribute, different class |

A string with no dot parses as an empty class part and the whole string as the attribute, so `ipaddress` matches
`*.ipaddress` but not `Host.ipaddress`. Be consistent: always write both segments.

Matching is deliberately blunt — two segments, one optional wildcard, no subtyping and no hierarchy. It is strong
enough to *validate* a join, which is all it is asked to do. It is not an ontology.

## It degrades gracefully

The check is skipped entirely, and the join proceeds, whenever:

- **Either field has no domain type.** This is the common case today and is why domain types are opt-in rather
  than a new obstacle. Tag one side and nothing changes; tag both and the check starts working.
- **Either field cannot be resolved** on its side's schema.
- **A side is a Cypher sub-query.** Its derived columns are deliberately exposed with no domain type, precisely so
  that validation degrades rather than rejecting a legitimate graph join
  ([06-joins.md](06-joins.md#a-cypher-sub-query-against-a-graph-db)).

The consequence: **a passing join tells you nothing about whether domain types are set up.** Only a rejection is
evidence.

## How to set them up

1. **On each index field** — and equivalently on state and query fields — set its `domainType` to a
   `class.attribute` value that describes what the values mean: `User.id`, `Host.ipaddress`, `Asset.serial`.

2. **Use the same domain type across differently-named columns that mean the same thing.** A column called
   `src_ip` on one source and `ipAddress` on another, both tagged `Host.ipaddress`, is exactly the case this
   feature is for: the names differ, the meaning does not, and the optimiser can now confirm a join between them
   is sensible.

3. **Use a wildcard where a column is genuinely generic.** Tagging an id column `*.id` lets it join against any
   specific `<Something>.id`. Use this sparingly — a wildcard on both sides makes the check vacuous.

4. **Optionally, create `DomainType` catalogue documents** to record the vocabulary. The catalogue is
   documentation for humans; the check reads the field's own `domainType` string, not the catalogue.

### A worked pair

| Source | Column | `domainType` |
|---|---|---|
| `Events` (index) | `UserId` | `User.id` |
| `Users` (state store) | `Key` | `User.id` |

```
from "Events" as a
join "Users" as b on a.UserId = b.Key
select a.EventTime, b.Value
```

…binds cleanly. Change either tag to something unrelated and the same query is rejected before it runs.

## Why it only validates

An obvious next step would be to *infer* joins — "these two columns are both `User.id`, so join them for me". That
is deliberately not what this does. Auto-inference always **confirms** an explicitly written join rather than
silently rewriting one, because a blunt two-segment match is not strong enough evidence to make a join decision on
a user's behalf.

The related ideas that are on the roadmap — discovering enrichment candidates by domain type rather than
structurally, and relationship-mediated joins such as `User.id --OWNS--> Account.number` — are in
[12-future-work.md](12-future-work.md#domain-type-discovery-and-relationships). Neither exists today.
