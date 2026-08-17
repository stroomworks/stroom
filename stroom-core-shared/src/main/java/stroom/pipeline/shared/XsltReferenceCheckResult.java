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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * What an XSLT refers to, and what could not be determined.
 * <p>
 * A <b>parse failure</b> is reported separately from the findings on purpose. A malformed stylesheet and a
 * stylesheet naming a document that does not exist are different events with different audiences: the first
 * is already known to whoever is editing it, while the second is the silent failure this check exists to
 * surface. A client should present them differently, and should not treat a mid-edit stylesheet as an error
 * worth interrupting anyone about.
 */
@JsonInclude(Include.NON_NULL)
@JsonPropertyOrder({"references", "parseFailure"})
public class XsltReferenceCheckResult {

    @JsonProperty
    private final List<XsltReferenceInfo> references;
    @JsonProperty
    private final String parseFailure;

    @JsonCreator
    public XsltReferenceCheckResult(
            @JsonProperty("references") final List<XsltReferenceInfo> references,
            @JsonProperty("parseFailure") final String parseFailure) {
        this.references = references == null
                ? Collections.emptyList()
                : references;
        this.parseFailure = parseFailure;
    }

    /**
     * @return the findings, in document order. Never null.
     */
    public List<XsltReferenceInfo> getReferences() {
        return references;
    }

    /**
     * @return why the stylesheet could not be read in full, or null if it could. Where this is set the
     * findings may be incomplete.
     */
    public String getParseFailure() {
        return parseFailure;
    }

    @JsonIgnore
    public boolean hasParseFailure() {
        return parseFailure != null;
    }

    /**
     * @return the findings worth an author's attention: a name that matched no document, and a name that
     * matched several. Everything else either resolved, or could not be known without running the
     * stylesheet, which is normal and not a fault.
     */
    @JsonIgnore
    public List<XsltReferenceInfo> getProblems() {
        // Collectors.toList() rather than Stream.toList(): this class is GWT compiled, and GWT's stream
        // emulation does not provide the Java 16 method. Every other stream in this module does the same.
        return references.stream()
                .filter(reference -> XsltReferenceReason.NOT_FOUND == reference.getReason()
                                     || XsltReferenceReason.AMBIGUOUS == reference.getReason())
                .collect(Collectors.toList());
    }

    @Override
    public String toString() {
        return "XsltReferenceCheckResult{references=" + references.size()
               + ", problems=" + getProblems().size()
               + ", parseFailure=" + parseFailure
               + '}';
    }
}
