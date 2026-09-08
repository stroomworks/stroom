/*
 * Copyright 2017 Crown Copyright
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
import stroom.docs.shared.Description;
import stroom.docstore.shared.AbstractDoc;
import stroom.docstore.shared.DocumentType;
import stroom.docstore.shared.DocumentTypeRegistry;
import stroom.pathways.shared.pathway.LockState;
import stroom.pathways.shared.pathway.Pathway;
import stroom.pathways.shared.pathway.PathwayLocks;
import stroom.util.shared.time.SimpleDuration;
import stroom.util.shared.time.TimeUnit;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Objects;

@Description(
        """
        Analyses trace logs held in a Plan B store to learn the paths that traces take between services, \
        e.g. A -> B -> C.
        Each distinct path is remembered, so that a new or changed path can be reported as soon as it appears.
        For each span on a path it also learns constraints on the span's duration, kind, flags and \
        attributes, held as an exact value, a set, a range or a regular expression, and widens them as \
        further traces are seen.
        Whether new paths and constraints may be added, and whether those already learnt may be widened, \
        is controlled per document, so Pathways can be left learning or fixed so that anything deviating \
        from what it has learnt is reported instead of absorbed.
        Findings are written to a nominated Feed for analytic rules to act on.
        Pathways makes no judgement about the changes it reports.
        """)
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
        "pathways"})
@JsonInclude(Include.NON_NULL)
public class PathwaysDoc extends AbstractDoc {

    public static final String TYPE = "Pathways";
    public static final DocumentType DOCUMENT_TYPE = DocumentTypeRegistry.PATHWAYS_DOCUMENT_TYPE;

    @JsonProperty
    private final String description;
    @JsonProperty
    private final SimpleDuration temporalOrderingTolerance;
    @JsonProperty
    private final List<Pathway> pathways;
    @JsonProperty
    private final PathwayLocks locks;
    @JsonProperty
    private final PathwayLocks childLockDefaults;
    @JsonProperty
    private final LockState pathwayDiscovery;
    @JsonProperty
    private final DocRef tracesDocRef;
    @JsonProperty
    private final DocRef infoFeed;
    @JsonProperty
    private final String processingNode;

    @JsonCreator
    public PathwaysDoc(@JsonProperty("uuid") final String uuid,
                       @JsonProperty("name") final String name,
                       @JsonProperty("version") final String version,
                       @JsonProperty("createTimeMs") final Long createTimeMs,
                       @JsonProperty("updateTimeMs") final Long updateTimeMs,
                       @JsonProperty("createUser") final String createUser,
                       @JsonProperty("updateUser") final String updateUser,
                       @JsonProperty("description") final String description,
                       @JsonProperty("temporalOrderingTolerance") final SimpleDuration temporalOrderingTolerance,
                       @JsonProperty("pathways") final List<Pathway> pathways,
                       @JsonProperty("locks") final PathwayLocks locks,
                       @JsonProperty("childLockDefaults") final PathwayLocks childLockDefaults,
                       @JsonProperty("pathwayDiscovery") final LockState pathwayDiscovery,
                       @JsonProperty("tracesDocRef") final DocRef tracesDocRef,
                       @JsonProperty("infoFeed") final DocRef infoFeed,
                       @JsonProperty("processingNode") final String processingNode) {
        super(TYPE, uuid, name, version, createTimeMs, updateTimeMs, createUser, updateUser);
        this.description = description;
        this.temporalOrderingTolerance = temporalOrderingTolerance;
        this.pathways = pathways;
        this.locks = locks != null
                ? locks
                : PathwayLocks.builder().build();
        this.childLockDefaults = childLockDefaults != null
                ? childLockDefaults
                : PathwayLocks.builder().build();
        this.pathwayDiscovery = pathwayDiscovery != null
                ? pathwayDiscovery
                : LockState.INHERIT;
        this.tracesDocRef = tracesDocRef;
        this.infoFeed = infoFeed;
        this.processingNode = processingNode;
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

    public SimpleDuration getTemporalOrderingTolerance() {
        return temporalOrderingTolerance;
    }

    public List<Pathway> getPathways() {
        return pathways;
    }

    public PathwayLocks getLocks() {
        return locks;
    }

    public PathwayLocks getChildLockDefaults() {
        return childLockDefaults;
    }

    public LockState getPathwayDiscovery() {
        return pathwayDiscovery;
    }

    public DocRef getTracesDocRef() {
        return tracesDocRef;
    }

    public DocRef getInfoFeed() {
        return infoFeed;
    }

    public String getProcessingNode() {
        return processingNode;
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
        final PathwaysDoc that = (PathwaysDoc) o;
        return Objects.equals(description, that.description) &&
               Objects.equals(temporalOrderingTolerance, that.temporalOrderingTolerance) &&
               Objects.equals(pathways, that.pathways) &&
               Objects.equals(locks, that.locks) &&
               Objects.equals(childLockDefaults, that.childLockDefaults) &&
               pathwayDiscovery == that.pathwayDiscovery &&
               Objects.equals(tracesDocRef, that.tracesDocRef) &&
               Objects.equals(infoFeed, that.infoFeed) &&
               Objects.equals(processingNode, that.processingNode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(),
                description,
                temporalOrderingTolerance,
                pathways,
                locks,
                childLockDefaults,
                pathwayDiscovery,
                tracesDocRef,
                infoFeed,
                processingNode);
    }

    @Override
    public String toString() {
        return "PathwaysDoc{" +
               "description='" + description + '\'' +
               ", temporalOrderingTolerance=" + temporalOrderingTolerance +
               ", pathways=" + pathways +
               ", locks=" + locks +
               ", childLockDefaults=" + childLockDefaults +
               ", pathwayDiscovery=" + pathwayDiscovery +
               ", tracesDocRef=" + tracesDocRef +
               ", infoFeed=" + infoFeed +
               ", processingNode=" + processingNode +
               '}';
    }

    public Builder copy() {
        return new Builder(this);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder
            extends AbstractBuilder<PathwaysDoc, Builder> {

        private String description;
        private SimpleDuration temporalOrderingTolerance = new SimpleDuration(0L, TimeUnit.NANOSECONDS);
        private List<Pathway> pathways;
        private PathwayLocks locks;
        private PathwayLocks childLockDefaults;
        private LockState pathwayDiscovery;
        private DocRef tracesDocRef;
        private DocRef infoFeed;
        private String processingNode;

        private Builder() {
        }

        private Builder(final PathwaysDoc pathwaysDoc) {
            super(pathwaysDoc);
            this.description = pathwaysDoc.description;
            this.temporalOrderingTolerance = pathwaysDoc.temporalOrderingTolerance;
            this.pathways = pathwaysDoc.pathways;
            this.locks = pathwaysDoc.locks;
            this.childLockDefaults = pathwaysDoc.childLockDefaults;
            this.pathwayDiscovery = pathwaysDoc.pathwayDiscovery;
            this.tracesDocRef = pathwaysDoc.tracesDocRef;
            this.infoFeed = pathwaysDoc.infoFeed;
            this.processingNode = pathwaysDoc.processingNode;
        }

        public Builder description(final String description) {
            this.description = description;
            return self();
        }

        public Builder temporalOrderingTolerance(final SimpleDuration temporalOrderingTolerance) {
            this.temporalOrderingTolerance = temporalOrderingTolerance;
            return self();
        }

        public Builder pathways(final List<Pathway> pathways) {
            this.pathways = pathways;
            return self();
        }

        public Builder locks(final PathwayLocks locks) {
            this.locks = locks;
            return self();
        }

        public Builder childLockDefaults(final PathwayLocks childLockDefaults) {
            this.childLockDefaults = childLockDefaults;
            return self();
        }

        public Builder pathwayDiscovery(final LockState pathwayDiscovery) {
            this.pathwayDiscovery = pathwayDiscovery;
            return self();
        }

        public Builder tracesDocRef(final DocRef tracesDocRef) {
            this.tracesDocRef = tracesDocRef;
            return self();
        }

        public Builder infoFeed(final DocRef infoFeed) {
            this.infoFeed = infoFeed;
            return self();
        }

        public Builder processingNode(final String processingNode) {
            this.processingNode = processingNode;
            return self();
        }

        @Override
        protected Builder self() {
            return this;
        }

        public PathwaysDoc build() {
            return new PathwaysDoc(
                    uuid,
                    name,
                    version,
                    createTimeMs,
                    updateTimeMs,
                    createUser,
                    updateUser,
                    description,
                    temporalOrderingTolerance,
                    pathways,
                    locks,
                    childLockDefaults,
                    pathwayDiscovery,
                    tracesDocRef,
                    infoFeed,
                    processingNode);
        }
    }
}
