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

package stroom.shapeshifter.ai.doc;

import stroom.docref.DocRef;
import stroom.docstore.api.DocFinder;
import stroom.event.logging.rs.api.AutoLogged;
import stroom.security.api.SecurityContext;
import stroom.security.shared.DocumentPermission;
import stroom.shapeshifter.ai.element.LearnerDocuments;
import stroom.shapeshifter.ai.element.StageFactory;
import stroom.shapeshifter.ai.element.StepDetails;
import stroom.shapeshifter.ai.stage.Attempts;
import stroom.shapeshifter.ai.stage.Attempts.Page;
import stroom.shapeshifter.ai.stage.Attempts.Recorded;
import stroom.shapeshifter.ai.stage.Attempts.Turn;
import stroom.shapeshifter.ai.stage.Decision;
import stroom.shapeshifter.ai.stage.Deferrals;
import stroom.shapeshifter.ai.stage.Deferrals.Deferral;
import stroom.shapeshifter.ai.stage.DeletedPipelines;
import stroom.shapeshifter.ai.stage.Guidance;
import stroom.shapeshifter.ai.stage.Learner;
import stroom.shapeshifter.ai.stage.LearnerBindings;
import stroom.shapeshifter.ai.stage.LearningPipelines;
import stroom.shapeshifter.shared.AmendTurnRequest;
import stroom.shapeshifter.shared.AttemptCriteria;
import stroom.shapeshifter.shared.BacklogStream;
import stroom.shapeshifter.shared.GrantRequest;
import stroom.shapeshifter.shared.GuidanceRequest;
import stroom.shapeshifter.shared.ImproveOutcome;
import stroom.shapeshifter.shared.ImproveRequest;
import stroom.shapeshifter.shared.LearnerCriteria;
import stroom.shapeshifter.shared.LearnerRef;
import stroom.shapeshifter.shared.LearnerRequest;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.RejectRequest;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.SupervisorAttempt;
import stroom.shapeshifter.shared.SupervisorGuidance;
import stroom.shapeshifter.shared.SupervisorLearner;
import stroom.shapeshifter.shared.SupervisorResource;
import stroom.shapeshifter.shared.SupervisorTurn;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/// The Supervisor of A28: attempts across every Shapeshifter AI document, and what a person may do to
/// one they are reading.
///
/// Every attempt names the document it was learning for, and a person sees only the attempts of the
/// documents they may see: an attempt's transcript carries what the model was told about a feed's data,
/// so it is read by the document's permission and not by a permission of its own.
@AutoLogged
public class SupervisorResourceImpl implements SupervisorResource {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(SupervisorResourceImpl.class);

    /// The most rows a page of the serving bindings may ask for, whatever it asks for.
    /// The attempts beside them have always clamped; these did not, so any person with VIEW on one
    /// document could ask for the whole table in one request, and a negative length reached the database
    /// as `LIMIT -1`.
    private static final int PAGE_LIMIT = 1000;

    private final Provider<Attempts> attemptsProvider;
    private final Provider<ShapeshifterAiStore> storeProvider;
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

    @Inject
    SupervisorResourceImpl(final Provider<Attempts> attemptsProvider,
                           final Provider<ShapeshifterAiStore> storeProvider,
                           final Provider<StageFactory> stageFactoryProvider,
                           final Provider<PipelineScopeRunnable> pipelineScopeProvider,
                           final Provider<SecurityContext> securityContextProvider,
                           final Provider<Guidance> guidanceProvider,
                           final Provider<Deferrals> deferralsProvider,
                           final Provider<LearnerDocuments> learnerDocumentsProvider,
                           final Provider<DeletedPipelines> deletedPipelinesProvider,
                           final Provider<LearnerBindings> learnerBindingsProvider,
                           final Provider<LearningPipelines> learningPipelinesProvider,
                           final Provider<DocFinder> docFinderProvider) {
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
    }

