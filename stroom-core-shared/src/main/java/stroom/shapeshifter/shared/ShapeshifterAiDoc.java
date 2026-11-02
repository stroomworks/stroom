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

package stroom.shapeshifter.shared;

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

/**
 * The configuration of one supervised pipeline stage (design 01 §3, ruling A3): what it learns, which
 * model it may call and how, how many candidates an attempt gets, how candidates are scored, and when
 * one is promoted and whether a person looks first.
 * <p>
 * Every numeric default here is provisional in the sense of §2.1 — a configured default with a recorded
 * justification, to be revised once real feeds have been measured. Each field's javadoc names the section
 * or ruling it serves.
 * <p>
 * Runtime state deliberately does not live here: learner state, deferrals, regression records,
 * per-stream bindings, learning leases and error-mode state are rows (A26) or streams. So are the
 * learners' bindings, the one part the supervisor rewrites (§7.3 rule 2). The replay unit is not a setting either:
 * it is a property of a binding's fragment, fixed by the stage's position (A1 as revised).
 */
@Description(
        "Configures one Shapeshifter AI stage: what it learns, the model and instructions it may use, its " +
        "candidate and budget limits, the scorers that judge each candidate, and the promotion gate.")
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
        "relearnThreshold",
        "instructions",
        "flow",
        "maxAttempts",
        "attemptBudgetMs",
        "tokenBudget",
        "maxDeferralAgeMs",
        "sampleSizeLimit",
        "promotionMode",
        "promotionFloor",
        "heldOutFraction",
        "minRecordsToLearn",
        "regressionCap",
        "regressionRetentionDays",
        "scorers",
})
@JsonInclude(Include.NON_NULL)
public class ShapeshifterAiDoc extends AbstractDoc {

    public static final String TYPE = "ShapeshifterAi";
    public static final DocumentType DOCUMENT_TYPE = DocumentTypeRegistry.SHAPESHIFTER_AI_DOCUMENT_TYPE;

    static final LearningMode DEFAULT_LEARNING_MODE = LearningMode.DISABLED;
    /**
     * Below the promotion floor, so that a binding promoted at the floor is not relearned at once.
     */
    static final double DEFAULT_RELEARN_THRESHOLD = 0.8;
    static final PromotionMode DEFAULT_PROMOTION_MODE = PromotionMode.AUTOMATIC;
    /**
     * Five, not three: the first live run (design 02 §6.2) found three right for a simple feed and one short
     * for a transform that needs a rarer schema branch; the attempt budget is the real bound (A5).
     */
    static final int DEFAULT_MAX_ATTEMPTS = 5;
    /**
     * The direct example's steps and the built-in text: on the feeds measured so far the direct flow reached
     * the same scores at half the tokens (design 02 §6.3).
     */
    static final LearningFlow DEFAULT_FLOW = LearningFlow.of(FlowExample.DIRECT);
    static final long DEFAULT_ATTEMPT_BUDGET_MS = 60_000L;
    static final long DEFAULT_MAX_DEFERRAL_AGE_MS = 24L * 60 * 60 * 1000;
    static final int DEFAULT_SAMPLE_SIZE_LIMIT = 8_192;
    static final double DEFAULT_PROMOTION_FLOOR = 0.9;
    static final double DEFAULT_HELD_OUT_FRACTION = 0.2;
    static final int DEFAULT_MIN_RECORDS_TO_LEARN = 10;
    static final int DEFAULT_REGRESSION_CAP = 100;

