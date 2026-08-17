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

package stroom.pipeline.shared;

import stroom.docref.DocRef;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One thing an XSLT refers to, as reported to a client.
 * <p>
 * A reference is either resolved ({@code reason} is null) or not. Note that resolved does not imply a
 * document: a map name is fully resolved once its value is known, yet has no target, because a map name
 * does not identify a document on its own - which store a lookup reaches depends on the pipeline's
 * configured references.
 */
@JsonInclude(Include.NON_NULL)
@JsonPropertyOrder({
        "kind",
        "rawValue",
        "target",
        "candidates",
        "reason",
        "direction",
        "lineNumber"})
public class XsltReferenceInfo {

    @JsonProperty
    private final XsltReferenceKind kind;
    @JsonProperty
    private final String rawValue;
    @JsonProperty
    private final DocRef target;
    @JsonProperty
    private final List<DocRef> candidates;
    @JsonProperty
    private final XsltReferenceReason reason;
    @JsonProperty
    private final XsltReferenceDirection direction;
    @JsonProperty
    private final int lineNumber;

    @JsonCreator
    public XsltReferenceInfo(@JsonProperty("kind") final XsltReferenceKind kind,
                             @JsonProperty("rawValue") final String rawValue,
                             @JsonProperty("target") final DocRef target,
                             @JsonProperty("candidates") final List<DocRef> candidates,
                             @JsonProperty("reason") final XsltReferenceReason reason,
                             @JsonProperty("direction") final XsltReferenceDirection direction,
                             @JsonProperty("lineNumber") final int lineNumber) {
        this.kind = kind;
        this.rawValue = rawValue;
        this.target = target;
        this.candidates = candidates == null
                ? Collections.emptyList()
                : candidates;
        this.reason = reason;
        this.direction = direction;
        this.lineNumber = lineNumber;
    }

    public XsltReferenceKind getKind() {
        return kind;
    }

    /**
     * @return the value as written. For an unresolved reference this is the expression that could not be
     * resolved, which is what someone needs in order to find it in the source.
     */
    public String getRawValue() {
        return rawValue;
    }

    /**
     * @return the document referred to, or null where there is none - either because this kind has no
     * document target, or because the reference is unresolved.
     */
    public DocRef getTarget() {
        return target;
    }

    /**
     * @return where the reason is {@link XsltReferenceReason#AMBIGUOUS}, every document the name matched,
     * so the collision can be named. Never null; empty otherwise.
     */
    public List<DocRef> getCandidates() {
        return candidates;
    }

    /**
     * @return why this reference is unresolved, or null if it is resolved.
     */
    public XsltReferenceReason getReason() {
        return reason;
    }

    /**
     * @return for {@link XsltReferenceKind#HTTP}, which way data flows; null otherwise.
     */
    public XsltReferenceDirection getDirection() {
        return direction;
    }

    /**
     * @return the line the reference was found on, or -1 if unknown.
     */
    public int getLineNumber() {
        return lineNumber;
    }

    /**
     * @return true if the value was determined. A resolved reference of a kind that has no document target,
     * such as a map name, still returns true.
     */
    public boolean isResolved() {
        return reason == null;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final XsltReferenceInfo that = (XsltReferenceInfo) o;
        return lineNumber == that.lineNumber
               && kind == that.kind
               && Objects.equals(rawValue, that.rawValue)
               && Objects.equals(target, that.target)
               && Objects.equals(candidates, that.candidates)
               && reason == that.reason
               && direction == that.direction;
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, rawValue, target, candidates, reason, direction, lineNumber);
    }

    @Override
    public String toString() {
        return "XsltReferenceInfo{"
               + "kind=" + kind
               + ", rawValue='" + rawValue + '\''
               + ", target=" + target
               + ", reason=" + reason
               + ", lineNumber=" + lineNumber
               + '}';
    }
}
