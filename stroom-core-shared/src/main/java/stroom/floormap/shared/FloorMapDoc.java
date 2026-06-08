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
import stroom.query.api.TimeRange;
import stroom.query.shared.QueryTablePreferences;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Comparator;
import java.util.List;
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
    private final List<FloorMapBackground> backgroundImages;
    @JsonProperty
    private final String query;
    @JsonProperty
    private final TimeRange queryTimeRange;
    @JsonProperty
    private final QueryTablePreferences queryTablePreferences;
    @JsonProperty
    private final FloorMapTransformationMatrix matrix;
    @JsonProperty
    private final String entityIdColumn;
    @JsonProperty
    private final String locationIdColumn;

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
                       @JsonProperty("backgroundImages") final List<FloorMapBackground> backgroundImages,
                       @JsonProperty("query") final String query,
                       @JsonProperty("queryTimeRange") final TimeRange queryTimeRange,
                       @JsonProperty("queryTablePreferences") final QueryTablePreferences queryTablePreferences,
                       @JsonProperty("matrix") final FloorMapTransformationMatrix matrix,
                       @JsonProperty("entityIdColumn") final String entityIdColumn,
                       @JsonProperty("locationIdColumn") final String locationIdColumn) {
        super(TYPE, uuid,
                name,
                version,
                createTimeMs,
                updateTimeMs,
                createUser,
                updateUser);

        this.description = description;
        this.template = template;
        this.backgroundImages = backgroundImages;
        this.query = query;
        this.queryTimeRange = queryTimeRange;
        this.queryTablePreferences = queryTablePreferences;
        this.matrix = matrix != null ? matrix : FloorMapTransformationMatrix.identity();
        this.entityIdColumn = entityIdColumn;
        this.locationIdColumn = locationIdColumn;
    }

    public String getDescription() {
        return description;
    }

    public String getTemplate() {
        return template;
    }

    public List<FloorMapBackground> getBackgroundImages() {
        return backgroundImages;
    }

    public String getQuery() {
        return query;
    }

    public TimeRange getQueryTimeRange() {
        return queryTimeRange;
    }

    public QueryTablePreferences getQueryTablePreferences() {
        return queryTablePreferences;
    }

    /**
     * Gets the background image that should be active at the specified time.
     * Finds the image with the latest validFromTime that is <= currentTime.
     */
    public FloorMapBackground getActiveBackground(final long currentTime) {
        FloorMapBackground active = null;
        if (backgroundImages != null) {
            for (final FloorMapBackground bg : backgroundImages) {
                if (bg.getValidFromTime() <= currentTime) {
                    active = bg;
                } else {
                    // Since the list is sorted, we can stop here.
                    break;
                }
            }
        }
        return active;
    }

    public String getEntityIdColumn() {
        return entityIdColumn;
    }

    public String getLocationIdColumn() {
        return locationIdColumn;
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
               Objects.equals(backgroundImages, that.backgroundImages) &&
               Objects.equals(query, that.query) &&
               Objects.equals(queryTimeRange, that.queryTimeRange) &&
               Objects.equals(queryTablePreferences, that.queryTablePreferences) &&
               Objects.equals(matrix, that.matrix) &&
               Objects.equals(entityIdColumn, that.entityIdColumn) &&
               Objects.equals(locationIdColumn, that.locationIdColumn);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                super.hashCode(),
                description,
                template,
                backgroundImages,
                query,
                queryTimeRange,
                queryTablePreferences,
                matrix,
                entityIdColumn,
                locationIdColumn);
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
        private List<FloorMapBackground> backgroundImages;
        private String query;
        private TimeRange queryTimeRange;
        private QueryTablePreferences queryTablePreferences;
        private FloorMapTransformationMatrix matrix;
        private String entityIdColumn;
        private String locationIdColumn;

        public Builder() {
        }

        public Builder(final FloorMapDoc doc) {
            super(doc);
            this.template = doc.template;
            this.description = doc.description;
            this.backgroundImages = doc.backgroundImages;
            this.query = doc.query;
            this.queryTimeRange = doc.queryTimeRange;
            this.queryTablePreferences = doc.queryTablePreferences;
            this.matrix = doc.matrix;
            this.entityIdColumn = doc.entityIdColumn;
            this.locationIdColumn = doc.locationIdColumn;
        }

        public Builder template(final String template) {
            this.template = template;
            return self();
        }

        public Builder description(final String description) {
            this.description = description;
            return self();
        }

        public Builder backgroundImages(final List<FloorMapBackground> backgroundImages) {
            this.backgroundImages = backgroundImages;
            return self();
        }

        public Builder query(final String query) {
            this.query = query;
            return self();
        }

        public Builder queryTimeRange(final TimeRange queryTimeRange) {
            this.queryTimeRange = queryTimeRange;
            return self();
        }

        public Builder queryTablePreferences(final QueryTablePreferences queryTablePreferences) {
            this.queryTablePreferences = queryTablePreferences;
            return self();
        }

        public Builder matrix(final FloorMapTransformationMatrix matrix) {
            this.matrix = matrix;
            return self();
        }

        public Builder entityIdColumn(final String entityIdColumn) {
            this.entityIdColumn = entityIdColumn;
            return self();
        }

        public Builder locationIdColumn(final String locationIdColumn) {
            this.locationIdColumn = locationIdColumn;
            return self();
        }

        @Override
        protected Builder self() {
            return this;
        }

        @Override
        public FloorMapDoc build() {
            // Ensure the list is sorted by time before building
            if (backgroundImages != null) {
                backgroundImages.sort(Comparator.comparingLong(FloorMapBackground::getValidFromTime));
            }

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
                    backgroundImages,
                    query,
                    queryTimeRange,
                    queryTablePreferences,
                    matrix,
                    entityIdColumn,
                    locationIdColumn);
        }
    }
}
