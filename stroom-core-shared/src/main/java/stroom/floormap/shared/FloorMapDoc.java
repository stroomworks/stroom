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

package stroom.floormap.shared;

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

import java.util.Objects;

@Description(
        """
                Defines a floor map document which can be used to visualize data over time.
                """)
@JsonPropertyOrder(alphabetic = true)
@JsonInclude(Include.NON_NULL)
public class FloorMapDoc extends AbstractDoc {

    public static final String TYPE = "FloorMap";
    public static final DocumentType DOCUMENT_TYPE = DocumentTypeRegistry.FLOOR_MAP_DOCUMENT_TYPE;

    @JsonProperty
    private final String description;
    @JsonProperty
    private final String template;
    @JsonProperty
    private final DocRef feed;
    @JsonProperty
    private final String backgroundImage; // Base64 or URL for the map background

    @JsonCreator
    public FloorMapDoc(@JsonProperty("uuid") final String uuid,
                       @JsonProperty("name") final String name,
                       @JsonProperty("version") final String version,
                       @JsonProperty("createTimeMs") final Long createTimeMs,
                       @JsonProperty("updateTimeMs") final Long updateTimeMs,
                       @JsonProperty("createUser") final String createUser,
                       @JsonProperty("updateUser") final String updateUser,
                       @JsonProperty("description") final String description,
                       @JsonProperty("template") final String template,
                       @JsonProperty("feed") final DocRef feed,
                       @JsonProperty("backgroundImage") final String backgroundImage) {
        super(TYPE, uuid,
                name,
                version,
                createTimeMs,
                updateTimeMs,
                createUser,
                updateUser);

        this.description = description;
        this.template = template;
        this.feed = feed;
        this.backgroundImage = backgroundImage;
    }

    public String getDescription() {
        return description;
    }

    public String getTemplate() {
        return template;
    }

    public DocRef getFeed() {
        return feed;
    }

    public String getBackgroundImage() {
        return backgroundImage;
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
        if (!super.equals(o)) {
            return false;
        }
        final FloorMapDoc that = (FloorMapDoc) o;
        return Objects.equals(description, that.description) &&
               Objects.equals(template, that.template) &&
               Objects.equals(feed, that.feed) &&
               Objects.equals(backgroundImage, that.backgroundImage);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), description, template, feed, backgroundImage);
    }

    public Builder copy() {
        return new Builder(this);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder extends AbstractBuilder<FloorMapDoc, Builder> {

        private String template;
        private String description;
        private DocRef feed;
        private String backgroundImage;

        public Builder() {
        }

        public Builder(final FloorMapDoc doc) {
            super(doc);
            this.template = doc.template;
            this.description = doc.description;
            this.feed = doc.feed;
            this.backgroundImage = doc.backgroundImage;
        }

        public Builder template(final String template) {
            this.template = template;
            return self();
        }

        public Builder description(final String description) {
            this.description = description;
            return self();
        }

        public Builder feed(final DocRef feed) {
            this.feed = feed;
            return self();
        }

        public Builder backgroundImage(final String backgroundImage) {
            this.backgroundImage = backgroundImage;
            return self();
        }

        @Override
        protected Builder self() {
            return this;
        }

        @Override
        public FloorMapDoc build() {
            return new FloorMapDoc(
                    uuid,
                    name,
                    version,
                    createTimeMs,
                    updateTimeMs,
                    createUser,
                    updateUser,
                    description,
                    template,
                    feed,
                    backgroundImage);
        }
    }
}
