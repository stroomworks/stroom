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

package stroom.learningflow.shared;

import stroom.docref.DocRef;
import stroom.docs.shared.Description;
import stroom.docstore.shared.AbstractDoc;
import stroom.docstore.shared.DocumentType;
import stroom.docstore.shared.DocumentTypeRegistry;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/// The Learning Flow document: the settings learning runs under for the Smart Pipeline elements that point at
/// it. It sets whether learning is on, which model may be called and with what instructions, the learning
/// flow and its limits, the budgets, the checks that judge each candidate, and whether a person approves a
/// translation before it goes live.
///
/// Every numeric default here is provisional: a configured default with a stated reason, to be revised once
/// real feeds have been measured.
///
/// The document holds only what a person authors. Runtime state deliberately does not live here: learner
/// state, bindings, deferred streams, the regression corpus and session claims are held in database tables
/// or streams.
@Description(
        "Configures one Learning Flow stage: what it learns, the model and instructions it may use, its " +
        "candidate and budget limits, the checks that judge each candidate, and the promotion gate.")
@JsonPropertyOrder({
        "type",
        "uuid",
        "name",
        "version",
        "createTimeMs",
        "updateTimeMs",
        "createUser",
        "updateUser",
        "description",
        "learningMode",
        "model",
        "targetSchema",
        "relearnThreshold",
        "instructions",
        "flow",
        "maxAttempts",
        "attemptBudgetMs",
        "tokenBudget",
        "spendTokensPerHour",
        "spendCallsPerHour",
        "maxDeferralAgeMs",
        "sampleSizeLimit",
        "promotionMode",
        "heldOutFraction",
        "minRecordsToLearn",
        "regressionCap",
        "checks",
})
@JsonInclude(Include.NON_NULL)
public class LearningFlowDoc extends AbstractDoc {

    public static final String TYPE = "LearningFlow";
    public static final DocumentType DOCUMENT_TYPE = DocumentTypeRegistry.LEARNING_FLOW_DOCUMENT_TYPE;

    static final LearningMode DEFAULT_LEARNING_MODE = LearningMode.DISABLED;
    /// Set below the usual passing thresholds, so that a binding that only just passed is not flagged at
    /// once.
    static final double DEFAULT_RELEARN_THRESHOLD = 0.8;
    /// Review rather than automatic: a new Learning Flow waits for someone to approve what it learns, and
    /// using translations as soon as they pass is a deliberate choice that needs the
    /// `APPROVE_SMART_PIPELINES_AUTOMATICALLY` permission.
    ///
    /// A model nobody has calibrated should not change production content unwatched. In testing, a
    /// translation of the NETFILTER feed was promoted automatically with a score of 1.0 although it had lost
    /// a line and hard-coded a year.
    static final PromotionMode DEFAULT_PROMOTION_MODE = PromotionMode.REVIEW;
    /// Five rather than three: the first live run found three enough for a simple feed but one too few for a
    /// transform that needs a rarer schema branch. The time and token budgets are what really bound a
    /// session.
    static final int DEFAULT_MAX_ATTEMPTS = 5;
    /// No steps, and the built-in template text. The example flows are defined on the server, so a document
    /// built here cannot carry one: the server fills in a new document's steps when it creates it, and until
    /// then the flow reports that it has no steps rather than silently using the Direct example.
    static final LearningFlow DEFAULT_FLOW = new LearningFlow(List.of(), null, null);
    static final long DEFAULT_ATTEMPT_BUDGET_MS = 60_000L;
    static final long DEFAULT_MAX_DEFERRAL_AGE_MS = 24L * 60 * 60 * 1000;
    static final int DEFAULT_SAMPLE_SIZE_LIMIT = 8_192;
    static final double DEFAULT_HELD_OUT_FRACTION = 0.2;
    static final int DEFAULT_MIN_RECORDS_TO_LEARN = 10;
    static final int DEFAULT_REGRESSION_CAP = 100;

