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

import java.util.Objects;

/**
 * Schema conformance (§8.2, §8.4): the proportion of records that validate, per record, against the
 * schema group the stage's output is meant to conform to. Ruling A16 makes this a gate, not a maximand.
 */
@JsonPropertyOrder({"schemaGroup"})
@JsonInclude(Include.NON_NULL)
public final class SchemaConformanceParameters extends ScorerParameters {

    /**
     * The event-logging schema's group in Stroom's content packs; a transformation stage's default.
     */
    static final String DEFAULT_SCHEMA_GROUP = "EVENTS";

    @JsonProperty
    private final String schemaGroup;

    @JsonCreator
    public SchemaConformanceParameters(@JsonProperty("schemaGroup") final String schemaGroup) {
        this.schemaGroup = schemaGroup == null || schemaGroup.isEmpty()
                ? DEFAULT_SCHEMA_GROUP
                : schemaGroup;
    }

    @Override
    public ScorerType scorerType() {
        return ScorerType.SCHEMA_CONFORMANCE;
    }

    public String getSchemaGroup() {
        return schemaGroup;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        return Objects.equals(schemaGroup, ((SchemaConformanceParameters) o).schemaGroup);
    }

    @Override
    public int hashCode() {
        return Objects.hash(schemaGroup);
    }

    @Override
    public String toString() {
        return "schema group " + schemaGroup;
    }
}