    /// The learning pipelines (design 06 A115, A142): read and paged where the rows are, each named with
    /// its pipeline and document, and its oldest wait judged against its document's maximum deferral age
    /// (A57). The documents this person may read go into the query, as they do for the other lists.
    @Override
    public ResultPage<SupervisorLearner> learners(final LearnerCriteria criteria) {
        final LearnerCriteria asked = criteria == null
                ? new LearnerCriteria()
                : criteria;
        final Map<String, DocRef> readable = readable(asked.getDocUuid());
        final Map<String, ShapeshifterAiDoc> docs = documents(readable.values());
        final Set<String> learningOff = docs.values().stream()
                .filter(doc -> doc.getLearningMode() == LearningMode.DISABLED)
                .map(ShapeshifterAiDoc::getUuid)
                .collect(Collectors.toSet());
        final PageRequest page = asked.getPageRequest() == null
                ? new PageRequest(0, 100)
                : asked.getPageRequest();
        final long offset = Math.max(0L, NullSafe.getOrElse(page.getOffset(), Integer::longValue, 0L));
        final LearningPipelines.Page found = learningPipelinesProvider.get().learners(readable.keySet(),
                learningOff, asked.getStates(), asked.isShowDeletedPipelines(), offset, pageLength(page));
        final DocFinder docFinder = docFinderProvider.get();
        final long now = System.currentTimeMillis();
        final List<SupervisorLearner> rows = found.learners().stream()
                .map(row -> row.named(docFinder.decorate(row.getPipeline()),
                        readable.get(row.getDoc().getUuid()),
                        row.getOldestWaitMs() != null
                        && now - row.getOldestWaitMs() > maxDeferralAge(docs, row.getDoc().getUuid()),
                        belowThreshold(docs.get(row.getDoc().getUuid()), row.getRollingScore())))
                .toList();
        return new ResultPage<>(rows, new PageResponse(offset, rows.size(), found.total(), true));
    }

    /// What waits on one learner (design 06 A115), oldest first, a page at a time.
    @Override
    public ResultPage<BacklogStream> waiting(final String docUuid,
                                             final String pipelineUuid,
                                             final String elementId,
                                             final PageRequest pageRequest) {
        if (!may(docUuid, DocumentPermission.VIEW)) {
            throw new PermissionException(securityContextProvider.get().getUserRef(),
                    "You do not have permission to read this document");
        }
        final Learner learner = learnerOf(docUuid, new LearnerRef(pipelineUuid, elementId));
        final PageRequest page = pageRequest == null
                ? new PageRequest(0, 100)
                : pageRequest;
        final long offset = Math.max(0L, NullSafe.getOrElse(page.getOffset(), Integer::longValue, 0L));
        final Deferrals.Waiting waiting = deferralsProvider.get().waitingOn(learner, offset, pageLength(page));
        final DocRef doc = new DocRef(ShapeshifterAiDoc.TYPE, docUuid);
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
    @Override
    public Boolean relearnLearner(final String docUuid, final LearnerRequest request) {
        final Learner learner = changeable(docUuid, request);
        final String who = securityContextProvider.get().getUserIdentityForAudit();
        final String reason = NullSafe.isBlankString(request.getReason())
                ? "Sent back to be learned again by " + who
                : "Sent back to be learned again by " + who + ": " + request.getReason();
        final ShapeshifterAiDoc doc = document(docUuid);
        pipelineScopeProvider.get().scopeRunnable(() ->
                stageFactoryProvider.get().create().relearn(doc, learner, reason));
        return Boolean.TRUE;
    }

    /// Give a learner up (design 06 A115). The reason is asked for rather than assumed: its streams error
    /// with it.
    @Override
    public Boolean giveUp(final String docUuid, final LearnerRequest request) {
        if (request == null || NullSafe.isBlankString(request.getReason())) {
            throw new IllegalArgumentException("Giving a learner up says why: its streams error with the "
                                               + "reason");
        }
        final Learner learner = changeable(docUuid, request);
        final String by = securityContextProvider.get().getUserIdentityForAudit();
        final ShapeshifterAiDoc doc = document(docUuid);
        pipelineScopeProvider.get().scopeRunnable(() ->
                stageFactoryProvider.get().create().giveUp(doc, learner, request.getReason(), by));
        return Boolean.TRUE;
    }

    /// The learner a request about a row names, where this person may change how the document learns and
    /// its pipeline has not been deleted (A137).
    private Learner changeable(final String docUuid, final LearnerRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Say which learner: its pipeline and element");
        }
        if (!may(docUuid, DocumentPermission.EDIT)) {
            throw new PermissionException(securityContextProvider.get().getUserRef(),
                    "You do not have permission to change how this document learns");
        }
        return live(learnerOf(docUuid, request.getLearner()));
    }