    @JsonProperty
    private final String description;
    /// Whether the model may be called at all. Off by default, because learning is opted into per document.
    /// When it is off, bindings already in use go on serving.
    @JsonProperty
    private final LearningMode learningMode;
    /// The OpenAI-compatible model document that learning calls.
    @JsonProperty
    private final DocRef model;
    /// The one schema version that translations learned under this document must write. It is the
    /// `XMLSchema` document, not its schema group, since a group such as `EVENTS` holds several event-logging
    /// versions. The prompts, the target checks and the conformance check all read it, so the target is set
    /// in one place. A new document is given the latest event-logging version installed, saved here so that
    /// installing a newer one later does not change what this document writes.
    @JsonProperty
    private final DocRef targetSchema;
    /// The rolling per-record score below which a learner with a binding is flagged in the Supervisor. The
    /// flag only draws a person's attention; it does not trigger relearning by itself.
    @JsonProperty
    private final double relearnThreshold;
    /// Free-text instructions added to the system text of every prompt.
    @JsonProperty
    private final String instructions;
    /// The number of candidates each step may try before its candidates are spent, unless the step sets its
    /// own number. The field is named "attempts" for compatibility with stored documents, although it counts
    /// candidates, not sessions.
    @JsonProperty
    private final int maxAttempts;
    /// The learning flow a session follows.
    @JsonProperty
    private final LearningFlow flow;
    /// The wall-clock budget for one run of a session: each time the session is resumed, it has the whole
    /// budget again. The budget is mandatory; there is no unlimited value.
    @JsonProperty
    private final long attemptBudgetMs;
    /// The token budget for one session over its whole life; null is unlimited.
    @JsonProperty
    private final Long tokenBudget;
    /// The most tokens this Learning Flow may spend in an hour, across every session of every feed it serves;
    /// null or zero leaves it to the cluster-wide ceiling alone. A session's token budget bounds one session;
    /// this bounds a feed that keeps opening new ones.
    @JsonProperty
    private final Long spendTokensPerHour;
    /// The most questions this Learning Flow may put to a model in an hour, across every session of every
    /// feed it serves; null or zero leaves it to the cluster-wide ceiling alone.
    @JsonProperty
    private final Integer spendCallsPerHour;
    /// How long a stream may wait for a translation before it is raised. Past this age it is flagged and
    /// counted in the Supervisor's backlog, and goes on waiting.
    @JsonProperty
    private final long maxDeferralAgeMs;
    /// The largest sample, in bytes, sent to the model. Samples are never redacted, so a feed should only be
    /// pointed at a model its data may be sent to.
    @JsonProperty
    private final int sampleSizeLimit;
    /// Whether a learned binding goes live as soon as it passes, or waits as a draft for a person to approve.
    @JsonProperty
    private final PromotionMode promotionMode;
    /// The fraction of a stream's records withheld from the sample shown to the model, so that a candidate is
    /// also judged on records the model never saw.
    @JsonProperty
    private final double heldOutFraction;
    /// The number of records a stream must bring for the system to trust what is learned from it. A first
    /// translation learned from fewer is written as a draft for a person to approve, even under automatic
    /// promotion, since the system approves on its own only when it has enough evidence. A served stream with
    /// fewer records does not start a relearning.
    @JsonProperty
    private final int minRecordsToLearn;
    /// The most previously accepted records kept per binding in its regression corpus.
    @JsonProperty
    private final int regressionCap;
    /// Every check, each Off, Advice or Gate, with any check not named at its default; a new document has
    /// the defaults.
    @JsonProperty
    private final List<CheckSetting> checks;

