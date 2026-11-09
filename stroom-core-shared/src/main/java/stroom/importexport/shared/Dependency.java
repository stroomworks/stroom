/*
 * Copyright 2018 Crown Copyright
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

package stroom.importexport.shared;

import stroom.docref.DocRef;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Objects;

@JsonPropertyOrder({"from", "to", "ok", "ambiguous"})
@JsonInclude(Include.NON_NULL)
public class Dependency {

    @JsonProperty
    private DocRef from;
    @JsonProperty
    private DocRef to;
    @JsonProperty
    private boolean ok;
    @JsonProperty
    private boolean ambiguous;

    /// @param from      The document that depends on `to`.
    /// @param to        The document depended on.
    /// @param ok        Whether `to` exists. Null is treated as false.
    /// @param ambiguous Whether `from` names `to` by a name that more than one document has, so `to` is
    ///                  only one of several candidates. Null is treated as false.
    @JsonCreator
    public Dependency(@JsonProperty("from") final DocRef from,
                      @JsonProperty("to") final DocRef to,
                      @JsonProperty("ok") final Boolean ok,
                      @JsonProperty("ambiguous") final Boolean ambiguous) {
        this.from = from;
        this.to = to;
        this.ok = Objects.requireNonNullElse(ok, false);
        this.ambiguous = Objects.requireNonNullElse(ambiguous, false);
    }

    public DocRef getFrom() {
        return from;
    }

    public DocRef getTo() {
        return to;
    }

    public boolean isOk() {
        return ok;
    }

    /// @return true if `from` names `to` by a name that more than one document has, so the runtime
    /// cannot tell which is meant. `to` itself exists.
    public boolean isAmbiguous() {
        return ambiguous;
    }
}
