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

/**
 * Asks what an XSLT refers to.
 * <p>
 * The body is sent rather than read from the stored document, so an author can check what is in front of
 * them rather than what was last saved. Checking the saved copy would be the less useful of the two: the
 * question "does this reference exist" is asked precisely while editing.
 */
@JsonInclude(Include.NON_NULL)
@JsonPropertyOrder({"docRef", "data"})
public class CheckXsltReferencesRequest {

    @JsonProperty
    private final DocRef docRef;
    @JsonProperty
    private final String data;

    @JsonCreator
    public CheckXsltReferencesRequest(@JsonProperty("docRef") final DocRef docRef,
                                      @JsonProperty("data") final String data) {
        this.docRef = docRef;
        this.data = data;
    }

    /**
     * @return the document being checked. Required: it is what the read permission is checked against.
     */
    public DocRef getDocRef() {
        return docRef;
    }

    /**
     * @return the stylesheet to check, or null to check what is currently stored.
     */
    public String getData() {
        return data;
    }

    @Override
    public String toString() {
        return "CheckXsltReferencesRequest{docRef=" + docRef
               + ", data=" + (data == null
                ? "null"
                : data.length() + " chars")
               + '}';
    }
}