    @JsonCreator
    public LearningFlowDoc(
            @JsonProperty("uuid") final String uuid,
            @JsonProperty("name") final String name,
            @JsonProperty("version") final String version,
            @JsonProperty("createTimeMs") final Long createTimeMs,
            @JsonProperty("updateTimeMs") final Long updateTimeMs,
            @JsonProperty("createUser") final String createUser,
            @JsonProperty("updateUser") final String updateUser,
            @JsonProperty("description") final String description,
            @JsonProperty("learningMode") final LearningMode learningMode,
            @JsonProperty("model") final DocRef model,
            @JsonProperty("targetSchema") final DocRef targetSchema,
            @JsonProperty("relearnThreshold") final Double relearnThreshold,
            @JsonProperty("instructions") final String instructions,
            @JsonProperty("flow") final LearningFlow flow,
            @JsonProperty("maxAttempts") final Integer maxAttempts,
            @JsonProperty("attemptBudgetMs") final Long attemptBudgetMs,
            @JsonProperty("tokenBudget") final Long tokenBudget,
            @JsonProperty("spendTokensPerHour") final Long spendTokensPerHour,
            @JsonProperty("spendCallsPerHour") final Integer spendCallsPerHour,
            @JsonProperty("maxDeferralAgeMs") final Long maxDeferralAgeMs,
            @JsonProperty("sampleSizeLimit") final Integer sampleSizeLimit,
            @JsonProperty("promotionMode") final PromotionMode promotionMode,
            @JsonProperty("heldOutFraction") final Double heldOutFraction,
            @JsonProperty("minRecordsToLearn") final Integer minRecordsToLearn,
            @JsonProperty("regressionCap") final Integer regressionCap,
            @JsonProperty("checks") final List<CheckSetting> checks) {
        super(TYPE, uuid, name, version, createTimeMs, updateTimeMs, createUser, updateUser);
        this.description = description;
        this.learningMode = Objects.requireNonNullElse(learningMode, DEFAULT_LEARNING_MODE);
        this.model = model;
        this.targetSchema = targetSchema;
        this.relearnThreshold = Objects.requireNonNullElse(relearnThreshold, DEFAULT_RELEARN_THRESHOLD);
        this.instructions = instructions;
        // A document saved by an earlier version stored the flow under the key "plan". The server's
        // serialiser reads that key as this one, since the client's codec cannot.
        this.flow = Objects.requireNonNullElse(flow, DEFAULT_FLOW);
        this.maxAttempts = Objects.requireNonNullElse(maxAttempts, DEFAULT_MAX_ATTEMPTS);
        this.attemptBudgetMs = Objects.requireNonNullElse(attemptBudgetMs, DEFAULT_ATTEMPT_BUDGET_MS);
        this.tokenBudget = tokenBudget;
        this.spendTokensPerHour = spendTokensPerHour;
        this.spendCallsPerHour = spendCallsPerHour;
        this.maxDeferralAgeMs = Objects.requireNonNullElse(maxDeferralAgeMs, DEFAULT_MAX_DEFERRAL_AGE_MS);
        this.sampleSizeLimit = Objects.requireNonNullElse(sampleSizeLimit, DEFAULT_SAMPLE_SIZE_LIMIT);
        this.promotionMode = Objects.requireNonNullElse(promotionMode, DEFAULT_PROMOTION_MODE);
        this.heldOutFraction = Objects.requireNonNullElse(heldOutFraction, DEFAULT_HELD_OUT_FRACTION);
        this.minRecordsToLearn = Objects.requireNonNullElse(minRecordsToLearn, DEFAULT_MIN_RECORDS_TO_LEARN);
        this.regressionCap = Objects.requireNonNullElse(regressionCap, DEFAULT_REGRESSION_CAP);
        this.checks = CheckSettings.complete(checks);
    }

    // Lists default to empty rather than null, so that a document saved before a list existed compares equal
    // to the same document read back, and so that nothing reading them has to check for null.
    private static <T> List<T> copyOrEmpty(final List<T> list) {
        return list == null
                ? Collections.emptyList()
                : List.copyOf(list);
    }

    /// @return A new builder for creating a [DocRef] for this document's type.
    public static DocRef.TypedBuilder buildDocRef() {
        return DocRef.builder(TYPE);
    }

    public String getDescription() {
        return description;
    }

    public LearningMode getLearningMode() {
        return learningMode;
    }

    public DocRef getModel() {
        return model;
    }

    /// @return The one schema version this document's translations must write, or null where none is set.
    public DocRef getTargetSchema() {
        return targetSchema;
    }

    public double getRelearnThreshold() {
        return relearnThreshold;
    }

    /// @return The pipeline element types the chain question may choose from: those named on the flow's Chain
    /// step, or every learnable type where it names none. With exactly one, the chain question is not asked.
    /// This is derived from the flow, so it is not serialised.
    @JsonIgnore
    public List<String> getAllowedElements() {
        return flow.allowedElements();
    }

    public String getInstructions() {
        return instructions;
    }

