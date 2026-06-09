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

package stroom.pathways.shared;

import stroom.docref.DocRef;
import stroom.docstore.shared.DocumentType;
import stroom.docstore.shared.DocumentTypeRegistry;
import stroom.planb.shared.AbstractPlanBSettings;
import stroom.planb.shared.PlanBDoc;
import stroom.planb.shared.StateType;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

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
        "stateType",
        "settings"
})
@JsonInclude(Include.NON_NULL)
public class TracesDoc extends PlanBDoc {

    public static final String TYPE = "Traces";
    public static final DocumentType DOCUMENT_TYPE = DocumentTypeRegistry.TRACES_DOCUMENT_TYPE;

    @JsonCreator
    public TracesDoc(
            @JsonProperty("uuid") final String uuid,
            @JsonProperty("name") final String name,
            @JsonProperty("version") final String version,
            @JsonProperty("createTimeMs") final Long createTimeMs,
            @JsonProperty("updateTimeMs") final Long updateTimeMs,
            @JsonProperty("createUser") final String createUser,
            @JsonProperty("updateUser") final String updateUser,
            @JsonProperty("description") final String description,
            @JsonProperty("stateType") final StateType stateType,
            @JsonProperty("settings") final AbstractPlanBSettings settings) {
        super(TYPE, uuid, name, version, createTimeMs, updateTimeMs, createUser, updateUser,
                description, stateType == null ? StateType.TRACE : stateType, settings);
    }

    /**
     * @return A new {@link DocRef} for this document's type with the supplied uuid.
     */
    public static DocRef getDocRef(final String uuid) {
        return DocRef.builder(TYPE)
                .uuid(uuid)
                .build();
    }

    /**
     * @return A new builder for creating a {@link DocRef} for this document's type.
     */
    public static DocRef.TypedBuilder buildDocRef() {
        return DocRef.builder(TYPE);
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        return super.equals(o);
    }

    @Override
    public int hashCode() {
        return super.hashCode();
    }

    @Override
    public String toString() {
        return "TracesDoc{" +
               "type='" + getType() + '\'' +
               ", uuid='" + getUuid() + '\'' +
               ", name='" + getName() + '\'' +
               ", description='" + getDescription() + '\'' +
               ", stateType=" + getStateType() +
               ", settings=" + getSettings() +
               '}';
    }

    public Builder copyTraces() {
        return new Builder(this);
    }

    public static Builder tracesBuilder() {
        return new Builder();
    }

    public static final class Builder
            extends AbstractBuilder<TracesDoc, Builder> {

        private String description;
        private StateType stateType = StateType.TRACE;
        private AbstractPlanBSettings settings;

        private Builder() {
        }

        private Builder(final TracesDoc tracesDoc) {
            super(tracesDoc);
            this.description = tracesDoc.getDescription();
            this.stateType = tracesDoc.getStateType();
            this.settings = tracesDoc.getSettings();
        }

        public Builder description(final String description) {
            this.description = description;
            return self();
        }

        public Builder stateType(final StateType stateType) {
            this.stateType = stateType;
            return self();
        }

        public Builder settings(final AbstractPlanBSettings settings) {
            this.settings = settings;
            return self();
        }

        @Override
        protected Builder self() {
            return this;
        }

        @Override
        public TracesDoc build() {
            return new TracesDoc(
                    uuid,
                    name,
                    version,
                    createTimeMs,
                    updateTimeMs,
                    createUser,
                    updateUser,
                    description,
                    stateType,
                    settings);
        }
    }
}
