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

package stroom.shapeshifter.shared;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/// What a QA person said about a Smart Pipeline's translated output: that it looks right, or an issue for the Data
/// Provider to fix at the pipeline, open until someone who may edit the pipeline resolves it.
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QualityAssessment {

    @JsonProperty
    private final long id;
    @JsonProperty
    private final long createTimeMs;
    @JsonProperty
    private final String bindingUuid;
    @JsonProperty
    private final AssessmentVerdict verdict;
    @JsonProperty
    private final String comment;
    @JsonProperty
    private final String author;
    @JsonProperty
    private final String resolvedBy;
    @JsonProperty
    private final Long resolveTimeMs;

    @JsonCreator
    public QualityAssessment(@JsonProperty("id") final Long id,
                             @JsonProperty("createTimeMs") final Long createTimeMs,
                             @JsonProperty("bindingUuid") final String bindingUuid,
                             @JsonProperty("verdict") final AssessmentVerdict verdict,
                             @JsonProperty("comment") final String comment,
                             @JsonProperty("author") final String author,
                             @JsonProperty("resolvedBy") final String resolvedBy,
                             @JsonProperty("resolveTimeMs") final Long resolveTimeMs) {
        this.id = Objects.requireNonNullElse(id, 0L);
        this.createTimeMs = Objects.requireNonNullElse(createTimeMs, 0L);
        this.bindingUuid = bindingUuid;
        this.verdict = verdict;
        this.comment = comment;
        this.author = author;
        this.resolvedBy = resolvedBy;
        this.resolveTimeMs = resolveTimeMs;
    }

    /// @return Its id.
    public long getId() {
        return id;
    }

    /// @return When it was said, in milliseconds since the epoch.
    public long getCreateTimeMs() {
        return createTimeMs;
    }

    /// @return The translation in use when it was said, or null where none was.
    public String getBindingUuid() {
        return bindingUuid;
    }

    /// @return What was said of it.
    public AssessmentVerdict getVerdict() {
        return verdict;
    }

    /// @return What the person wrote, or null for nothing.
    public String getComment() {
        return comment;
    }

    /// @return Who said it.
    public String getAuthor() {
        return author;
    }

    /// @return Who resolved it, or null while it is open.
    public String getResolvedBy() {
        return resolvedBy;
    }

    /// @return When it was resolved, or null while it is open.
    public Long getResolveTimeMs() {
        return resolveTimeMs;
    }
}
