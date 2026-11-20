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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Schema conformance (§8.2, §8.4): the proportion of records that validate, per record, against the
 * document's target schema ({@link ShapeshifterAiDoc#getTargetSchema()}, D1). Ruling A16 makes this a gate,
 * not a maximand.
 * <p>
 * The check takes no setting of its own: what it validates against is the document's one schema version, so
 * that it is still defined when the check is off, and the questions and the check cannot disagree. A document
 * saved before D1 may carry a schema group here. It is read and dropped, since a group is not one version.
 */
@JsonPropertyOrder({"schemaGroup"})
@JsonInclude(Include.NON_NULL)
public final class SchemaConformanceParameters extends ScorerParameters {

    /// Always null. The property is kept so that a document saved before D1 still reads, and so that the
    /// parameters are a bean with a property to serialise.
    @JsonProperty
    private final String schemaGroup;

    /// @param schemaGroup The schema group a document saved before D1 named, which is dropped: the check
    ///                    validates against the document's target schema.
    @JsonCreator
    public SchemaConformanceParameters(@JsonProperty("schemaGroup") final String schemaGroup) {
        this.schemaGroup = null;
    }

    /// The parameters a check is given: none of its own, since it validates against the document's target.
    public SchemaConformanceParameters() {
        this(null);
    }

    @Override
    public Check check() {
        return Check.CONFORMANCE;
    }

    @Override
    public boolean equals(final Object o) {
        return this == o || (o != null && getClass() == o.getClass());
    }

    @Override
    public int hashCode() {
        return SchemaConformanceParameters.class.hashCode();
    }

    @Override
    public String toString() {
        return "the document's target schema";
    }
}
