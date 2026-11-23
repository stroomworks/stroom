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

/// Promotion (simplification option iii, the `PromotionService` of `simplification-options.md`): what turns
/// a learned translation into the one that serves, and what takes one back. Drafts are written here, approved
/// or rejected here (A25), promoted here by the one path both modes take (option A), and retracted here
/// (A28, design 01 §11.6); a binding whose fragment was edited by hand is snapshotted afresh here (A72).
///
/// Nothing here runs a pipeline or asks the model, so it needs no pipeline scope, unlike the learning and
/// serving that call it.
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

    /// @param learnerBindings The bindings, drafts among them.
    /// @param states          Each learner's state, which says which draft it awaits.
    /// @param regressionSet   The records each binding was accepted on.
    /// @param snapshots       The configuration each binding serves with (A61).
    /// @param outputs         What each binding produced, counted when one is retracted.
    /// @param attempts        The runs, closed with what a person decided.
    /// @param writer          Copies a draft's fragment into the one that serves (A58).
    /// @param codes           Reads a fragment's documents, for its snapshot.
    /// @param places          Where a learner's fragments are kept.
    /// @param wake            Wakes a learner's pipeline once what it waited for is decided.
    /// @param clock           When a binding was promoted.
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

    /// Approve a draft (A25): the promotion, saying who approved it, which is kept beside the attempt
    /// and shown on the Learning Status tab (Track 2 step 4).
    ///
    /// @param doc         The document.
    /// @param bindingUuid The draft.
    /// @param reviewedBy  Who approved it; null where nobody is named.
    public void approve(final LearningFlowDoc doc, final String bindingUuid, final String reviewedBy) {
        final LearnerBindings.Held held = draft(doc, bindingUuid);
        promote(doc, awaitingReview(doc, held), held.binding());
        // The attempt that wrote the draft is no longer awaiting review (A28).
        record(() -> attempts.decided(doc.getUuid(), bindingUuid, AttemptStatus.PROMOTED, "Approved",
                reviewedBy));
    }

    /// Reject a draft (A25), saying who rejected it (Track 2 step 4): the binding goes, with its records; its
    /// documents stay, as every document does. The learner gives up with the reason, so the model is not
    /// asked the same question again until an operator says otherwise.
    ///
    /// @param doc         The document.
    /// @param bindingUuid The draft.
    /// @param reason      Why.
    /// @param reviewedBy  Who rejected it; null where nobody is named.
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
        record(() -> attempts.decided(doc.getUuid(), bindingUuid, AttemptStatus.REJECTED, "Rejected: " + reason,
                reviewedBy));
        // The streams waiting behind the draft (design 04 §3) are woken to error, as a given-up learner's
        // always are (A52, A80): left waiting, they would wait for a review that is no longer coming.
        wake(learner);
    }

    /// Take back a binding that is serving, by hand (A28, design 01 §11.6).
    ///
    /// A person has read what the binding is producing and decided it should not be: a reason no gate can
    /// see. The binding goes and the learner knows nothing again. What the binding produced was
    /// produced by a binding that has been withdrawn, and is counted rather than asked for (A129): a
    /// person who wants it as it would be now makes a reprocess filter on the pipeline.
    ///
    /// Not a removal, which is what it says: a row taken out of the table. Removing a learned binding leaves the
    /// learner thinking it is bound and leaves the streams it produced standing as though they were right.
    /// Retracting says they were not.
    ///
    /// Not `reject` either, which is A25's decision about a draft that never served anything, and which
    /// gives the learner up so it does not learn again. A retracted learner *does* learn again: its next
    /// stream starts afresh, which is the point — the binding was wrong, the feed is not.
    ///
    /// @param reason Why, in the person's words. It goes on the attempt, so that a person reading it a week
    ///               later knows what took the binding back.
    /// @param by     Who retracted it, since this overrides what the gate decided.
    /// @return How many streams the binding produced output for: what a person would reprocess, the one
    /// consequence a person retracting a binding cannot see for themselves and the one worth telling them.
    public int retract(final LearningFlowDoc doc, final String bindingUuid, final String reason, final String by) {
        final LearnerBindings.Held held = held(doc, bindingUuid);
        final Learner learner = held.learner();
        final Binding binding = held.binding();
        if (binding.isDraft()) {
            // A draft has served nothing, so there is nothing to take back. Deciding it is Approve or
            // Reject (A25), and saying so beats retracting something that was never bound.
            throw new IllegalArgumentException("Binding " + bindingUuid + " is a draft awaiting review: approve "
                                               + "or reject it rather than retracting it");
        }
        final String said = "Retracted by " + by + ": " + reason;
        learnerBindings.remove(learner, binding.getUuid());
        regressionSet.discard(binding.getUuid());
        snapshots.discard(binding.getUuid());
        // The learner knows nothing again rather than giving up: the binding was wrong, not the feed, and the
        // next stream learns afresh — unless a draft awaits review, which is still the learner's candidate
        // and a person's to decide.
        resetKeepingDraft(doc, learner);
        // By the fragment as well as the binding: a binding keeps its uuid when it is rebound (§7.3 rule 3), and
        // what an earlier generation produced was produced correctly by what was bound then.
        final List<Replayable> produced = outputs.boundBy(binding.getUuid(), binding.getPipeline() == null
                ? null
                : binding.getPipeline().getUuid());
        record(() -> attempts.settled(doc.getUuid(), bindingUuid, AttemptStatus.RETRACTED, said));
        return produced.size();
    }

    /// The promotion itself: the one path both modes take (simplification option A). Where a binding
    /// already serves the learner — the draft came of relearning — that binding is rebound to the draft's
    /// fragment and keeps its `uuid` and its history, so that what the learner produced before and after
    /// hangs off one binding; otherwise the draft itself goes live.
    ///
    /// Either way the learner's state starts afresh and its pipeline is woken, which puts through what
    /// waited for the review (design 04 §5.3) — all it needs, since those tasks were deferred, not
    /// errored.
    ///
    /// @param doc     The document.
    /// @param learner The learner the draft was learned for.
    /// @param draft   The draft.
    /// @return The binding that serves the learner from here on.
    Binding promote(final LearningFlowDoc doc, final Learner learner, final Binding draft) {
        final Optional<Binding> incumbent = incumbentOf(learnerBindings.forLearner(learner));
        final Binding promoted;
        if (incumbent.isPresent()) {
            // One fragment per learner (A58): the draft is copied into the fragment that serves, which
            // keeps its own DocRef, and the draft's documents go. The snapshot is taken afresh over the
            // serving fragment, since the draft's was taken over documents that no longer exist.
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

    /// A draft binding over a freshly written fragment, behind whatever serves the learner now (A25).
    ///
    /// @param doc      The document.
    /// @param learner  The learner.
    /// @param fragment The fragment the draft binds.
    /// @param score    What the candidate scored.
    /// @param boundary What one record is, where settled.
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
                // The configuration it serves with, kept (A61).
                .snapshot(snapshot(doc, draftUuid, fragment))
                .build();
        learnerBindings.append(learner, doc.getUuid(), draft);
        return draft;
    }

    /// The binding as it serves now (A72): where its fragment has been edited by hand since its snapshot
    /// was taken — the documents' versions differ — or it has no snapshot at all, having been written
    /// before snapshots existed, a snapshot is taken and the binding points at it. A promotion by hand.
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
            // A fragment whose documents cannot be read — deleted by hand — cannot be snapshotted, and is
            // not what this is about: the binding serves as it stands and the serve says what it finds.
            LOGGER.debug(() -> LogUtil.message("Binding {} could not be snapshotted: {}", binding.getUuid(),
                    e.getMessage()), e);
            return binding;
        }
    }

    /// A binding of this document, with whose it is: what an action that begins from a binding rather than a
    /// stream acts on (design 06 phase J slice 2b). A binding of another document is not this one's, whatever
    /// its uuid, since the document is what decided the person may act on it.
    LearnerBindings.Held held(final LearningFlowDoc doc, final String bindingUuid) {
        return learnerBindings.byUuid(bindingUuid)
                .filter(row -> row.docUuid().equals(doc.getUuid()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Binding " + bindingUuid + " is not a binding of this document"));
    }

    /// The learner's state starts afresh — not given up, not marked, its rolling score forgotten — but a
    /// draft awaiting review still awaits it: it is a person's to decide (A25), and nothing that calls this
    /// decided it. Forgotten, it would be a draft nothing awaits, serving the learner's streams a wait that
    /// approve and reject both refuse to end.
    private void resetKeepingDraft(final LearningFlowDoc doc, final Learner learner) {
        final Optional<String> draft = states.draftAwaiting(learner);
        states.reset(learner);
        draft.ifPresent(bindingUuid -> states.awaitReview(learner, doc.getUuid(), bindingUuid));
    }

    /// A draft of this document, with whose it is.
    private LearnerBindings.Held draft(final LearningFlowDoc doc, final String bindingUuid) {
        return learnerBindings.byUuid(bindingUuid)
                .filter(row -> row.docUuid().equals(doc.getUuid()) && row.binding().isDraft())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Binding " + bindingUuid + " is not a draft on document " + doc.getName()));
    }

    /// The learner whose review the draft is: the one whose state says it awaits that draft. A draft no
    /// learner awaits is one nothing would release on approval and nothing would give up on rejection.
    private Learner awaitingReview(final LearningFlowDoc doc, final LearnerBindings.Held draft) {
        if (!states.draftAwaiting(draft.learner()).filter(draft.binding().getUuid()::equals).isPresent()) {
            throw new IllegalStateException("Nothing awaits review under draft binding " + draft.binding().getUuid()
                                            + " on document " + doc.getName());
        }
        return draft.learner();
    }

    /// The binding a draft would replace: the one serving the learner.
    private static Optional<Binding> incumbentOf(final List<Binding> table) {
        return table.stream()
                .filter(binding -> !binding.isDraft())
                .findFirst();
    }

    /// A snapshot of a fragment as it stands, kept against a binding (A61, A70): the version the binding serves
    /// with, stamps its deferrals with and records its outputs under.
    private String snapshot(final LearningFlowDoc doc, final String bindingUuid, final DocRef fragment) {
        final FragmentCodes.Codes read = codes.read(fragment);
        final String id = UUID.randomUUID().toString();
        snapshots.keep(new Snapshots.Snapshot(id, doc.getUuid(), bindingUuid, fragment.getUuid(), read.structure(),
                read.code(), read.versions(), clock.millis()));
        return id;
    }

    /// What serves a learner (A124): its binding, or failing that the draft awaiting review.
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

    /// A run's record is written beside the binding it describes: failing to write it must not undo what
    /// the binding change did, so it is logged rather than thrown.
    private static void record(final Runnable write) {
        try {
            write.run();
        } catch (final RuntimeException e) {
            LOGGER.error(() -> LogUtil.message("The attempt could not be recorded: {}", e.getMessage()), e);
        }
    }
}
