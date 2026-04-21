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

package stroom.domaintype.shared;

import stroom.docref.DocRef;
import stroom.docs.shared.Description;
import stroom.docstore.shared.AbstractDoc;
import stroom.docstore.shared.DocumentType;
import stroom.docstore.shared.DocumentTypeRegistry;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Objects;

@Description(
        "A Domain Type Doc represents a collection of Domain Types within Stroom.")
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
        "domainTypes"})
@JsonInclude(Include.NON_NULL)
public class DomainTypeDoc extends AbstractDoc {

    public static final String TYPE = "DomainType";
    public static final DocumentType DOCUMENT_TYPE = DocumentTypeRegistry.DOMAIN_TYPE_DOCUMENT_TYPE;

    @JsonProperty
    private final String description;
    @JsonProperty
    private final List<DomainType> domainTypes;

    @JsonCreator
    public DomainTypeDoc(@JsonProperty("uuid") final String uuid,
                         @JsonProperty("name") final String name,
                         @JsonProperty("version") final String version,
                         @JsonProperty("createTimeMs") final Long createTimeMs,
                         @JsonProperty("updateTimeMs") final Long updateTimeMs,
                         @JsonProperty("createUser") final String createUser,
                         @JsonProperty("updateUser") final String updateUser,
                         @JsonProperty("description") final String description,
                         @JsonProperty("domainTypes") final List<DomainType> domainTypes) {
        super(TYPE, uuid, name, version, createTimeMs, updateTimeMs, createUser, updateUser);
        this.description = description;
        this.domainTypes = domainTypes;
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

    public String getDescription() {
        return description;
    }

    public List<DomainType> getDomainTypes() {
        return domainTypes;
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
        final DomainTypeDoc that = (DomainTypeDoc) o;
        return Objects.equals(description, that.description) &&
               Objects.equals(domainTypes, that.domainTypes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), description, domainTypes);
    }

    public Builder copy() {
        return new Builder(this);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder
            extends AbstractBuilder<DomainTypeDoc, Builder> {

        private String description;
        private List<DomainType> domainTypes;

        private Builder() {
        }

        private Builder(final DomainTypeDoc domainTypeDoc) {
            super(domainTypeDoc);
            this.description = domainTypeDoc.description;
            this.domainTypes = domainTypeDoc.domainTypes;
        }

        public Builder description(final String description) {
            this.description = description;
            return self();
        }

        public Builder domainTypes(final List<DomainType> domainTypes) {
            this.domainTypes = domainTypes;
            return self();
        }

        @Override
        protected Builder self() {
            return this;
        }

        public DomainTypeDoc build() {
            return new DomainTypeDoc(
                    uuid,
                    name,
                    version,
                    createTimeMs,
                    updateTimeMs,
                    createUser,
                    updateUser,
                    description,
                    domainTypes);
        }
    }
}