    public LearningFlow getFlow() {
        return flow;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public long getAttemptBudgetMs() {
        return attemptBudgetMs;
    }

    public Long getTokenBudget() {
        return tokenBudget;
    }

    /// @return The most tokens this Learning Flow may spend in an hour across every session of every feed
    ///         it serves; null or zero leaves it to the cluster-wide ceiling alone.
    public Long getSpendTokensPerHour() {
        return spendTokensPerHour;
    }

    /// @return The most questions this Learning Flow may put to a model in an hour; null or zero leaves
    ///         it to the cluster-wide ceiling alone.
    public Integer getSpendCallsPerHour() {
        return spendCallsPerHour;
    }

    /// @return How long a stream may wait for a translation before it is raised.
    public long getMaxDeferralAgeMs() {
        return maxDeferralAgeMs;
    }

    public int getSampleSizeLimit() {
        return sampleSizeLimit;
    }

    public PromotionMode getPromotionMode() {
        return promotionMode;
    }

    public double getHeldOutFraction() {
        return heldOutFraction;
    }

    public int getMinRecordsToLearn() {
        return minRecordsToLearn;
    }

    public int getRegressionCap() {
        return regressionCap;
    }

    /// @return One setting for every check, in the order of [Check].
    public List<CheckSetting> getChecks() {
        return checks;
    }

    /// @param check A check.
    /// @return Its setting.
    public CheckSetting getCheck(final Check check) {
        return CheckSettings.of(checks, check);
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        final LearningFlowDoc that = (LearningFlowDoc) o;
        return maxAttempts == that.maxAttempts &&
               attemptBudgetMs == that.attemptBudgetMs &&
               sampleSizeLimit == that.sampleSizeLimit &&
               Double.compare(relearnThreshold, that.relearnThreshold) == 0 &&
               Double.compare(heldOutFraction, that.heldOutFraction) == 0 &&
               minRecordsToLearn == that.minRecordsToLearn &&
               regressionCap == that.regressionCap &&
               Objects.equals(description, that.description) &&
               learningMode == that.learningMode &&
               Objects.equals(flow, that.flow) &&
               promotionMode == that.promotionMode &&
               Objects.equals(model, that.model) &&
               Objects.equals(instructions, that.instructions) &&
               Objects.equals(tokenBudget, that.tokenBudget) &&
               Objects.equals(spendTokensPerHour, that.spendTokensPerHour) &&
               Objects.equals(spendCallsPerHour, that.spendCallsPerHour) &&
               maxDeferralAgeMs == that.maxDeferralAgeMs &&
               Objects.equals(checks, that.checks);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(),
                description,
                learningMode,
                model,
                relearnThreshold,
                instructions,
                flow,
                maxAttempts,
                attemptBudgetMs,
                tokenBudget,
                spendTokensPerHour,
                spendCallsPerHour,
                maxDeferralAgeMs,
                sampleSizeLimit,
                promotionMode,
                heldOutFraction,
                minRecordsToLearn,
                regressionCap,
                checks);
    }

    @Override
    public String toString() {
        return "LearningFlowDoc{" +
               "name='" + getName() + '\'' +
               ", learningMode=" + learningMode +
               ", model=" + model +
               ", relearnThreshold=" + relearnThreshold +
               ", flow=" + flow +
               ", maxAttempts=" + maxAttempts +
               ", attemptBudgetMs=" + attemptBudgetMs +
               ", tokenBudget=" + tokenBudget +
               ", spendTokensPerHour=" + spendTokensPerHour +
               ", spendCallsPerHour=" + spendCallsPerHour +
               ", sampleSizeLimit=" + sampleSizeLimit +
               ", promotionMode=" + promotionMode +
               ", heldOutFraction=" + heldOutFraction +
               ", minRecordsToLearn=" + minRecordsToLearn +
               ", regressionCap=" + regressionCap +
               ", checks=" + checks +
               '}';
    }

    public Builder copy() {
        return new Builder(this);
    }

    public static Builder builder() {
        return new Builder();
    }


    // --------------------------------------------------------------------------------


    public static final class Builder extends AbstractBuilder<LearningFlowDoc, Builder> {

        private String description;
        private LearningMode learningMode = DEFAULT_LEARNING_MODE;
        private DocRef model;
        private DocRef targetSchema;
        private double relearnThreshold = DEFAULT_RELEARN_THRESHOLD;
        private String instructions;
        private LearningFlow flow = DEFAULT_FLOW;
        private int maxAttempts = DEFAULT_MAX_ATTEMPTS;
        private long attemptBudgetMs = DEFAULT_ATTEMPT_BUDGET_MS;
        private Long tokenBudget;
        private Long spendTokensPerHour;
        private Integer spendCallsPerHour;
        private long maxDeferralAgeMs = DEFAULT_MAX_DEFERRAL_AGE_MS;
        private int sampleSizeLimit = DEFAULT_SAMPLE_SIZE_LIMIT;
        private PromotionMode promotionMode = DEFAULT_PROMOTION_MODE;
        private double heldOutFraction = DEFAULT_HELD_OUT_FRACTION;
        private int minRecordsToLearn = DEFAULT_MIN_RECORDS_TO_LEARN;
        private int regressionCap = DEFAULT_REGRESSION_CAP;
        private List<CheckSetting> checks;

        private Builder() {
        }

