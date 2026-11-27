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

package stroom.learningflow.shared;

import stroom.docref.DocRef;
import stroom.pipeline.shared.stepping.ElementStepDetails;
import stroom.pipeline.shared.stepping.NestedElementData;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/// What the Smart Pipeline element did with the stream at the stepper's cursor: the contents of the Learning
/// Flow pane the stepper shows in place of a code pane, built on the server from the element's run.
///
/// The element has no single document to show as code (its Learning Flow document is edited in its own
/// editor), and what a person stepping through it needs to see is the decision: the stream's feed and type,
/// which binding served it, what the checks made of it, and what was said to the model if it was learned.
/// The fragment and the document are carried as [DocRef]s so that the pane can show them as links.
@JsonInclude(Include.NON_NULL)
public class LearningFlowStepDetails extends ElementStepDetails {

    @JsonProperty
    private final boolean dryRun;
    @JsonProperty
    private final DocRef document;
    @JsonProperty
    private final Map<String, String> stream;
    @JsonProperty
    private final String decision;
    @JsonProperty
    private final String reason;
    @JsonProperty
    private final String bindingUuid;
    @JsonProperty
    private final DocRef fragment;
    @JsonProperty
    private final Double score;
    @JsonProperty
    private final String recordBoundary;
    @JsonProperty
    private final List<StageVerdict> verdicts;
    @JsonProperty
    private final List<SupervisorTurn> transcript;
    @JsonProperty
    private final List<NestedElementData> nested;

    @JsonCreator
    public LearningFlowStepDetails(@JsonProperty("dryRun") final Boolean dryRun,
                                     @JsonProperty("document") final DocRef document,
                                     @JsonProperty("stream") final Map<String, String> stream,
                                     @JsonProperty("decision") final String decision,
                                     @JsonProperty("reason") final String reason,
                                     @JsonProperty("bindingUuid") final String bindingUuid,
                                     @JsonProperty("fragment") final DocRef fragment,
                                     @JsonProperty("score") final Double score,
                                     @JsonProperty("recordBoundary") final String recordBoundary,
                                     @JsonProperty("verdicts") final List<StageVerdict> verdicts,
                                     @JsonProperty("transcript") final List<SupervisorTurn> transcript,
                                     @JsonProperty("nested") final List<NestedElementData> nested) {
        this.dryRun = Objects.requireNonNullElse(dryRun, false);
        this.document = document;
        this.stream = stream;
        this.decision = decision;
        this.reason = reason;
        this.bindingUuid = bindingUuid;
        this.fragment = fragment;
        this.score = score;
        this.recordBoundary = recordBoundary;
        this.verdicts = verdicts;
        this.transcript = transcript;
        this.nested = nested;
    }

    /// The fragment's own elements and what each made of the record at the cursor. These are the chain the
    /// element ran, opened up, so that a learned `DSParser -> XSLTFilter` steps like any other pair of
    /// elements.
    ///
    /// @return The nested elements; empty where nothing was bound and so nothing ran, and where the run came
    /// from a stand-in rather than from a pipeline.
    @Override
    public List<NestedElementData> getNested() {
        return nested == null
                ? List.of()
                : nested;
    }

    /// Attaches the fragment's elements and what the record at the cursor made of each. This is done per
    /// record, because the decision applies to the whole stream but the elements' output differs per record.
    ///
    /// @param elements The fragment's elements for the record at the cursor.
    /// @return A copy of these details with those elements attached.
    public LearningFlowStepDetails withNested(final List<NestedElementData> elements) {
        return new LearningFlowStepDetails(dryRun, document, stream, decision, reason, bindingUuid,
                fragment, score, recordBoundary, verdicts, transcript, elements);
    }

    /// @return Whether this came from the stepper rather than from processing. Stepping only serves with an
    /// existing binding and never learns, so for a stream that nothing serves the pane shows what the element
    /// *would* have done.
    public boolean isDryRun() {
        return dryRun;
    }

    public DocRef getDocument() {
        return document;
    }

    /// @return The stream's feed and type, which is what the model is shown of the stream's metadata when it
    /// learns.
    public Map<String, String> getStream() {
        return stream;
    }

    /// @return The kind of decision made, as the server's decision type name, e.g. `Bound`, `Promoted`,
    /// `Drafted`, `Deferred`, `Parked` or `GivenUp`; `Would` where this was a step and nothing was done.
    /// [#getReason()] describes it in a sentence.
    public String getDecision() {
        return decision;
    }

    public String getReason() {
        return reason;
    }

    public String getBindingUuid() {
        return bindingUuid;
    }

    /// @return The fragment that produced the output, or the one a step would have used; null where nothing
    /// was bound and nothing would have been.
    public DocRef getFragment() {
        return fragment;
    }

    public Double getScore() {
        return score;
    }

    /// @return What one record was in this stream, as the binding recorded it; null for a chain over raw text,
    /// where the parser's own configuration cuts the records.
    public String getRecordBoundary() {
        return recordBoundary;
    }

    public List<StageVerdict> getVerdicts() {
        return verdicts;
    }

    /// @return Every exchange with the model, oldest first; empty where the stream was served rather than
    /// learned.
    public List<SupervisorTurn> getTranscript() {
        return transcript;
    }

    @Override
    public String toString() {
        return decision + ": " + reason;
    }
}