    @JsonProperty
    private final String description;
    /**
     * Whether the model may be called at all. Off by default because §11 makes Shapeshifter AI opt-in per
     * document; off still serves what is bound and promotes what clears the floor.
     */
    @JsonProperty
    private final LearningMode learningMode;
    /**
     * The OpenAI-compatible model document the stage calls (§10, A13).
     */
    @JsonProperty
    private final DocRef model;
    /**
     * The rolling per-record score below which a bound learner is marked for relearning (A29, §5), so that a
     * new kind of record reaching a bound learner — which its binding goes on serving — is still learned.
     */
    @JsonProperty
    private final double relearnThreshold;
    /**
     * Free-text instructions prepended to the prompt contract of §10.
     */
    @JsonProperty
    private final String instructions;
    /**
     * Candidates an attempt may try before the learner is given up on (§3, §7.4). The name predates the
     * split of <em>attempt</em> from <em>candidate</em> in design 01 §3 and is kept for the stored form.
     */
    @JsonProperty
    private final int maxAttempts;
    /**
     * The learning flow an attempt follows (A33, A34, A37; design 01 §10.2).
     */
    @JsonProperty
    private final LearningFlow flow;
    /**
     * Wall-clock budget for one run of an attempt: each time it is carried on, it has the whole of it again
     * (design 06 A145). Ruling A5 makes the budget mandatory; there is no unlimited value.
     */
    @JsonProperty
    private final long attemptBudgetMs;
    /**
     * Token budget for one attempt (§10); null is unlimited.
     */
    @JsonProperty
    private final Long tokenBudget;
    /**
     * How long a stream may wait on a session before it is raised (design 04 §8 scenario 8, A57, A78): past
     * this it is flagged and counted in the Supervisor's backlog, and goes on waiting (A79).
     */
    @JsonProperty
    private final long maxDeferralAgeMs;
    /**
     * Largest sample, in bytes, sent to the model: ruling A17's size cap, which is the half of A17 that
     * is built. Redacting the values themselves waits on A38 and has no setting here until it does
     * (design 01 §15.1).
     */
    @JsonProperty
    private final int sampleSizeLimit;
    /**
     * Whether a learned binding goes live on promotion or is appended as a draft for a person to approve (A25).
     */
    @JsonProperty
    private final PromotionMode promotionMode;
    /**
     * Absolute weighted score a candidate must reach on the held-out sample before it can be promoted.
     * Ruling A15's floor; the no-regression half of A15 is measured against the incumbent at run time.
     */
    @JsonProperty
    private final double promotionFloor;
    /**
     * Fraction of a learner's records withheld from the model and used only to judge promotion. Ruling A14.
     */
    @JsonProperty
    private final double heldOutFraction;
    /**
     * Records a learner must have accumulated before promotion is judged at all; below this a candidate
     * that clears the floor is bound provisionally and waits for more. Ruling A14, A5.
     */
    @JsonProperty
    private final int minRecordsToLearn;
    /**
     * Most previously-accepted records retained per binding for the regression check. Ruling A18.
     */
    @JsonProperty
    private final int regressionCap;
    /**
     * Days regression records are retained, capped by the source feed's own retention; null is the feed's.
     * Ruling A18.
     */
    @JsonProperty
    private final Integer regressionRetentionDays;
    /**
     * The scorers applied to every attempt, with weights and thresholds. §8.4.
     */
    @JsonProperty
    private final List<ScorerSetting> scorers;

    @JsonCreator
    public ShapeshifterAiDoc(
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
            @JsonProperty("relearnThreshold") final Double relearnThreshold,
            @JsonProperty("instructions") final String instructions,
            @JsonProperty("flow") final LearningFlow flow,
            @JsonProperty("maxAttempts") final Integer maxAttempts,
            @JsonProperty("attemptBudgetMs") final Long attemptBudgetMs,
            @JsonProperty("tokenBudget") final Long tokenBudget,
            @JsonProperty("maxDeferralAgeMs") final Long maxDeferralAgeMs,
            @JsonProperty("sampleSizeLimit") final Integer sampleSizeLimit,
            @JsonProperty("promotionMode") final PromotionMode promotionMode,
            @JsonProperty("promotionFloor") final Double promotionFloor,
            @JsonProperty("heldOutFraction") final Double heldOutFraction,
            @JsonProperty("minRecordsToLearn") final Integer minRecordsToLearn,
            @JsonProperty("regressionCap") final Integer regressionCap,
            @JsonProperty("regressionRetentionDays") final Integer regressionRetentionDays,
            @JsonProperty("scorers") final List<ScorerSetting> scorers) {
        super(TYPE, uuid, name, version, createTimeMs, updateTimeMs, createUser, updateUser);
        this.description = description;
        this.learningMode = Objects.requireNonNullElse(learningMode, DEFAULT_LEARNING_MODE);
        this.model = model;
        this.relearnThreshold = Objects.requireNonNullElse(relearnThreshold, DEFAULT_RELEARN_THRESHOLD);
        this.instructions = instructions;
        // A document written before the flow was named so carried it as "plan" (A81): the store's
        // serialiser reads that key as this one, since the client's codec cannot.
        this.flow = Objects.requireNonNullElse(flow, DEFAULT_FLOW);
        this.maxAttempts = Objects.requireNonNullElse(maxAttempts, DEFAULT_MAX_ATTEMPTS);
        this.attemptBudgetMs = Objects.requireNonNullElse(attemptBudgetMs, DEFAULT_ATTEMPT_BUDGET_MS);
        this.tokenBudget = tokenBudget;
        this.maxDeferralAgeMs = Objects.requireNonNullElse(maxDeferralAgeMs, DEFAULT_MAX_DEFERRAL_AGE_MS);
        this.sampleSizeLimit = Objects.requireNonNullElse(sampleSizeLimit, DEFAULT_SAMPLE_SIZE_LIMIT);
        this.promotionMode = Objects.requireNonNullElse(promotionMode, DEFAULT_PROMOTION_MODE);
        this.promotionFloor = Objects.requireNonNullElse(promotionFloor, DEFAULT_PROMOTION_FLOOR);
        this.heldOutFraction = Objects.requireNonNullElse(heldOutFraction, DEFAULT_HELD_OUT_FRACTION);
        this.minRecordsToLearn = Objects.requireNonNullElse(minRecordsToLearn, DEFAULT_MIN_RECORDS_TO_LEARN);
        this.regressionCap = Objects.requireNonNullElse(regressionCap, DEFAULT_REGRESSION_CAP);
        this.regressionRetentionDays = regressionRetentionDays;
        this.scorers = copyOrEmpty(scorers);
    }

