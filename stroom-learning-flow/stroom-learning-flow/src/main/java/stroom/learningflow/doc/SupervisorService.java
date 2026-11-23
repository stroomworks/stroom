/*
 * Copyright 2026 Crown Copyright
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

package stroom.learningflow.doc;

import stroom.docref.DocRef;
import stroom.docstore.api.DocFinder;
import stroom.learningflow.element.LearnerDocuments;
import stroom.learningflow.element.PipelineZones;
import stroom.learningflow.element.ResolvedZone;
import stroom.learningflow.element.StageFactory;
import stroom.learningflow.learning.TargetSchemas;
import stroom.learningflow.shared.AmendTurnRequest;
import stroom.learningflow.shared.AttemptCriteria;
import stroom.learningflow.shared.BacklogStream;
import stroom.learningflow.shared.GrantRequest;
import stroom.learningflow.shared.GuidanceRequest;
import stroom.learningflow.shared.ImproveOutcome;
import stroom.learningflow.shared.ImproveRequest;
import stroom.learningflow.shared.LearnerCriteria;
import stroom.learningflow.shared.LearnerRef;
import stroom.learningflow.shared.LearnerRequest;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.LearningMode;
import stroom.learningflow.shared.RebuiltPrompt;
import stroom.learningflow.shared.RejectRequest;
import stroom.learningflow.shared.SupervisorAttempt;
import stroom.learningflow.shared.SupervisorGuidance;
import stroom.learningflow.shared.SupervisorLearner;
import stroom.learningflow.shared.SupervisorTurn;
import stroom.learningflow.stage.Attempts;
import stroom.learningflow.stage.Attempts.Page;
import stroom.learningflow.stage.Attempts.Recorded;
import stroom.learningflow.stage.Attempts.Turn;
import stroom.learningflow.stage.Decision;
import stroom.learningflow.stage.Decision.Sentinel;
import stroom.learningflow.stage.Deferrals;
import stroom.learningflow.stage.Deferrals.Deferral;
import stroom.learningflow.stage.DeletedPipelines;
import stroom.learningflow.stage.Guidance;
import stroom.learningflow.stage.Input;
import stroom.learningflow.stage.Inputs;
import stroom.learningflow.stage.Learner;
import stroom.learningflow.stage.LearnerBindings;
import stroom.learningflow.stage.LearningPipelines;
import stroom.learningflow.stage.Rebuilt;
import stroom.learningflow.stage.SpendBreaker;
import stroom.security.api.SecurityContext;
import stroom.security.shared.AppPermission;
import stroom.security.shared.DocumentPermission;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;
import stroom.util.pipeline.scope.PipelineScopeRunnable;
import stroom.util.shared.NullSafe;
import stroom.util.shared.PageRequest;
import stroom.util.shared.PageResponse;
import stroom.util.shared.PermissionException;
import stroom.util.shared.ResultPage;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/// The Supervisor's logic, behind its REST resource ([SupervisorResourceImpl]), which does the auditing and
/// delegates here (simplification option iii: resource, then service, then DAO).
///
/// The Supervisor of A28: attempts across every Learning Flow document, and what a person may do to
/// one they are reading.
///
/// What is learned for a feed belongs to the feed's pipeline, and is held to the pipeline's permissions: a
/// person who may view a pipeline may read its learners' sessions, waiting streams and notes (an attempt's
/// transcript carries what the model was told about that feed's data), and a person who may edit it may
/// act on them: leave a note, approve, reject, give more, relearn, give up, improve, accept or retract. A
/// pipeline since deleted has no permissions to ask; its learning is read by its document's VIEW instead,
/// and nothing more is done with it (A137). The lists across documents (the learners and attempts of every
/// document, the backlog) are of the documents a person may view, as an administrator's overview.
public class SupervisorService {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(SupervisorService.class);

    /// The most rows a page of the serving bindings may ask for, whatever it asks for.
    /// The attempts beside them have always clamped; these did not, so any person with VIEW on one
    /// document could ask for the whole table in one request, and a negative length reached the database
    /// as `LIMIT -1`.
    private static final int PAGE_LIMIT = 1000;

    private final Provider<Attempts> attemptsProvider;
    private final Provider<LearningFlowStore> storeProvider;
    private final Provider<StageFactory> stageFactoryProvider;
    private final Provider<PipelineScopeRunnable> pipelineScopeProvider;
    private final Provider<SecurityContext> securityContextProvider;
    private final Provider<Guidance> guidanceProvider;
    private final Provider<Deferrals> deferralsProvider;
    private final Provider<LearnerDocuments> learnerDocumentsProvider;
    private final Provider<DeletedPipelines> deletedPipelinesProvider;
    private final Provider<LearnerBindings> learnerBindingsProvider;
    private final Provider<LearningPipelines> learningPipelinesProvider;
    private final Provider<DocFinder> docFinderProvider;
    private final Provider<TargetSchemas> targetSchemasProvider;
    private final Provider<PipelineZones> pipelineZonesProvider;
    private final Provider<Inputs> inputsProvider;

    @Inject
    public SupervisorService(final Provider<Attempts> attemptsProvider,
                             final Provider<LearningFlowStore> storeProvider,
                             final Provider<StageFactory> stageFactoryProvider,
                             final Provider<PipelineScopeRunnable> pipelineScopeProvider,
                             final Provider<SecurityContext> securityContextProvider,
                             final Provider<Guidance> guidanceProvider,
                             final Provider<Deferrals> deferralsProvider,
                             final Provider<LearnerDocuments> learnerDocumentsProvider,
                             final Provider<DeletedPipelines> deletedPipelinesProvider,
                             final Provider<LearnerBindings> learnerBindingsProvider,
                             final Provider<LearningPipelines> learningPipelinesProvider,
                             final Provider<DocFinder> docFinderProvider,
                             final Provider<TargetSchemas> targetSchemasProvider,
                             final Provider<PipelineZones> pipelineZonesProvider,
                             final Provider<Inputs> inputsProvider) {
        this.attemptsProvider = attemptsProvider;
        this.storeProvider = storeProvider;
        this.stageFactoryProvider = stageFactoryProvider;
        this.pipelineScopeProvider = pipelineScopeProvider;
        this.securityContextProvider = securityContextProvider;
        this.guidanceProvider = guidanceProvider;
        this.deferralsProvider = deferralsProvider;
        this.learnerDocumentsProvider = learnerDocumentsProvider;
        this.deletedPipelinesProvider = deletedPipelinesProvider;
        this.learnerBindingsProvider = learnerBindingsProvider;
        this.learningPipelinesProvider = learningPipelinesProvider;
        this.docFinderProvider = docFinderProvider;
        this.targetSchemasProvider = targetSchemasProvider;
        this.pipelineZonesProvider = pipelineZonesProvider;
        this.inputsProvider = inputsProvider;
    }

    /// The learning pipelines (design 06 A115, A142): read and paged where the rows are, each named with
    /// its pipeline and document, and its oldest wait judged against its document's maximum deferral age
    /// (A57). The documents this person may read go into the query, as they do for the other lists.
    public ResultPage<SupervisorLearner> learners(final LearnerCriteria criteria) {
        final LearnerCriteria asked = criteria == null
                ? new LearnerCriteria()
                : criteria;
        final Map<String, DocRef> readable = readable(asked.getDocUuid());
        final Map<String, LearningFlowDoc> docs = documents(readable.values());
        final Set<String> learningOff = docs.values().stream()
                .filter(doc -> doc.getLearningMode() == LearningMode.DISABLED)
                .map(LearningFlowDoc::getUuid)
                .collect(Collectors.toSet());
        final PageRequest page = asked.getPageRequest() == null
                ? new PageRequest(0, 100)
                : asked.getPageRequest();
        final long offset = Math.max(0L, NullSafe.getOrElse(page.getOffset(), Integer::longValue, 0L));
        final LearningPipelines.Page found = learningPipelinesProvider.get().learners(readable.keySet(),
                learningOff, asked.getStates(), asked.isShowDeletedPipelines(), offset, pageLength(page));
        final DocFinder docFinder = docFinderProvider.get();
        final long now = System.currentTimeMillis();
        final Set<String> noFallback = noFallback(docs.values());
        final Map<String, String> spendPaused = spendPaused(docs.values());
        final List<SupervisorLearner> rows = found.learners().stream()
                .map(row -> row.named(docFinder.decorate(row.getPipeline()),
                        readable.get(row.getDoc().getUuid()),
                        row.getOldestWaitMs() != null
                        && now - row.getOldestWaitMs() > maxDeferralAge(docs, row.getDoc().getUuid()),
                        belowThreshold(docs.get(row.getDoc().getUuid()), row.getRollingScore()),
                        noFallback.contains(row.getDoc().getUuid()),
                        spendPaused.get(row.getDoc().getUuid())))
                .toList();
        return new ResultPage<>(rows, new PageResponse(offset, rows.size(), found.total(), true));
    }

    /// The learners of one pipeline, as its own editor shows them: those of its Learning Flow elements that
    /// have learned or are learning, under whichever documents they run under. Held to the pipeline's VIEW.
    public List<SupervisorLearner> pipelineLearners(final String pipelineUuid) {
        if (NullSafe.isBlankString(pipelineUuid) || !mayPipeline(pipelineUuid, DocumentPermission.VIEW)) {
            throw new PermissionException(securityContextProvider.get().getUserRef(),
                    "You do not have permission to read this pipeline");
        }
        final Map<String, DocRef> every = new LinkedHashMap<>();
        storeProvider.get().listDocuments().forEach(docRef -> every.put(docRef.getUuid(), docRef));
        final Map<String, LearningFlowDoc> docs = documents(every.values());
        final Set<String> learningOff = docs.values().stream()
                .filter(doc -> doc.getLearningMode() == LearningMode.DISABLED)
                .map(LearningFlowDoc::getUuid)
                .collect(Collectors.toSet());
        final LearningPipelines.Page found = learningPipelinesProvider.get().learners(every.keySet(),
                learningOff, List.of(), false, pipelineUuid, 0L, PAGE_LIMIT);
        final DocFinder docFinder = docFinderProvider.get();
        final long now = System.currentTimeMillis();
        final Set<String> noFallback = noFallback(docs.values());
        final Map<String, String> spendPaused = spendPaused(docs.values());
        return found.learners().stream()
                .map(row -> row.named(docFinder.decorate(row.getPipeline()),
                        every.get(row.getDoc().getUuid()),
                        row.getOldestWaitMs() != null
                        && now - row.getOldestWaitMs() > maxDeferralAge(docs, row.getDoc().getUuid()),
                        belowThreshold(docs.get(row.getDoc().getUuid()), row.getRollingScore()),
                        noFallback.contains(row.getDoc().getUuid()),
                        spendPaused.get(row.getDoc().getUuid())))
                .map(this::withTimeZone)
                .toList();
    }

    /// The document a learner runs under, for its pipeline's editor to show where it stands: its flow,
    /// pass mark and limits. Held to the pipeline's VIEW: the settings are how that pipeline's feed is
    /// learned, which a person who may see the pipeline may read without being able to open the document.
    public LearningFlowDoc settings(final String docUuid, final String pipelineUuid, final String elementId) {
        readable(learnerOf(docUuid, new LearnerRef(pipelineUuid, elementId)), docUuid);
        return document(docUuid);
    }

    /// What waits on one learner (design 06 A115), oldest first, a page at a time.
    public ResultPage<BacklogStream> waiting(final String docUuid,
                                             final String pipelineUuid,
                                             final String elementId,
                                             final PageRequest pageRequest) {
        final Learner learner = readable(learnerOf(docUuid, new LearnerRef(pipelineUuid, elementId)), docUuid);
        final PageRequest page = pageRequest == null
                ? new PageRequest(0, 100)
                : pageRequest;
        final long offset = Math.max(0L, NullSafe.getOrElse(page.getOffset(), Integer::longValue, 0L));
        final Deferrals.Waiting waiting = deferralsProvider.get().waitingOn(learner, offset, pageLength(page));
        final DocRef doc = new DocRef(LearningFlowDoc.TYPE, docUuid);
        final long maxAge = maxDeferralAge(documents(List.of(doc)), docUuid);
        final long now = System.currentTimeMillis();
        final List<BacklogStream> rows = waiting.rows().stream()
                .map(row -> new BacklogStream(doc, row.inputId(), row.feed(), row.type(), row.failedAgainst(),
                        row.reason(), row.failingRecords(), row.firstDeferredMs(), row.attempts(),
                        row.raised(maxAge, now)))
                .toList();
        return new ResultPage<>(rows, new PageResponse(offset, rows.size(), waiting.total(), true));
    }

    /// Send a learner back to learn afresh from its next stream (A28, A29), from its row.
    public Boolean relearnLearner(final String docUuid, final LearnerRequest request) {
        final Learner learner = changeable(docUuid, request);
        final String who = securityContextProvider.get().getUserIdentityForAudit();
        final String reason = NullSafe.isBlankString(request.getReason())
                ? "Sent back to be learned again by " + who
                : "Sent back to be learned again by " + who + ": " + request.getReason();
        final LearningFlowDoc doc = document(docUuid);
        stageFactoryProvider.get().supervisorActions().relearn(doc, learner, reason);
        return Boolean.TRUE;
    }

    /// Give a learner up (design 06 A115). The reason is asked for rather than assumed: its streams error
    /// with it.
    public Boolean giveUp(final String docUuid, final LearnerRequest request) {
        if (request == null || NullSafe.isBlankString(request.getReason())) {
            throw new IllegalArgumentException("Giving a learner up says why: its streams error with the "
                                               + "reason");
        }
        final Learner learner = changeable(docUuid, request);
        final String by = securityContextProvider.get().getUserIdentityForAudit();
        final LearningFlowDoc doc = document(docUuid);
        stageFactoryProvider.get().supervisorActions().giveUp(doc, learner, request.getReason(), by);
        return Boolean.TRUE;
    }

    /// The learner a request about a row names, where this person may edit its pipeline and the pipeline has
    /// not been deleted (A137).
    private Learner changeable(final String docUuid, final LearnerRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Say which learner: its pipeline and element");
        }
        return changeable(live(learnerOf(docUuid, request.getLearner())));
    }

    /// The documents, read once each; one that cannot be read is left out, and its rows take the defaults.
    private Map<String, LearningFlowDoc> documents(final Collection<DocRef> docRefs) {
        final Map<String, LearningFlowDoc> docs = new LinkedHashMap<>();
        for (final DocRef docRef : docRefs) {
            try {
                final LearningFlowDoc doc = storeProvider.get().readDocument(docRef);
                if (doc != null) {
                    docs.put(docRef.getUuid(), doc);
                }
            } catch (final RuntimeException e) {
                LOGGER.debug(() -> LogUtil.message("Document {} could not be read: {}", docRef.getUuid(),
                        e.getMessage()));
            }
        }
        return docs;
    }

    /// The documents whose target schema isn't event-logging, so has no marked Unknown for *Finish with
    /// Unknowns* to write (task 7, W1-3). A document naming no target, or one this node can't read, is
    /// event-logging as it always was.
    private Set<String> noFallback(final Collection<LearningFlowDoc> docs) {
        final TargetSchemas targetSchemas = targetSchemasProvider.get();
        return docs.stream()
                .filter(doc -> targetSchemas.of(doc)
                        .map(target -> !target.isEventLogging())
                        .orElse(false))
                .map(LearningFlowDoc::getUuid)
                .collect(Collectors.toSet());
    }

    /// A learner's row with the zone its pipeline reads timestamps in (decision N6, Track 2 step 4): the
    /// one a stream with no `TimeZone` header is read in, where it comes from, and what is wrong with the
    /// pipeline's property, so that a surprising date can be traced to what set the zone.
    private SupervisorLearner withTimeZone(final SupervisorLearner row) {
        final ResolvedZone zone = pipelineZonesProvider.get().of(learner(row.getLearner()), null);
        return row.withTimeZone(zone.id(), zone.source(), zone.problem());
    }

    /// Why each document can't open a learning run now, where a spend ceiling has been reached (A24, Track 2
    /// step 4): its own, or the cluster's. Asked of the breaker the stages use, so the list says exactly
    /// what holds the streams back.
    private Map<String, String> spendPaused(final Collection<LearningFlowDoc> docs) {
        final SpendBreaker breaker = stageFactoryProvider.get().breaker();
        final Map<String, String> paused = new HashMap<>();
        for (final LearningFlowDoc doc : docs) {
            breaker.tripped(doc).ifPresent(reason -> paused.put(doc.getUuid(), reason));
        }
        return paused;
    }

    /// Whether a learner's rolling score is below its document's relearn threshold (design 04 §6): where
    /// the document has one and the learner has served enough to be scored.
    private static boolean belowThreshold(final LearningFlowDoc doc, final Double rollingScore) {
        return doc != null
               && rollingScore != null
               && rollingScore < doc.getRelearnThreshold();
    }

    private static long maxDeferralAge(final Map<String, LearningFlowDoc> docs, final String docUuid) {
        final LearningFlowDoc doc = docs.get(docUuid);
        return doc == null
                ? LearningFlowDoc.builder().build().getMaxDeferralAgeMs()
                : doc.getMaxDeferralAgeMs();
    }

    /// What is waiting on a session (design 04 §6), a row per stream, the raised ones first and then the
    /// longest waiting: the backlog an operator watches form and drain. Raised is computed as it is read,
    /// against each document's maximum deferral age (A78, A79).
    public ResultPage<BacklogStream> backlog(final String docUuid, final PageRequest pageRequest) {
        final PageRequest page = pageRequest == null
                ? new PageRequest(0, 100)
                : pageRequest;
        final Map<String, DocRef> readable = readable(docUuid);
        final long now = System.currentTimeMillis();
        final List<BacklogStream> all = new ArrayList<>();
        for (final DocRef docRef : readable.values()) {
            // A document that cannot be read — deleted since it was listed — must not take the whole
            // backlog down with it: its rows are shown against the default age.
            long maxAge = LearningFlowDoc.builder().build().getMaxDeferralAgeMs();
            try {
                final LearningFlowDoc doc = storeProvider.get().readDocument(docRef);
                if (doc != null) {
                    maxAge = doc.getMaxDeferralAgeMs();
                }
            } catch (final RuntimeException e) {
                LOGGER.debug(() -> LogUtil.message("Document {} could not be read for the backlog: {}",
                        docRef.getUuid(), e.getMessage()));
            }
            for (final Deferral row : deferralsProvider.get().forDocument(docRef.getUuid())) {
                all.add(new BacklogStream(docRef, row.inputId(), row.feed(), row.type(), row.failedAgainst(),
                        row.reason(), row.failingRecords(), row.firstDeferredMs(), row.attempts(),
                        row.raised(maxAge, now)));
            }
        }
        all.sort(Comparator.comparing(BacklogStream::isRaised).reversed()
                .thenComparingLong(BacklogStream::getFirstDeferredMs));
        final int offset = (int) Math.max(0L, NullSafe.getOrElse(page.getOffset(), Integer::longValue, 0L));
        final int length = pageLength(page);
        final List<BacklogStream> rows = all.stream().skip(offset).limit(length).toList();
        return new ResultPage<>(rows, new PageResponse((long) offset, rows.size(), (long) all.size(), true));
    }

    /// Give a parked session more to spend (A77), and carry it on.
    public SupervisorAttempt grant(final long id, final GrantRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Nothing was granted");
        }
        final Recorded attempt = live(change(id));
        stageFactoryProvider.get().supervisorActions().grant(documentOf(attempt), id, request.getTokens(),
                request.getMs());
        return detail(read(id));
    }

    /// Send a parked session to the exhaustion step (A65), and carry it on.
    public SupervisorAttempt exhaust(final long id) {
        final Recorded attempt = live(change(id));
        stageFactoryProvider.get().supervisorActions().exhaust(documentOf(attempt), id);
        return detail(read(id));
    }

    /// The learning runs a person may read, newest first, as the criteria narrow them.
    ///
    /// @param criteria Which runs, and which page of them.
    /// @return The page.
    public ResultPage<SupervisorAttempt> find(final AttemptCriteria criteria) {
        final AttemptCriteria asked = criteria == null
                ? new AttemptCriteria()
                : criteria;
        // The documents this person may see go into the query rather than filtering its answer: a count
        // taken before the filtering would tell them how many attempts exist on documents they may not
        // see, and the pages they turned would come back short with no way to reach the end. One learner's
        // sessions, as its pipeline's editor asks for them, are held to the pipeline instead, and may be
        // under any document.
        final Page page = attemptsProvider.get().found(asked, asked.getLearner() == null
                ? mayRead(asked.getDocUuid())
                : learnersDocuments(asked.getLearner()));
        // Which of the page's pipelines are deleted, asked once for the page rather than once a row.
        final Set<String> deleted = deletedPipelinesProvider.get().deletedOf(page.attempts().stream()
                .map(attempt -> attempt.attempt().learner().pipelineUuid())
                .collect(Collectors.toSet()));
        final List<SupervisorAttempt> rows = page.attempts().stream()
                .map(attempt -> attempt(attempt, List.of(),
                        deleted.contains(attempt.attempt().learner().pipelineUuid())))
                .toList();
        final long offset = asked.getPageRequest() == null || asked.getPageRequest().getOffset() == null
                ? 0L
                : asked.getPageRequest().getOffset();
        return new ResultPage<>(rows, new PageResponse(offset, rows.size(), page.total(), true));
    }

    /// Ask for a binding that is already serving to be made better (A46).
    ///
    /// Held to EDIT on the binding's pipeline, as approving a draft is: it spends a model's tokens and may
    /// rebind the learner.
    ///
    /// The request opens the attempt and parks it; the worker asks the model (A46). A model conversation
    /// is minutes long, and holding an HTTP thread for it would not survive the node restarting or the
    /// browser giving up. Nothing waits on it: the incumbent serves every stream throughout, and a
    /// candidate takes over only through the ordinary gate.
    ///
    /// So what comes back says the improvement was asked for, not what it came to. What it comes to
    /// appears on the Learning Status tab, as every other run's does.
    public ImproveOutcome improve(final String docUuid, final String bindingUuid, final ImproveRequest request) {
        changeableBinding(docUuid, bindingUuid);
        final String by = securityContextProvider.get().getUserIdentityForAudit();
        final String message = request == null
                ? null
                : request.getMessage();
        final LearningFlowDoc doc = document(docUuid);
        final Decision decision = pipelineScopeProvider.get().scopeResult(() ->
                stageFactoryProvider.get().create().improve(doc, bindingUuid, message, by).decision());
        // A refusal is the one thing that is decided here and now — the binding is a draft, the records
        // have gone, learning is off — and it is said rather than left to show up as an abandoned run.
        if (decision instanceof final Sentinel refused) {
            return new ImproveOutcome(refused.reason());
        }
        return new ImproveOutcome("Asked. The AI is working on it; what it comes to will show here.");
    }

    /// Take back a binding that is serving (A28, design 01 §11.6).
    ///
    /// The reason is asked for rather than assumed: it is recorded against the attempt that wrote the binding.
    ///
    /// Answers how many streams the binding produced output for, which is the one consequence the person
    /// cannot see for themselves, and what they would reprocess (A129).
    ///
    /// Held to EDIT on the binding's pipeline, as approving a draft is: it changes what serves it.
    public Integer retract(final String docUuid, final String bindingUuid, final RejectRequest request) {
        if (request == null || NullSafe.isBlankString(request.getReason())) {
            throw new IllegalArgumentException("A retraction says why: the reason is recorded against "
                                               + "the attempt that wrote the binding");
        }
        changeableBinding(docUuid, bindingUuid);
        final String by = securityContextProvider.get().getUserIdentityForAudit();
        final LearningFlowDoc doc = document(docUuid);
        return stageFactoryProvider.get().promotion().retract(doc, bindingUuid, request.getReason(), by);
    }

    /// What has been said to a learner (A46), oldest first.
    public List<SupervisorGuidance> guidance(final String docUuid,
                                             final String pipelineUuid,
                                             final String elementId) {
        return said(readable(learnerOf(docUuid, new LearnerRef(pipelineUuid, elementId)), docUuid));
    }

    /// Tell a learner something, without asking for anything to be run.
    ///
    /// Held to EDIT on the pipeline rather than VIEW: what is written here is carried into every question
    /// the learner asks from now on, which is a change to how its feed is learned.
    public List<SupervisorGuidance> hint(final String docUuid, final GuidanceRequest request) {
        if (request == null || NullSafe.isBlankString(request.getMessage())) {
            throw new IllegalArgumentException("There is nothing to record: the message is empty");
        }
        final Learner learner = changeable(live(learnerOf(docUuid, request.getLearner())));
        guidanceProvider.get().given(learner, docUuid, request.getMessage(),
                securityContextProvider.get().getUserIdentityForAudit());
        return said(learner);
    }

    /// Take back something said to a learner. A hint that turned out to be wrong is worse than no hint:
    /// it is carried into every question the learner asks until it is withdrawn.
    ///
    /// The turns that already carried it still say they did (A45), which is what a re-walk replays: what
    /// was withdrawn stops being carried, and does not stop having been.
    public List<SupervisorGuidance> withdraw(final String docUuid,
                                             final long id,
                                             final String pipelineUuid,
                                             final String elementId) {
        final Learner learner = changeable(live(learnerOf(docUuid,
                new LearnerRef(pipelineUuid, elementId))));
        guidanceProvider.get().withdraw(docUuid, id);
        return said(learner);
    }

    /// One learning run with its turns, held to its pipeline's VIEW.
    ///
    /// @param id The run.
    /// @return It.
    public SupervisorAttempt fetch(final long id) {
        return detail(read(id));
    }

    /// A person's answer in place of a turn's (A28), held to its pipeline's EDIT; the run is left for the
    /// learning job to carry on.
    ///
    /// @param id      The run.
    /// @param number  The turn.
    /// @param request The answer.
    /// @return The run as it now stands.
    public SupervisorAttempt amend(final long id, final int number, final AmendTurnRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("No answer was given for turn " + number);
        }
        final Recorded attempt = stillUnder(live(change(id)));
        final String answeredBy = securityContextProvider.get().getUserIdentityForAudit();
        stageFactoryProvider.get().supervisorActions().amend(documentOf(attempt), id, number, request.getAnswer(),
                answeredBy);
        return detail(read(id));
    }

    /// Rebuilt rather than stored (N9): stored, the prompt would be a copy of the stream's text readable
    /// without the data permission and outliving the stream under retention. Whatever stops it being
    /// rebuilt is said, never guessed past.
    public RebuiltPrompt prompt(final long id, final int number) {
        final Recorded attempt = read(id);
        if (attempt.turns().stream().noneMatch(turn -> turn.number() == number)) {
            throw new IllegalArgumentException("Attempt " + id + " has no turn " + number);
        }
        if (!securityContextProvider.get().hasAppPermission(AppPermission.VIEW_DATA_PERMISSION)) {
            return RebuiltPrompt.refused("The prompt carries the stream's own text, so it is shown only to "
                                         + "someone who may view data.");
        }
        if (attempt.attempt().isImprovement() || attempt.attempt().inputId() == null) {
            return RebuiltPrompt.refused("This run improved a translation from the records it was accepted "
                                         + "on rather than from a stream, so its prompt can't be rebuilt.");
        }
        final Optional<Input> input = inputsProvider.get().byId(attempt.attempt().inputId());
        if (input.isEmpty()) {
            return RebuiltPrompt.refused("The stream this run learned from has been deleted, or can't be "
                                         + "read, so its prompt can't be rebuilt.");
        }
        final List<Optional<Rebuilt>> rebuilt = new ArrayList<>();
        onStage(attempt, (stage, doc) -> rebuilt.add(stage.rebuild(doc, id, input.get())));
        if (rebuilt.isEmpty() || rebuilt.getFirst().isEmpty()) {
            return RebuiltPrompt.refused("This run was asked before the words it used were kept, so its "
                                         + "prompt can't be rebuilt without guessing.");
        }
        final Rebuilt prompts = rebuilt.getFirst().get();
        final String prompt = prompts.prompts().get(number);
        if (prompt == null) {
            return RebuiltPrompt.refused("This question was asked in a later round of the run, after the "
                                         + "whole stream was checked, or after the Learning Flow's steps "
                                         + "changed; only the first round can be rebuilt.");
        }
        final List<String> notes = new ArrayList<>();
        notes.add("The questions and answers before it went with it, as the turns above show them.");
        final Integer withdrawn = prompts.withdrawn().get(number);
        if (withdrawn != null) {
            notes.add(withdrawn + " note(s) it carried have since been withdrawn, and aren't shown.");
        }
        if (prompts.olderThanBuiltIn()) {
            notes.add("Stroom has been upgraded since this run. The feedback is derived again, so the checks' "
                      + "wording in it may differ from what was sent.");
        }
        return new RebuiltPrompt(prompt, notes, null);
    }

    /// Approve the draft a run wrote (A25), held to its pipeline's EDIT, recording who approved it.
    ///
    /// @param id The run.
    /// @return The run as it now stands.
    public SupervisorAttempt approve(final long id) {
        final Recorded attempt = live(change(id));
        final String by = securityContextProvider.get().getUserIdentityForAudit();
        stageFactoryProvider.get().promotion().approve(documentOf(attempt), bindingOf(attempt), by);
        return detail(read(id));
    }

    /// Reject the draft a run wrote (A25), held to its pipeline's EDIT, recording who rejected it and why.
    ///
    /// @param id      The run.
    /// @param request Why.
    /// @return The run as it now stands.
    public SupervisorAttempt reject(final long id, final RejectRequest request) {
        if (request == null || request.getReason() == null || request.getReason().isBlank()) {
            throw new IllegalArgumentException("A rejection says why: the learner gives up with the reason");
        }
        final Recorded attempt = live(change(id));
        final String by = securityContextProvider.get().getUserIdentityForAudit();
        stageFactoryProvider.get().promotion().reject(documentOf(attempt), bindingOf(attempt), request.getReason(),
                by);
        return detail(read(id));
    }

    private List<SupervisorGuidance> said(final Learner learner) {
        return guidanceProvider.get().standing(learner).stream()
                .map(given -> new SupervisorGuidance(given.id(), given.message(), given.author(),
                        given.timeMs()))
                .toList();
    }

    /// How many rows a page may hold: what was asked for, within what the server will give. A request
    /// is not to be trusted with either end of it — an unbounded length hands over the whole table in
    /// one call, and a negative one reaches the database as `LIMIT -1` and answers with a server error
    /// rather than a refusal.
    private static int pageLength(final PageRequest page) {
        final int asked = NullSafe.getOrElse(page.getLength(), Integer::intValue, 100);
        return Math.max(1, Math.min(asked, PAGE_LIMIT));
    }

    /// The documents this person may read, by uuid and keeping their names, narrowed to the one they
    /// asked about if they asked about one. A handful per installation, so asking the store about each
    /// costs nothing next to the query it saves.
    private Map<String, DocRef> readable(final String asked) {
        final Map<String, DocRef> readable = new LinkedHashMap<>();
        storeProvider.get().listDocuments().stream()
                .filter(docRef -> asked == null || asked.equals(docRef.getUuid()))
                .filter(docRef -> may(docRef.getUuid(), DocumentPermission.VIEW))
                .forEach(docRef -> readable.put(docRef.getUuid(), docRef));
        return readable;
    }

    /// The learner a request names, as the row it was made from carried it (design 06 phase J slice 2b).
    private static Learner learner(final LearnerRef ref) {
        if (ref == null || NullSafe.isBlankString(ref.getPipelineUuid())
            || NullSafe.isBlankString(ref.getElementId())) {
            throw new IllegalArgumentException("Say which learner: its pipeline and element");
        }
        return new Learner(ref.getPipelineUuid(), ref.getElementId());
    }

    /// The learner a request names, held to the document the request was permitted on. The request names
    /// the two separately and the permission was the document's: without this, a person who may edit one
    /// document could act on another's learner by naming it.
    private Learner learnerOf(final String docUuid, final LearnerRef ref) {
        final Learner learner = learner(ref);
        if (!runsUnder(learner, docUuid)) {
            throw new IllegalArgumentException("Element " + learner.elementId() + " of pipeline "
                                               + learner.pipelineUuid() + " does not run under this document");
        }
        return learner;
    }

    /// An attempt whose pipeline has not been deleted. A deleted pipeline's learning is kept to be read
    /// until it ages out, and nothing more is done with it (design 06 A137): what a person would change
    /// from here would serve, or teach, a pipeline that will never run again.
    private Recorded live(final Recorded attempt) {
        live(attempt.attempt().learner());
        return attempt;
    }

    /// The learner, where its pipeline has not been deleted.
    private Learner live(final Learner learner) {
        if (deletedPipelinesProvider.get().isDeleted(learner.pipelineUuid())) {
            throw new IllegalStateException("The pipeline of element " + learner.elementId() + " has been "
                                            + "deleted: what it learned is kept to be read until it ages "
                                            + "out, and nothing more is done with it");
        }
        return learner;
    }

    /// A binding this person may change: its learner runs under the document named, its pipeline has not
    /// been deleted, and they may edit the pipeline. A binding that isn't there answers as one they may not
    /// change, or the answer would say which bindings exist.
    private void changeableBinding(final String docUuid, final String bindingUuid) {
        final Learner learner = learnerBindingsProvider.get().byUuid(bindingUuid)
                .map(held -> held.learner())
                .filter(held -> runsUnder(held, docUuid))
                .orElseThrow(() -> new PermissionException(securityContextProvider.get().getUserRef(),
                        "You do not have permission to change binding " + bindingUuid));
        changeable(live(learner));
    }

    /// An attempt whose learner still runs under the document it was learning for. An attempt is history
    /// and keeps its document, but a change made from one is a change to its learner: once the pipeline
    /// has been pointed at another document, the old document's editors are no longer the ones to make it.
    private Recorded stillUnder(final Recorded attempt) {
        if (!runsUnder(attempt.attempt().learner(), attempt.attempt().docUuid())) {
            throw new IllegalStateException("Attempt " + attempt.id() + " was learning for a pipeline that no "
                                            + "longer runs under its document; act on it from the document "
                                            + "the pipeline names now");
        }
        return attempt;
    }

    /// Whether the learner runs under the document: as its pipeline says now, or for a pipeline since
    /// deleted, which says nothing, as the learning pipelines list says, so that what it learned can still
    /// be read from the document it is listed under (A137) — whether or not it ever bound anything.
    /// Nothing is changed for a deleted pipeline's learner, so this lets a person read and no more.
    private boolean runsUnder(final Learner learner, final String docUuid) {
        final Optional<String> runsUnder = deletedPipelinesProvider.get().isDeleted(learner.pipelineUuid())
                ? learningPipelinesProvider.get().documentOf(learner)
                : learnerDocumentsProvider.get().documentOf(learner);
        return runsUnder.filter(docUuid::equals).isPresent();
    }

    private static LearnerRef ref(final Learner learner) {
        return new LearnerRef(learner.pipelineUuid(), learner.elementId());
    }

    private LearningFlowDoc document(final String docUuid) {
        final LearningFlowDoc doc = storeProvider.get().readDocument(
                new DocRef(LearningFlowDoc.TYPE, docUuid));
        if (doc == null) {
            throw new IllegalArgumentException("There is no Learning Flow " + docUuid);
        }
        return doc;
    }

    /// An attempt a person may read. An attempt they may not see and an attempt that never existed
    /// answer the same way, or the answer itself says which documents exist and what has been learned
    /// for them.
    private Recorded read(final long id) {
        return permitted(id, DocumentPermission.VIEW);
    }

    /// An attempt a person may change. Approving a draft puts its binding in force, rejecting one gives a
    /// learner up, and running an attempt again spends a model's tokens: these change what serves the
    /// attempt's pipeline and are held to the same permission as editing the pipeline.
    private Recorded change(final long id) {
        return permitted(id, DocumentPermission.EDIT);
    }

    private Recorded permitted(final long id, final DocumentPermission permission) {
        final Recorded attempt = attemptsProvider.get().byId(id).orElse(null);
        if (attempt == null
            || !mayLearner(attempt.attempt().learner(), attempt.attempt().docUuid(), permission)) {
            throw new PermissionException(securityContextProvider.get().getUserRef(),
                    "You do not have permission to read attempt " + id);
        }
        return attempt;
    }

    /// The learner, where this person may view its pipeline (or, for a pipeline since deleted, its document).
    private Learner readable(final Learner learner, final String docUuid) {
        if (!mayLearner(learner, docUuid, DocumentPermission.VIEW)) {
            throw new PermissionException(securityContextProvider.get().getUserRef(),
                    "You do not have permission to read the pipeline of element " + learner.elementId());
        }
        return learner;
    }

    /// The learner, where this person may edit its pipeline. Called once the pipeline is known to be live.
    private Learner changeable(final Learner learner) {
        if (!mayPipeline(learner.pipelineUuid(), DocumentPermission.EDIT)) {
            throw new PermissionException(securityContextProvider.get().getUserRef(),
                    "You do not have permission to change how the pipeline of element " + learner.elementId()
                    + " learns");
        }
        return learner;
    }

    /// Whether this person may do a thing to a learner: by its pipeline's permission, or, for a pipeline since
    /// deleted, which has none to ask, by its document's, to read alone. Nothing is changed for a deleted
    /// pipeline's learner (A137), so a change asked of one is refused.
    private boolean mayLearner(final Learner learner, final String docUuid, final DocumentPermission permission) {
        return access().mayLearner(learner, docUuid, permission);
    }

    private boolean mayPipeline(final String pipelineUuid, final DocumentPermission permission) {
        return access().mayPipeline(pipelineUuid, permission);
    }

    private boolean may(final String docUuid, final DocumentPermission permission) {
        return access().mayDocument(docUuid, permission);
    }

    // The rules, shared with the Quality Assurance tab's resource
    private LearnerAccess access() {
        return new LearnerAccess(securityContextProvider, deletedPipelinesProvider);
    }

    /// The documents a learner's sessions may be under, where this person may read its pipeline: every
    /// document, since its pipeline may have run under more than one; none where they may not.
    private List<String> learnersDocuments(final LearnerRef ref) {
        final Learner learner = learner(ref);
        return storeProvider.get().listDocuments().stream()
                .map(DocRef::getUuid)
                .filter(uuid -> mayLearner(learner, uuid, DocumentPermission.VIEW))
                .toList();
    }

    /// The documents this person may see, narrowed to the one they asked about if they asked about one.
    /// A handful of documents per installation, so asking the store about each costs nothing next to the
    /// query it saves.
    private List<String> mayRead(final String asked) {
        return storeProvider.get().listDocuments().stream()
                .map(DocRef::getUuid)
                .filter(uuid -> asked == null || asked.equals(uuid))
                .filter(uuid -> may(uuid, DocumentPermission.VIEW))
                .toList();
    }

    private String bindingOf(final Recorded attempt) {
        if (attempt.bindingUuid() == null) {
            throw new IllegalStateException("Attempt " + attempt.id() + " drafted no binding to decide about");
        }
        return attempt.bindingUuid();
    }

    /// In a pipeline scope, though there is no pipeline: what a stage is built from reports through the
    /// scoped error receiver, as it does for the deferred worker.
    /// The Learning Flow an attempt ran under, which must still exist for anything to be done to it.
    private LearningFlowDoc documentOf(final Recorded attempt) {
        final LearningFlowDoc doc = storeProvider.get().readDocument(
                new DocRef(LearningFlowDoc.TYPE, attempt.attempt().docUuid()));
        if (doc == null) {
            throw new IllegalStateException("The Learning Flow of attempt " + attempt.id()
                                            + " has been deleted");
        }
        return doc;
    }

    /// Runs what needs a whole stage — learning and rebuilding run pipelines — in a pipeline scope.
    private void onStage(final Recorded attempt, final OnStage action) {
        final LearningFlowDoc doc = documentOf(attempt);
        pipelineScopeProvider.get().scopeRunnable(() ->
                action.run(stageFactoryProvider.get().create(), doc));
    }

    private SupervisorAttempt detail(final Recorded attempt) {
        return attempt(attempt, attempt.turns().stream()
                        .map(SupervisorService::turn)
                        .toList(),
                deletedPipelinesProvider.get().isDeleted(attempt.attempt().learner().pipelineUuid()));
    }

    private static SupervisorAttempt attempt(final Recorded attempt,
                                             final List<SupervisorTurn> turns,
                                             final boolean pipelineDeleted) {
        return new SupervisorAttempt(
                attempt.id(),
                new DocRef(LearningFlowDoc.TYPE, attempt.attempt().docUuid()),
                ref(attempt.attempt().learner()),
                attempt.attempt().feed(),
                attempt.attempt().type(),
                attempt.attempt().inputId(),
                attempt.attempt().node(),
                attempt.attempt().promotionMode(),
                attempt.status(),
                attempt.decision(),
                attempt.bindingUuid(),
                attempt.score(),
                attempt.tokensSpent(),
                attempt.grant().tokens(),
                attempt.grant().ms(),
                attempt.exhaustNext(),
                attempt.createTimeMs(),
                attempt.updateTimeMs(),
                turns,
                pipelineDeleted,
                attempt.report(),
                attempt.review().evidence(),
                attempt.review().reviewedBy(),
                attempt.review().reviewedTimeMs());
    }

    private static SupervisorTurn turn(final Turn turn) {
        return new SupervisorTurn(turn.number(), turn.stepId(), turn.candidate(), turn.kind(),
                turn.question(), turn.answer(), turn.answeredBy(), turn.outcome(), turn.score());
    }


    // --------------------------------------------------------------------------------


    @FunctionalInterface
    private interface OnStage {

        void run(stroom.learningflow.stage.Stage stage, LearningFlowDoc doc);
    }
}