        private Builder(final LearningFlowDoc doc) {
            super(doc);
            this.description = doc.description;
            this.learningMode = doc.learningMode;
            this.model = doc.model;
            this.targetSchema = doc.targetSchema;
            this.relearnThreshold = doc.relearnThreshold;
            this.instructions = doc.instructions;
            this.flow = doc.flow;
            this.maxAttempts = doc.maxAttempts;
            this.attemptBudgetMs = doc.attemptBudgetMs;
            this.tokenBudget = doc.tokenBudget;
            this.spendTokensPerHour = doc.spendTokensPerHour;
            this.spendCallsPerHour = doc.spendCallsPerHour;
            this.maxDeferralAgeMs = doc.maxDeferralAgeMs;
            this.sampleSizeLimit = doc.sampleSizeLimit;
            this.promotionMode = doc.promotionMode;
            this.heldOutFraction = doc.heldOutFraction;
            this.minRecordsToLearn = doc.minRecordsToLearn;
            this.regressionCap = doc.regressionCap;
            this.checks = doc.checks;
        }

        public Builder description(final String description) {
            this.description = description;
            return self();
        }

        public Builder learningMode(final LearningMode learningMode) {
            this.learningMode = learningMode;
            return self();
        }

        public Builder model(final DocRef model) {
            this.model = model;
            return self();
        }

        /// @param targetSchema The `XMLSchema` document a translation must write, or null for none.
        /// @return This builder.
        public Builder targetSchema(final DocRef targetSchema) {
            this.targetSchema = targetSchema;
            return self();
        }

        public Builder relearnThreshold(final double relearnThreshold) {
            this.relearnThreshold = relearnThreshold;
            return self();
        }

        /// Sets the elements the chain question chooses from, which are held on the flow's Chain step. It is
        /// kept so that the server can read documents saved by an earlier version, which held these on the
        /// document itself, and for the test fixtures that set them.
        ///
        /// @param allowedElements The element types to put on the Chain step.
        /// @return This builder.
        public Builder allowedElements(final List<String> allowedElements) {
            this.flow = flow.withChainElements(allowedElements);
            return self();
        }

        public Builder instructions(final String instructions) {
            this.instructions = instructions;
            return self();
        }

        public Builder flow(final LearningFlow flow) {
            this.flow = flow;
            return self();
        }

        public Builder maxAttempts(final int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return self();
        }

        public Builder attemptBudgetMs(final long attemptBudgetMs) {
            this.attemptBudgetMs = attemptBudgetMs;
            return self();
        }

        /// Sets the most tokens this Learning Flow may spend in an hour.
        ///
        /// @param spendTokensPerHour The ceiling, or null for none of its own.
        /// @return This builder.
        public Builder spendTokensPerHour(final Long spendTokensPerHour) {
            this.spendTokensPerHour = spendTokensPerHour;
            return this;
        }

        /// Sets the most questions this Learning Flow may put to a model in an hour.
        ///
        /// @param spendCallsPerHour The ceiling, or null for none of its own.
        /// @return This builder.
        public Builder spendCallsPerHour(final Integer spendCallsPerHour) {
            this.spendCallsPerHour = spendCallsPerHour;
            return this;
        }

        public Builder tokenBudget(final Long tokenBudget) {
            this.tokenBudget = tokenBudget;
            return self();
        }

        public Builder maxDeferralAgeMs(final long maxDeferralAgeMs) {
            this.maxDeferralAgeMs = maxDeferralAgeMs;
            return self();
        }

        public Builder sampleSizeLimit(final int sampleSizeLimit) {
            this.sampleSizeLimit = sampleSizeLimit;
            return self();
        }

        public Builder promotionMode(final PromotionMode promotionMode) {
            this.promotionMode = promotionMode;
            return self();
        }

        public Builder heldOutFraction(final double heldOutFraction) {
            this.heldOutFraction = heldOutFraction;
            return self();
        }

        public Builder minRecordsToLearn(final int minRecordsToLearn) {
            this.minRecordsToLearn = minRecordsToLearn;
            return self();
        }

        public Builder regressionCap(final int regressionCap) {
            this.regressionCap = regressionCap;
            return self();
        }

        /// @param checks The document's checks; any not named have their defaults.
        /// @return This builder.
        public Builder checks(final List<CheckSetting> checks) {
            this.checks = checks;
            return self();
        }

        @Override
        protected Builder self() {
            return this;
        }

        public LearningFlowDoc build() {
            return new LearningFlowDoc(
                    uuid,
                    name,
                    version,
                    createTimeMs,
                    updateTimeMs,
                    createUser,
                    updateUser,
                    description,
                    learningMode,
                    model,
                    targetSchema,
                    relearnThreshold,
                    instructions,
                    flow,
                    maxAttempts,
                    attemptBudgetMs,
                    tokenBudget,
                    spendTokensPerHour,
                    spendCallsPerHour,
                    maxDeferralAgeMs,
                    sampleSizeLimit,
                    promotionMode,
                    heldOutFraction,
                    minRecordsToLearn,
                    regressionCap,
                    checks);
        }
    }
}