    /**
     * Lists default to empty rather than null so that a doc written before a list existed and the same doc
     * read back compare equal, and so that nothing walking them has to null-check.
     */
    private static <T> List<T> copyOrEmpty(final List<T> list) {
        return list == null
                ? Collections.emptyList()
                : List.copyOf(list);
    }

    /**
     * @return A new builder for creating a {@link DocRef} for this document's type.
     */
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

    public double getRelearnThreshold() {
        return relearnThreshold;
    }

    /// Pipeline element type names the chain question may choose from (A21): the flow's Chain step's since
    /// A95, A10's initial set where it names none. With exactly one the chain question is not asked. Not
    /// a property of the document any more, so not written.
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

    /// @return How long a stream may wait on a session before it is raised (A78).
    public long getMaxDeferralAgeMs() {
        return maxDeferralAgeMs;
    }

    public int getSampleSizeLimit() {
        return sampleSizeLimit;
    }

    public PromotionMode getPromotionMode() {
        return promotionMode;
    }

    public double getPromotionFloor() {
        return promotionFloor;
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

    public Integer getRegressionRetentionDays() {
        return regressionRetentionDays;
    }

    public List<ScorerSetting> getScorers() {
        return scorers;
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
        final ShapeshifterAiDoc that = (ShapeshifterAiDoc) o;
        return maxAttempts == that.maxAttempts &&
               attemptBudgetMs == that.attemptBudgetMs &&
               sampleSizeLimit == that.sampleSizeLimit &&
               Double.compare(relearnThreshold, that.relearnThreshold) == 0 &&
               Double.compare(promotionFloor, that.promotionFloor) == 0 &&
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
               maxDeferralAgeMs == that.maxDeferralAgeMs &&
               Objects.equals(regressionRetentionDays, that.regressionRetentionDays) &&
               Objects.equals(scorers, that.scorers);
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
                maxDeferralAgeMs,
                sampleSizeLimit,
                promotionMode,
                promotionFloor,
                heldOutFraction,
                minRecordsToLearn,
                regressionCap,
                regressionRetentionDays,
                scorers);
    }

    @Override
    public String toString() {
        return "ShapeshifterAiDoc{" +
               "name='" + getName() + '\'' +
               ", learningMode=" + learningMode +
               ", model=" + model +
               ", relearnThreshold=" + relearnThreshold +
               ", flow=" + flow +
               ", maxAttempts=" + maxAttempts +
               ", attemptBudgetMs=" + attemptBudgetMs +
               ", tokenBudget=" + tokenBudget +
               ", sampleSizeLimit=" + sampleSizeLimit +
               ", promotionMode=" + promotionMode +
               ", promotionFloor=" + promotionFloor +
               ", heldOutFraction=" + heldOutFraction +
               ", minRecordsToLearn=" + minRecordsToLearn +
               ", regressionCap=" + regressionCap +
               ", regressionRetentionDays=" + regressionRetentionDays +
               ", scorers=" + scorers +
               '}';
    }

