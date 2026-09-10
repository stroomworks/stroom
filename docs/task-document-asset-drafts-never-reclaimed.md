# An abandoned document-asset draft is never reclaimed

**Component:** `stroom-document-asset` — `DocumentAssetDaoImpl`, and
`DocumentAssetPresenter.onRevertButtonClick` in `stroom-core-client`
**Severity:** medium. Unbounded growth in a `longblob` column, with a per-item ceiling of whatever
`documentAsset.maxUploadSize` allows (50 MiB by default), no operator visibility, and a trigger that
is the ordinary user action of changing your mind.
**Status:** diagnosed against a running instance; not fixed.

---

## The defect

Editing a document's assets writes rows to `visualisation_assets_draft`. Those rows are removed on
exactly four paths, and **every one requires a deliberate act**:

| Path | What triggers it |
|---|---|
| `saveDraftToLive()` | **Save.** Drafts are copied into the live table, then deleted |
| `revertDraftFromLive()` | **The Revert button, and nothing else.** Its only caller is `DocumentAssetPresenter.onRevertButtonClick`, behind a *"Are you sure you want to lose all your changes?"* confirmation |
| `updateDelete()` | Deleting one asset removes that asset's draft row |
| `deleteAssetsForDoc()` | Deleting the owning document takes its drafts with it |

(A private `deleteDuplicateDraftAssets()` also runs, but it deduplicates within a live edit rather
than reclaiming anything.)

**Nothing covers abandonment.** Closing the tab, navigating away, losing the network, or closing the
browser leaves the draft rows in place. There is no cleanup in the presenter's close handling, and
`ScheduledJobsBinder` does not appear anywhere in the module, so there is no housekeeping job either.
The only occurrence of the word "orphan" in the module is a javadoc note about document deletion.

So: upload an asset, do not save, close the tab. The row and its blob stay for the life of the
database.

## Observed

On an instance used only for testing:

```
visualisation_assets_draft: 2 rows, 2,163,993 bytes
```

One of those rows is a spreadsheet against a document that has **no matching live row** — staged,
never saved, never reverted. It was found only by querying the table directly.

Two megabytes is nothing. The point is that nothing bounds it and nothing reports it.

## Why it grows unnoticed

**It is per user, per document, per path.** The key is `(draft_user_uuid, owner_doc_uuid, path)`, so
several people experimenting with the same document each accumulate their own set, and none can see
the others'.

**There is no operator view.** No admin screen, count, or warning surfaces draft rows. Discovering
them requires database access, which is not a reasonable thing to ask of the person whose disk is
filling.

**Each row can be large.** `data` is a `longblob` and the upload path enforces only
`documentAsset.maxUploadSize` — 50 MiB by default — so a single abandoned upload can be that big.
The limit that exists bounds one upload, not the number of abandoned ones.

## The obvious fix is blocked by a missing column

The natural remedy is a scheduled sweep of drafts older than some age. **That cannot be written
today**, because a draft row carries no timestamp:

| Table | Columns |
|---|---|
| `visualisation_assets` | `id, `**`modified`**`, owner_doc_uuid, asset_uuid, path, path_hash, is_folder, data` |
| `visualisation_assets_draft` | `id, draft_user_uuid, owner_doc_uuid, asset_uuid, path, path_hash, is_folder, data` |

The live table records `modified`; the draft table does not. So any age-based reclamation needs a
schema change first, and that is the smallest piece of work that unblocks every other option.

## Suggested fix

**Add `modified` to the draft table, then sweep on a schedule.** In order:

1. **Migration** adding `modified` to `visualisation_assets_draft`, set on every write. Cheap, and
   nothing depends on the column being absent.
2. **A scheduled job** deleting draft rows untouched for longer than a configurable period — days
   rather than hours, since a legitimate edit can sit open over a weekend. This is the only option
   that is reliable, because it does not depend on the client doing anything.
3. **Report the footprint.** A count and total size, wherever cache and store sizes are already
   shown, so the growth is visible before it matters.

### What not to rely on

**Cleaning up when the editor closes.** It is the intuitive fix and it is the unreliable one: a
closed browser, a crashed tab, a lost network or a killed session all skip it, and those are exactly
the cases that produce an abandoned draft in the first place. Worth doing as well, never instead.

**Prompting the user on close.** Also worth doing, and also insufficient for the same reason. It
also changes behaviour a user may reasonably want — leaving an edit open across sessions is not
abuse, which is why the sweep threshold should be generous rather than aggressive.

## Verification

- Upload an asset, do not save, close the document. A draft row exists.
- Run the sweep with the threshold set below the row's age. The row and its blob are gone, and the
  live assets are untouched.
- Save normally, then run the sweep. Nothing is removed, because saving already cleared the drafts.
- Two users editing one document accumulate independent draft sets, and sweeping one user's stale
  rows leaves the other's alone.
- The reported footprint matches `SELECT COUNT(*), SUM(LENGTH(data)) FROM
  visualisation_assets_draft`.