    /// The documents, read once each; one that cannot be read is left out, and its rows take the defaults.
    private Map<String, ShapeshifterAiDoc> documents(final Collection<DocRef> docRefs) {
        final Map<String, ShapeshifterAiDoc> docs = new LinkedHashMap<>();
        for (final DocRef docRef : docRefs) {
            try {
                final ShapeshifterAiDoc doc = storeProvider.get().readDocument(docRef);
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

    /// Whether a learner's rolling score is below its document's relearn threshold (design 04 §6): where
    /// the document has one and the learner has served enough to be scored.
    private static boolean belowThreshold(final ShapeshifterAiDoc doc, final Double rollingScore) {
        return doc != null
               && rollingScore != null
               && rollingScore < doc.getRelearnThreshold();
    }

    private static long maxDeferralAge(final Map<String, ShapeshifterAiDoc> docs, final String docUuid) {
        final ShapeshifterAiDoc doc = docs.get(docUuid);
        return doc == null
                ? ShapeshifterAiDoc.builder().build().getMaxDeferralAgeMs()
                : doc.getMaxDeferralAgeMs();
    }

    /// What is waiting on a session (design 04 §6), a row per stream, the raised ones first and then the
    /// longest waiting: the backlog an operator watches form and drain. Raised is computed as it is read,
    /// against each document's maximum deferral age (A78, A79).
    @Override
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
            long maxAge = ShapeshifterAiDoc.builder().build().getMaxDeferralAgeMs();
            try {
                final ShapeshifterAiDoc doc = storeProvider.get().readDocument(docRef);
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
    @Override
    public SupervisorAttempt grant(final long id, final GrantRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Nothing was granted");
        }
        final Recorded attempt = live(change(id));
        onStage(attempt, (stage, doc) -> stage.grant(doc, id, request.getTokens(), request.getMs()));
        return detail(read(id));
    }

    /// Send a parked session to the exhaustion step (A65), and carry it on.
    @Override
    public SupervisorAttempt exhaust(final long id) {
        final Recorded attempt = live(change(id));
        onStage(attempt, (stage, doc) -> stage.exhaust(doc, id));
        return detail(read(id));
    }

    @Override
    public ResultPage<SupervisorAttempt> find(final AttemptCriteria criteria) {
        final AttemptCriteria asked = criteria == null
                ? new AttemptCriteria()
                : criteria;
        // The documents this person may see go into the query rather than filtering its answer: a count
        // taken before the filtering would tell them how many attempts exist on documents they may not
        // see, and the pages they turned would come back short with no way to reach the end.
        final Page page = attemptsProvider.get().found(asked, mayRead(asked.getDocUuid()));
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
    /// Held to EDIT, as approving a draft is: it spends a model's tokens and may rebind the
    /// learner.
    ///
    /// It runs while the request is open, which is the one thing here that is not yet what A46 asks for:
    /// the ruling says an improvement is taken by the deferred worker. The stage says why in
    /// `Stage.improve` — the worker would need the attempt to remember that it is an improvement and to
    /// find its sample in the regression set rather than in the stream store. Nothing waits on it
    /// meanwhile: the incumbent serves every stream throughout, and a candidate takes over only through
    /// the ordinary gate.
    @Override
    public ImproveOutcome improve(final String docUuid, final String bindingUuid, final ImproveRequest request) {
        if (!may(docUuid, DocumentPermission.EDIT)) {
            throw new PermissionException(securityContextProvider.get().getUserRef(),
                    "You do not have permission to change the bindings of this document");
        }
        final String by = securityContextProvider.get().getUserIdentityForAudit();
        final String message = request == null
                ? null
                : request.getMessage();
        liveBinding(bindingUuid);
        final ShapeshifterAiDoc doc = document(docUuid);
        final Decision decision = pipelineScopeProvider.get().scopeResult(() ->
                stageFactoryProvider.get().create().improve(doc, bindingUuid, message, by).decision());
        // Described from the one switch that describes every decision: the stage pane, the error stream
        // and this button say the same thing because they say it from the same place.
        return new ImproveOutcome(StepDetails.describe(decision));
    }

    /// Accept a provisional binding now (design 01 §6, §11.6), rather than waiting for the records
    /// that would promote it.
    ///
    /// Held to EDIT, as the other acts that change what serves a learner are. Not a way round the
    /// gate: the binding cleared the promotion floor when it was bound, and what is skipped is the wait
    /// for more records to judge it on.
    @Override
    public Boolean accept(final String docUuid, final String bindingUuid) {
        if (!may(docUuid, DocumentPermission.EDIT)) {
            throw new PermissionException(securityContextProvider.get().getUserRef(),
                    "You do not have permission to change the bindings of this document");
        }
        final String by = securityContextProvider.get().getUserIdentityForAudit();
        liveBinding(bindingUuid);
        final ShapeshifterAiDoc doc = document(docUuid);
        pipelineScopeProvider.get().scopeRunnable(() ->
                stageFactoryProvider.get().create().accept(doc, bindingUuid, by));
        return Boolean.TRUE;
    }

    /// Take back a binding that is serving (A28, design 01 §11.6).
    ///
    /// The reason is asked for rather than assumed: it is recorded against the attempt that wrote the binding.
    ///
    /// Answers how many streams the binding produced output for, which is the one consequence the person
    /// cannot see for themselves, and what they would reprocess (A129).
    ///
    /// Held to EDIT, as approving a draft is: it changes what serves the document's pipelines.
    @Override
    public Integer retract(final String docUuid, final String bindingUuid, final RejectRequest request) {
        if (request == null || NullSafe.isBlankString(request.getReason())) {
            throw new IllegalArgumentException("A retraction says why: the reason is recorded against "
                                               + "the attempt that wrote the binding");
        }
        if (!may(docUuid, DocumentPermission.EDIT)) {
            throw new PermissionException(securityContextProvider.get().getUserRef(),
                    "You do not have permission to change the bindings of this document");
        }
        final String by = securityContextProvider.get().getUserIdentityForAudit();
        liveBinding(bindingUuid);
        final ShapeshifterAiDoc doc = document(docUuid);
        return pipelineScopeProvider.get().scopeResult(() ->
                stageFactoryProvider.get().create().retract(doc, bindingUuid, request.getReason(), by));
    }

    /// What has been said to a learner (A46), oldest first.
    @Override
    public List<SupervisorGuidance> guidance(final String docUuid,
                                             final String pipelineUuid,
                                             final String elementId) {
        if (!may(docUuid, DocumentPermission.VIEW)) {
            throw new PermissionException(securityContextProvider.get().getUserRef(),
                    "You do not have permission to read this document");
        }
        return said(learnerOf(docUuid, new LearnerRef(pipelineUuid, elementId)));
    }

    /// Tell a learner something, without asking for anything to be run.
    ///
    /// Held to EDIT rather than VIEW: what is written here is carried into every question the learner
    /// asks from now on, which is a change to how the document learns.
    @Override
    public List<SupervisorGuidance> hint(final String docUuid, final GuidanceRequest request) {
        if (request == null || NullSafe.isBlankString(request.getMessage())) {
            throw new IllegalArgumentException("There is nothing to record: the message is empty");
        }
        if (!may(docUuid, DocumentPermission.EDIT)) {
            throw new PermissionException(securityContextProvider.get().getUserRef(),
                    "You do not have permission to change how this document learns");
        }
        final Learner learner = live(learnerOf(docUuid, request.getLearner()));
        guidanceProvider.get().given(learner, docUuid, request.getMessage(),
                securityContextProvider.get().getUserIdentityForAudit());
        return said(learner);
    }

    /// Take back something said to a learner. A hint that turned out to be wrong is worse than no hint:
    /// it is carried into every question the learner asks until it is withdrawn.
    ///
    /// The turns that already carried it still say they did (A45), which is what a re-walk replays: what
    /// was withdrawn stops being carried, and does not stop having been.
    @Override
    public List<SupervisorGuidance> withdraw(final String docUuid,
                                             final long id,
                                             final String pipelineUuid,
                                             final String elementId) {
        if (!may(docUuid, DocumentPermission.EDIT)) {
            throw new PermissionException(securityContextProvider.get().getUserRef(),
                    "You do not have permission to change how this document learns");
        }
        final Learner learner = live(learnerOf(docUuid, new LearnerRef(pipelineUuid, elementId)));
        guidanceProvider.get().withdraw(docUuid, id);
        return said(learner);
    }

    @Override
    public SupervisorAttempt fetch(final long id) {
        return detail(read(id));
    }

    @Override
    public SupervisorAttempt amend(final long id, final int number, final AmendTurnRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("No answer was given for turn " + number);
        }
        final Recorded attempt = stillUnder(live(change(id)));
        final String answeredBy = securityContextProvider.get().getUserIdentityForAudit();
        onStage(attempt, (stage, doc) -> stage.amend(doc, id, number, request.getAnswer(), answeredBy));
        return detail(read(id));
    }

    @Override
    public SupervisorAttempt approve(final long id) {
        final Recorded attempt = live(change(id));
        onStage(attempt, (stage, doc) -> stage.approve(doc, bindingOf(attempt)));
        return detail(read(id));
    }

    @Override
    public SupervisorAttempt reject(final long id, final RejectRequest request) {
        if (request == null || request.getReason() == null || request.getReason().isBlank()) {
            throw new IllegalArgumentException("A rejection says why: the learner gives up with the reason");
        }
        final Recorded attempt = live(change(id));
        onStage(attempt, (stage, doc) -> stage.reject(doc, bindingOf(attempt), request.getReason()));
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

    /// The binding's learner held to the same, where the binding is still there to say whose it is.
    private void liveBinding(final String bindingUuid) {
        learnerBindingsProvider.get().byUuid(bindingUuid).ifPresent(held -> live(held.learner()));
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

    private ShapeshifterAiDoc document(final String docUuid) {
        final ShapeshifterAiDoc doc = storeProvider.get().readDocument(
                new DocRef(ShapeshifterAiDoc.TYPE, docUuid));
        if (doc == null) {
            throw new IllegalArgumentException("There is no Shapeshifter AI document " + docUuid);
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
    /// document's learners and are held to the same permission as editing the document.
    private Recorded change(final long id) {
        return permitted(id, DocumentPermission.EDIT);
    }

    private Recorded permitted(final long id, final DocumentPermission permission) {
        final Recorded attempt = attemptsProvider.get().byId(id).orElse(null);
        if (attempt == null || !may(attempt.attempt().docUuid(), permission)) {
            throw new PermissionException(securityContextProvider.get().getUserRef(),
                    "You do not have permission to read attempt " + id);
        }
        return attempt;
    }

    private boolean may(final String docUuid, final DocumentPermission permission) {
        return securityContextProvider.get().hasDocumentPermission(
                new DocRef(ShapeshifterAiDoc.TYPE, docUuid), permission);
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
    private void onStage(final Recorded attempt, final OnStage action) {
        final ShapeshifterAiDoc doc = storeProvider.get().readDocument(
                new DocRef(ShapeshifterAiDoc.TYPE, attempt.attempt().docUuid()));
        if (doc == null) {
            throw new IllegalStateException("The Shapeshifter AI document of attempt " + attempt.id()
                                            + " has been deleted");
        }
        pipelineScopeProvider.get().scopeRunnable(() ->
                action.run(stageFactoryProvider.get().create(), doc));
    }

    private SupervisorAttempt detail(final Recorded attempt) {
        return attempt(attempt, attempt.turns().stream()
                        .map(SupervisorResourceImpl::turn)
                        .toList(),
                deletedPipelinesProvider.get().isDeleted(attempt.attempt().learner().pipelineUuid()));
    }

    private static SupervisorAttempt attempt(final Recorded attempt,
                                             final List<SupervisorTurn> turns,
                                             final boolean pipelineDeleted) {
        return new SupervisorAttempt(
                attempt.id(),
                new DocRef(ShapeshifterAiDoc.TYPE, attempt.attempt().docUuid()),
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
                pipelineDeleted);
    }

    private static SupervisorTurn turn(final Turn turn) {
        return new SupervisorTurn(turn.number(), turn.stepId(), turn.candidate(), turn.kind(),
                turn.question(), turn.answer(), turn.answeredBy(), turn.outcome(), turn.score());
    }


    // --------------------------------------------------------------------------------


    @FunctionalInterface
    private interface OnStage {

        void run(stroom.shapeshifter.ai.stage.Stage stage, ShapeshifterAiDoc doc);
    }
}