    public Builder copy() {
        return new Builder(this);
    }

    public static Builder builder() {
        return new Builder();
    }


    // --------------------------------------------------------------------------------


    public static final class Builder extends AbstractBuilder<ShapeshifterAiDoc, Builder> {

        private String description;
        private LearningMode learningMode = DEFAULT_LEARNING_MODE;
        private DocRef model;
        private double relearnThreshold = DEFAULT_RELEARN_THRESHOLD;
        private String instructions;
        private LearningFlow flow = DEFAULT_FLOW;
        private int maxAttempts = DEFAULT_MAX_ATTEMPTS;
        private long attemptBudgetMs = DEFAULT_ATTEMPT_BUDGET_MS;
        private Long tokenBudget;
        private long maxDeferralAgeMs = DEFAULT_MAX_DEFERRAL_AGE_MS;
        private int sampleSizeLimit = DEFAULT_SAMPLE_SIZE_LIMIT;
        private PromotionMode promotionMode = DEFAULT_PROMOTION_MODE;
        private double promotionFloor = DEFAULT_PROMOTION_FLOOR;
        private double heldOutFraction = DEFAULT_HELD_OUT_FRACTION;
        private int minRecordsToLearn = DEFAULT_MIN_RECORDS_TO_LEARN;
        private int regressionCap = DEFAULT_REGRESSION_CAP;
        private Integer regressionRetentionDays;
        private List<ScorerSetting> scorers = Collections.emptyList();

        private Builder() {
        }

        private Builder(final ShapeshifterAiDoc doc) {
            super(doc);
            this.description = doc.description;
            this.learningMode = doc.learningMode;
            this.model = doc.model;
            this.relearnThreshold = doc.relearnThreshold;
            this.instructions = doc.instructions;
            this.flow = doc.flow;
            this.maxAttempts = doc.maxAttempts;
            this.attemptBudgetMs = doc.attemptBudgetMs;
            this.tokenBudget = doc.tokenBudget;
            this.maxDeferralAgeMs = doc.maxDeferralAgeMs;
            this.sampleSizeLimit = doc.sampleSizeLimit;
            this.promotionMode = doc.promotionMode;
            this.promotionFloor = doc.promotionFloor;
            this.heldOutFraction = doc.heldOutFraction;
            this.minRecordsToLearn = doc.minRecordsToLearn;
            this.regressionCap = doc.regressionCap;
            this.regressionRetentionDays = doc.regressionRetentionDays;
            this.scorers = doc.scorers;
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

        public Builder relearnThreshold(final double relearnThreshold) {
            this.relearnThreshold = relearnThreshold;
            return self();
        }

        /// The elements the chain question chooses from, which are the flow's Chain step's (A95): named
        /// here, they are put on that step. Kept for the store's reading of documents saved before A95, and
        /// for the fixtures that set them.
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

        /**
         * An example's steps as the document's own, with the built-in text.
         */
        public Builder flow(final FlowExample example) {
            this.flow = LearningFlow.of(example);
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

        public Builder promotionFloor(final double promotionFloor) {
            this.promotionFloor = promotionFloor;
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

        public Builder regressionRetentionDays(final Integer regressionRetentionDays) {
            this.regressionRetentionDays = regressionRetentionDays;
            return self();
        }

        public Builder scorers(final List<ScorerSetting> scorers) {
            this.scorers = scorers;
            return self();
        }

        @Override
        protected Builder self() {
            return this;
        }

        public ShapeshifterAiDoc build() {
            return new ShapeshifterAiDoc(
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
                        relearnThreshold,
                    instructions,
                    flow,
                    maxAttempts,
                    attemptBudgetMs,
                    tokenBudget,
                    maxDeferralAgeMs,
                    sampleSizeLimit,
                    promotionMode,
                    promotionFloor,
                    heldOutFraction,
                    minRecordsToLearn,
                    regressionCap,
                    regressionRetentionDays,
                    scorers);
        }
    }
}
