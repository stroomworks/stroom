# The "AI authored" flag: a design note

**Status:** a design draft for the project owner. Nothing here is built. It is written for the AI translations system.
It says nothing about how the MCP server would work, except that the flag's shape must not stop the MCP server using it
later.

**Revision 3.** The first version had classes, content hashes and a taint rule. The second used a side table. This one
follows the project owner's design: **the flag is a property of the document**, so it travels with the document through
copy, export and import, and it is visible on the document. It is changed only by a separate, audited call.

## 1. The rule

> If a person reviews and accepts a change, the change is treated as done by a person. The AI assisted and the person
> took responsibility. Only content that **no person has accepted** is marked.

A proposed change is not a change until someone applies it. So the mark arises only where the AI wrote **live or
reusable content with no person in between**: Shapeshifter in auto mode (and, later, the MCP server). A triage fix that
a person approves and enables is human content.

The mark makes no claim about quality. It says "AI-written, not yet accepted by a person".

## 2. What it is for

| Use | What the mark does |
|---|---|
| **Not used as an example by AI** | Marked documents are never offered to a model as examples, and never added to the corpus, the regression set or the golden events. Otherwise a model learns from its own unreviewed output |
| **Not allowed dangerous features** | A pipeline that is marked may not use egress, whatever the rules say (§6). To egress, a person accepts the content |
| A review queue | Auto mode can promote now and be accepted later. The marked documents are that queue |
| Incident scoping and rollback | "Everything auto mode changed since date D that nobody has accepted" is a query. It supports the required one-action rollback (§6.7 of the shared note) |
| The trial report and accreditation | How many auto promotions were later accepted, reverted or left, and a checkable statement that no unaccepted AI-written content is live |

## 3. The property

A new optional property on every document (on the shared base type, `AbstractDoc`), absent for content that is not
marked:

```text
aiAuthored: { sinceMs, source, runId }     // absent = not marked
```

It is a small structure, not a boolean, so that scoping an incident ("since D", "by this run") does not need guesswork.
`source` names the writer (`shapeshifter-ai`, later `mcp`). It is part of the document, so:

| It does this | Because |
|---|---|
| Survives copy, rename, move, export, import and git | It is in the document's own data (assuming the export serialises the document, which I have not checked) |
| Shows in the editor | The editor reads it from the document. How a common indicator is added to every document type's editor is question 4 |
| Needs no separate table | |

An absent property means "not marked". That is also how all existing content reads, so there is no migration.

## 4. How it changes

Four rules, all enforced on the server and none left to the client:

| Rule | Detail |
|---|---|
| **The translation system sets it** | Every write by the translation system (today `FragmentWriter`, and any like it) sets the property, with the run id. It is set by the code, not taken from anything in a request |
| **Anyone may switch it on** | A person can flag a document as AI-authored by hand. Turning it on is always safe |
| **The ordinary save never switches it off** | The generic save of a document that is currently marked, with the property absent or cleared in what is sent, is **rejected** with a message that says to use Accept. It does not silently restore it, because a silent restore hides what happened |
| **A separate call switches it off: Accept** | A distinct operation (`accept`), which takes the document **and the version the person reviewed**, needs edit permission, clears the property and writes an audit entry (who, when, which version, the previous `sinceMs` and `source`) |

**Accept is bound to the version that was reviewed.** If anything wrote the document after the person looked at it,
including the AI in auto mode, Accept fails with the same "modified by another user" error as a stale save. This is the
property that makes "a person reviewed it" mean something.

**Why a separate call.** If clearing were a field in an ordinary save, anything that can save a document could clear
it, and that includes an AI acting on a user's behalf. Making it a distinct call means a caller that is not given that
call cannot clear the flag (§7). Other AI that runs inside Stroom's own server is in the same position: it writes
through code that sets the property, and it does not call Accept.

**Saving is not accepting.** A person who edits marked content in the ordinary editor and saves leaves it marked.
Otherwise a one-character edit would clear it. Accepting is an explicit act that says the person has taken
responsibility.

**Every AI write sets it again.** If accepted content is later changed by auto mode, it is marked again, because the new
change has not been accepted.

## 5. Optimistic locking: what I checked

The question was whether a stale editor could clear or overwrite the flag by last-write-wins. I read the document store.

| Route | Finding |
|---|---|
| **Ordinary save (update)** | Each document has a `version` (a UUID). The store passes the version the caller read to the persistence layer. The **database persistence** (`DBPersistence`) updates `WHERE version = expectedVersion` and, if no row matches, throws `DataChangedException` ("has been modified by another user"). So a stale editor **cannot** overwrite or clear the flag on the database store |
| **File and memory persistence** | `FSPersistence` serialises access with a lock per document and **does not compare the expected version**. `MemoryPersistence` ignores it. On these, last write wins. I do not know which is used where, other than that tests use the memory one. **To check before relying on the guarantee** |
| **Import** | "Import always succeeds, no version check" (a comment in `StoreImpl`). It can overwrite a document, flag included. It needs the import permission, which the MCP security notes already propose to deny any agent role |
| **A writer that reads, waits, then writes** (the translation system) | Gets `DataChangedException` if a person saved in between. The writer must treat it as a refusal and retry or stop, never overwrite blindly |

So the rule in §4 that Accept carries the reviewed version is the existing mechanism applied to a new call, not a new
mechanism.

## 6. What the flag does to a request

**Examples.** Wherever the system chooses examples for a model, marked documents are excluded: exemplars for a
candidate, the corpus, the regression set, golden events, and later anything the MCP server offers. Accepted content is
eligible, by the owner's rule.

