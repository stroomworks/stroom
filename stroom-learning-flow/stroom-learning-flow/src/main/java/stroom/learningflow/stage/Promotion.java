/*
 * Copyright 2016-2026 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package stroom.learningflow.stage;

import stroom.docref.DocRef;
import stroom.learningflow.fragment.FragmentCodes;
import stroom.learningflow.fragment.FragmentWriter;
import stroom.learningflow.shared.AttemptStatus;
import stroom.learningflow.shared.Binding;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.RecordBoundary;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/// Makes a learned translation the one that serves a learner, and takes one back. Drafts are written,
/// approved and rejected here; both promotion modes (automatic and review) promote through the same
/// [#promote] method; a serving binding is retracted here; and a binding whose fragment has been edited by
/// hand is given a new snapshot here.
///
/// Nothing here runs a pipeline or asks the model, so unlike the learning and serving code that calls it, it
/// needs no pipeline scope.
public final class Promotion {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(Promotion.class);

    private final LearnerBindings learnerBindings;
    private final LearnerStates states;
    private final RegressionSet regressionSet;
    private final Snapshots snapshots;
    private final Outputs outputs;
    private final Attempts attempts;
    private final FragmentWriter writer;
    private final FragmentCodes codes;
    private final Places places;
    private final Wake wake;
    private final Clock clock;

    /// @param learnerBindings The bindings, including drafts.
    /// @param states          Each learner's state, which says which draft it awaits.
    /// @param regressionSet   The corpus records each binding was accepted on.
    /// @param snapshots       The configuration snapshots each binding serves with.
    /// @param outputs         What each binding produced, counted when one is retracted.
    /// @param attempts        The sessions, closed with what a person decided.
    /// @param writer          Copies a draft's fragment into the serving fragment, so that each learner keeps
    ///                        one serving fragment.
    /// @param codes           Reads a fragment's documents, to snapshot them.
    /// @param places          Where a learner's fragments are kept.
    /// @param wake            Wakes a learner's pipeline once what its streams waited for is decided.
    /// @param clock           The source of the time a binding is promoted.
    public Promotion(final LearnerBindings learnerBindings,
                     final LearnerStates states,
                     final RegressionSet regressionSet,
                     final Snapshots snapshots,
                     final Outputs outputs,
                     final Attempts attempts,
                     final FragmentWriter writer,
                     final FragmentCodes codes,
                     final Places places,
                     final Wake wake,
                     final Clock clock) {
        this.learnerBindings = learnerBindings;
        this.states = states;
        this.regressionSet = regressionSet;
        this.snapshots = snapshots;
        this.outputs = outputs;
        this.attempts = attempts;
        this.writer = writer;
        this.codes = codes;
        this.places = places;
        this.wake = wake;
        this.clock = clock;
    }

    /// Approves a draft, which promotes it, and records who approved it against the session that wrote it,
    /// for the Learning Status tab to show.
    ///
    /// @param doc         The document.
    /// @param bindingUuid The UUID of the draft binding.
    /// @param reviewedBy  Who approved it; null if nobody is named.
    public void approve(final LearningFlowDoc doc, final String bindingUuid, final String reviewedBy) {
        final LearnerBindings.Held held = draft(doc, bindingUuid);
        promote(doc, awaitingReview(doc, held), held.binding());
        // The session that wrote the draft is no longer awaiting review.
        Bookkeeping.record(LOGGER, () -> attempts.decided(doc.getUuid(), bindingUuid, AttemptStatus.PROMOTED,
                "Approved", reviewedBy));
    }

    /// Rejects a draft, recording who rejected it. The binding is removed, with its corpus records and
    /// snapshots; its documents are left in place, as documents always are. The learner gives up with the
    /// reason, so that the model is not asked the same question again until a person sends it back.
    ///
    /// @param doc         The document.
    /// @param bindingUuid The UUID of the draft binding.
    /// @param reason      Why it was rejected.
    /// @param reviewedBy  Who rejected it; null if nobody is named.
    public void reject(final LearningFlowDoc doc,
                       final String bindingUuid,
                       final String reason,
                       final String reviewedBy) {
        final LearnerBindings.Held held = draft(doc, bindingUuid);
        final Binding draft = held.binding();
        final Learner learner = awaitingReview(doc, held);
        learnerBindings.remove(learner, draft.getUuid());
        regressionSet.discard(draft.getUuid());
        snapshots.discard(draft.getUuid());
        states.reset(learner);
        states.giveUp(learner, doc.getUuid(), "Rejected: " + reason);
        Bookkeeping.record(LOGGER, () -> attempts.decided(doc.getUuid(), bindingUuid, AttemptStatus.REJECTED,
                "Rejected: " + reason, reviewedBy));
        // The streams waiting for the draft are woken so that they error, as a given-up learner's streams
        // always do. Left waiting, they would wait for a review that is no longer coming.
        wake(learner);
    }

    /// Retracts a serving binding at a person's request.
    ///
    /// A person has read what the binding is producing and decided it is wrong, for a reason no gate can
    /// detect. The binding is removed and the learner starts again from nothing. The streams the binding
    /// produced output for are counted, not reprocessed; a person who wants them as they would be now makes
    /// a reprocess filter on the pipeline.
    ///
    /// This is more than a removal of the binding's row. Removing the row alone would leave the learner's
    /// state as if it were bound and leave the streams it produced looking correct; retracting records
    /// that they were not.
    ///
    /// It is also not `reject`, which decides about a draft that never served anything, and gives the learner
    /// up so that it does not learn again. A retracted learner *does* learn again, starting afresh from its
    /// next stream, because the binding was wrong, not the feed.
    ///
    /// @param doc         The document the binding is under.
    /// @param bindingUuid The UUID of the binding to retract.
    /// @param reason      Why, in the person's words. It is recorded on the session that wrote the binding, so
    ///                    that a person reading it later knows why the binding was retracted.
    /// @param by          Who retracted it, since this overrides what the promotion gate decided.
    /// @return How many streams the binding produced output for, i.e. how many a person may want to
    ///         reprocess. It is the one consequence a person retracting a binding cannot see for themselves.
    public int retract(final LearningFlowDoc doc, final String bindingUuid, final String reason, final String by) {
        final LearnerBindings.Held held = held(doc, bindingUuid);
        final Learner learner = held.learner();
        final Binding binding = held.binding();
        if (binding.isDraft()) {
            // A draft has served nothing, so there is nothing to retract. It must be approved or rejected
            // instead, and saying so is clearer than retracting something that was never bound.
            throw new IllegalArgumentException("Binding " + bindingUuid + " is a draft awaiting review: approve "
                                               + "or reject it rather than retracting it");
        }
        final String said = "Retracted by " + by + ": " + reason;
        learnerBindings.remove(learner, binding.getUuid());
        regressionSet.discard(binding.getUuid());
        snapshots.discard(binding.getUuid());
        // The learner starts again from nothing rather than giving up, because the binding was wrong, not
        // the feed, and its next stream learns afresh. A draft awaiting review is kept, because it is still
        // the learner's candidate and a person's to decide.
        resetKeepingDraft(doc, learner);
        // Matched by fragment as well as binding, because a binding keeps its UUID when it is rebound, and
        // what an earlier fragment produced was produced correctly by what was bound then.
        final List<Replayable> produced = outputs.boundBy(binding.getUuid(), binding.getPipeline() == null
                ? null
                : binding.getPipeline().getUuid());
        Bookkeeping.record(LOGGER, () -> attempts.settled(doc.getUuid(), bindingUuid, AttemptStatus.RETRACTED, said));
        return produced.size();
    }

    /// Promotes a draft; both promotion modes use this. If a binding already serves the learner (the draft
    /// came from a relearning), that binding is rebound to the draft's fragment and keeps its `uuid` and
    /// its history, so that everything the learner produced before and after belongs to one binding.
    /// Otherwise the draft itself becomes the serving binding.
    ///
    /// Either way the learner's state is reset and its pipeline is woken, which processes the streams that
    /// waited. That is all they need, since their tasks were deferred, not errored.
    ///
    /// @param doc     The document.
    /// @param learner The learner the draft was learned for.
    /// @param draft   The draft binding.
    /// @return The binding that serves the learner from now on.
    Binding promote(final LearningFlowDoc doc, final Learner learner, final Binding draft) {
        final Optional<Binding> incumbent = incumbentOf(learnerBindings.forLearner(learner));
        final Binding promoted;
        if (incumbent.isPresent()) {
            // Each learner has one serving fragment, so the draft is copied into it. The serving fragment
            // keeps its own DocRef, and the draft's documents are deleted. A new snapshot is taken of the
            // serving fragment, because the draft's snapshot was taken of documents that no longer exist.
            final DocRef fragment = incumbent.get().getPipeline();
            writer.copyInto(fragment, draft.getPipeline(), places.of(learner).folder(), fragment.getName());
            promoted = incumbent.get().copy()
                    .snapshot(snapshot(doc, incumbent.get().getUuid(), fragment))
                    .promotedTimeMs(clock.millis())
                    .score(draft.getScore())
                    .recordBoundary(draft.getRecordBoundary())
                    .build();
            learnerBindings.replace(learner, doc.getUuid(), promoted);
            learnerBindings.remove(learner, draft.getUuid());
            regressionSet.move(draft.getUuid(), incumbent.get().getUuid(), doc.getRegressionCap());
            snapshots.discard(draft.getUuid());
        } else {
            promoted = draft.copy()
                    .draft(false)
                    .promotedTimeMs(clock.millis())
                    .build();
            learnerBindings.replace(learner, doc.getUuid(), promoted);
        }
        states.reset(learner);
        wake(learner);
        return promoted;
    }

    /// Writes a draft binding for a newly written fragment. Whatever serves the learner now goes on serving
    /// until the draft is approved.
    ///
    /// @param doc      The document.
    /// @param learner  The learner.
    /// @param fragment The fragment the draft binds.
    /// @param score    What the candidate scored.
    /// @param boundary What one record is, if it was settled.
    /// @return The draft, as it was written.
    Binding writeDraft(final LearningFlowDoc doc,
                               final Learner learner,
                               final DocRef fragment,
                               final Double score,
                               final RecordBoundary boundary) {
        final String draftUuid = UUID.randomUUID().toString();
        final Binding draft = Binding.builder()
                .uuid(draftUuid)
                .pipeline(fragment)
                .draft(true)
                .score(score)
                .recordBoundary(boundary)
                // Snapshot the configuration it will serve with.
                .snapshot(snapshot(doc, draftUuid, fragment))
                .build();
        learnerBindings.append(learner, doc.getUuid(), draft);
        return draft;
    }

    /// Returns the binding with a snapshot that matches its fragment as it is now. If the fragment has been
    /// edited by hand since its snapshot was taken (the documents' versions differ), or the binding has no
    /// snapshot because it was written before snapshots existed, a new snapshot is taken and the binding
    /// is updated to point at it. A hand edit is in effect a promotion made by a person.
    Binding freshened(final LearningFlowDoc doc, final Learner learner, final Binding binding) {
        if (binding.getPipeline() == null) {
            return binding;
        }
        try {
            if (binding.getSnapshot() != null) {
                final Optional<Snapshots.Snapshot> kept = snapshots.byId(binding.getSnapshot());
                if (kept.isPresent() && kept.get().versions().equals(codes.versions(binding.getPipeline()))) {
                    return binding;
                }
            }
            final Binding fresh = binding.copy().snapshot(snapshot(doc, binding.getUuid(), binding.getPipeline()))
                    .build();
            learnerBindings.replace(learner, doc.getUuid(), fresh);
            return fresh;
        } catch (final RuntimeException e) {
            // A fragment whose documents cannot be read (e.g. deleted by hand) cannot be snapshotted. That
            // is not this method's concern: the binding serves as it is, and serving reports what it finds.
            LOGGER.debug(() -> LogUtil.message("Binding {} could not be snapshotted: {}", binding.getUuid(),
                    e.getMessage()), e);
            return binding;
        }
    }

    /// Gets a binding of this document, with the learner it belongs to, for an action that starts from a
    /// binding rather than a stream. A binding under another document is refused, whatever its UUID, because
    /// the document is what the person's permission to act was checked against.
    LearnerBindings.Held held(final LearningFlowDoc doc, final String bindingUuid) {
        return learnerBindings.byUuid(bindingUuid)
                .filter(row -> row.docUuid().equals(doc.getUuid()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Binding " + bindingUuid + " is not a binding of this document"));
    }

    /// Resets the learner's state (not given up, not marked, rolling score cleared) but keeps any draft
    /// awaiting review, because that is a person's to decide and nothing that calls this decided it. If it
    /// were forgotten, the draft would still hold the learner's streams waiting, but approve and reject would
    /// both refuse it because nothing awaits it.
    private void resetKeepingDraft(final LearningFlowDoc doc, final Learner learner) {
        final Optional<String> draft = states.draftAwaiting(learner);
        states.reset(learner);
        draft.ifPresent(bindingUuid -> states.awaitReview(learner, doc.getUuid(), bindingUuid));
    }

    /// Gets a draft binding of this document, with the learner it belongs to.
    private LearnerBindings.Held draft(final LearningFlowDoc doc, final String bindingUuid) {
        return learnerBindings.byUuid(bindingUuid)
                .filter(row -> row.docUuid().equals(doc.getUuid()) && row.binding().isDraft())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Binding " + bindingUuid + " is not a draft on document " + doc.getName()));
    }

    /// Gets the learner whose state says it awaits this draft. A draft that no learner awaits is refused,
    /// because approving it would release nothing and rejecting it would give nothing up.
    private Learner awaitingReview(final LearningFlowDoc doc, final LearnerBindings.Held draft) {
        if (!states.draftAwaiting(draft.learner()).filter(draft.binding().getUuid()::equals).isPresent()) {
            throw new IllegalStateException("Nothing awaits review under draft binding " + draft.binding().getUuid()
                                            + " on document " + doc.getName());
        }
        return draft.learner();
    }

    /// Gets the binding a draft would replace: the one serving the learner.
    private static Optional<Binding> incumbentOf(final List<Binding> table) {
        return table.stream()
                .filter(binding -> !binding.isDraft())
                .findFirst();
    }

    /// Takes and stores a snapshot of a fragment as it is now, against a binding. Its id is the version the
    /// binding serves with, stamps its deferrals with and records its outputs under.
    private String snapshot(final LearningFlowDoc doc, final String bindingUuid, final DocRef fragment) {
        final FragmentCodes.Codes read = codes.read(fragment);
        final String id = UUID.randomUUID().toString();
        snapshots.keep(new Snapshots.Snapshot(id, doc.getUuid(), bindingUuid, fragment.getUuid(), read.structure(),
                read.code(), read.versions(), clock.millis()));
        return id;
    }

    /// Gets what serves a learner: its non-draft binding, or failing that the draft awaiting review.
    Optional<Binding> bindingFor(final Learner learner) {
        final List<Binding> bindings = learnerBindings.forLearner(learner);
        return bindings.stream()
                .filter(binding -> !binding.isDraft())
                .findFirst()
                .or(() -> bindings.stream().filter(Binding::isDraft).findFirst());
    }

    private void wake(final Learner learner) {
        wake.wake(learner.pipelineUuid());
    }
}