**Egress.** A request from a pipeline whose effective flag is set is **refused in every mode, and no rule permits it**.
The attempt is recorded on the Blocked list with a Marked column. To use egress, a person accepts the content. The
routes the egress policy already denies outright (`unparsed-text`, `collection`, `xsl:result-document`) are unchanged.

*Out of scope for 1.0:* a user who wants an AI-authored pipeline to send data to one named destination. The design does
not stop that being added later (for example a rule that says it applies to marked content), but it is not built or
specified here.

**The effective flag.** A pipeline runs more than one document, so the flag that matters is the OR over the pipeline
and everything it uses (XSLT, text converters, dictionaries, embedded or referenced). Otherwise a human-written pipeline
that references an AI-written XSLT would run it with full egress rights. Two points:

- The document store already records **dependency edges** between documents (`updateDocDependencies`, `DocDependencyDao`
  in `StoreImpl`). They may give the set of references without a new scan. **I have not checked that they cover every
  kind of reference.**
- An XSLT chosen at run time by `xsltNamePattern` is not known when the pipeline is saved. The effective flag has to be
  computed when the XSLT is resolved, and the result carried in `EgressContext` beside the pipeline and feed. It is
  computed once per pipeline start and cached by document version, so the check on each request is unchanged.

## 7. Import, and what the flag does and does not protect

Export and import are already powerful: they can bring in any pipeline, and an importer who is not trusted can do far
more harm than a missing flag. So the flag is not a security boundary against an importer. It is a **provenance record
and a modest protection**:

| | |
|---|---|
| Import honours the flag as it is in the file | A document exported with the flag set stays marked on import. A document exported without it arrives unmarked |
| The flag in a file is a claim, not proof | Someone who can edit the file can clear it. That is accepted, for the reason above |
| It still helps | It carries AI provenance between instances, it makes an honest export honest, and it stops an accidental loss of the mark |

**What the flag protects against** is the AI (in auto mode, or acting as a user) producing content that then runs with
full rights. That is why the clearing call is separate (§4): the thing to protect is the AI's own ability to clear it.

## 8. Where it can still go wrong

| Case | Consequence | Mitigation |
|---|---|---|
| **A writer that does not set the flag** | AI-written content looks human | A build-failing test (§9) |
| **Someone clicks Accept without reading** | The content becomes eligible as an example, and may egress | The owner's rule. The audit entry and the accept rate in the trial report make it visible |
| **A change too big to review** | Accepted anyway | No attempt is made to define it |
| **File or memory persistence, if used where it matters** | No version check | Check which persistence is used (question 3) |
| **Import** | Overwrites without a version check and can carry any flag | Needs the import permission. Accepted (§7) |
| **A writer that overwrites blindly** | Hides a person's save | Writers treat `DataChangedException` as a refusal (§5) |

## 9. Proving nothing writes around it

A build-failing test in the manner of `TestEgressSinks`: enumerate the code in the translation system that writes to the
pipeline, text converter, XSLT and dictionary stores, and fail if any is neither one that sets the flag nor on a
reasoned allow-list. It needs the same two controls: a known writer that it must find, and a reason for every
allow-list entry.

## 10. Phases

| Phase | Work |
|---|---|
| **P1** | The property on the base type. The translation system sets it at its write paths. The Accept call, its permission check, its version binding and its audit entry. The ordinary-save rule. The editor indicator and the tick box that calls Accept. The build test |
| **P2** | The effective flag. The example filter at each selection point. The refusal of egress, and the Marked column and filter on the Blocked list |
| **Later** | An "unaccepted" list for the review queue. Any destination an AI-authored pipeline may use. Whatever the MCP server needs, which must not be offered Accept |

## 11. What I checked, and what I did not

| Statement | Basis |
|---|---|
| Documents have a `version` UUID, and the database persistence enforces it on update | Read in `StoreImpl` and `DBPersistence` |
| The file and memory persistence do not compare the expected version | Read in `FSPersistence` and `MemoryPersistence` |
| Import does not check the version | A comment and call in `StoreImpl`. Not run |
| The store records dependency edges between documents | Seen in `StoreImpl` and `DocDependencyDao`. I have not checked that they cover every reference |
| Documents have no field that says they were machine-generated | `docs/reference/design/mcp-server/stroom-llm-mcp-security.md` §3. Not re-read in the code |
| `EgressContext` carries pipeline and feed only | Read in the working tree |
| `FragmentWriter` writes model output into pipeline, text converter and XSLT documents | Found by search. **I have not read it through** and I have not looked for other writers |
| Export and import serialise the whole document, so a new property travels | **An assumption** |
| The existing accept acts (review panel Approve, triage accept) can call Accept | **An assumption.** I have not looked at how they are implemented |

## 12. Open questions

1. **Reject or restore (§4).** An ordinary save that clears a set flag is rejected with a message. Is rejecting right,
   or should the flag silently stay on?
2. **Does the review panel's Approve call Accept?** I assume it should, so a reviewed translation arrives unmarked. Is
   there any approval that should *not* clear it?
3. **Which persistence is used where?** If the file or memory persistence is used in a deployment, the version check does
   not protect the flag there.
4. **A common editor indicator.** Is there one place in the document editors for the indicator and the tick box, or does
   each type need it?
5. **Is `FragmentWriter` the only writer?** This must be confirmed before P1 is estimated.
6. **How long are audit entries kept?** An accreditation use suggests they should outlive the document.
