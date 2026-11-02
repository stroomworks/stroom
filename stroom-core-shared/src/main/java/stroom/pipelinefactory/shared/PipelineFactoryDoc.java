/*
 * Copyright 2026 Crown Copyright
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

package stroom.pipelinefactory.shared;

import stroom.docref.DocRef;
import stroom.docs.shared.Description;
import stroom.docstore.shared.AbstractDoc;
import stroom.docstore.shared.DocumentType;
import stroom.docstore.shared.DocumentTypeRegistry;
import stroom.pipeline.shared.PipelineDoc;
import stroom.query.api.ExpressionOperator;
import stroom.util.shared.UserRef;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/// The configuration of one Pipeline Factory (design 06 §3.1, A103): which streams it looks at, what
/// makes one pipeline, the template every pipeline it makes inherits, where they go and what they are
/// called, what it writes onto them, who it makes them as, and the settings of the processor filter it
/// makes for each.
///
/// A factory knows nothing of what its pipelines do. What it has made is runtime state and lives in its
/// table, not here.
///
/// A factory may be saved incomplete while it is disabled. Enabling it is what asks for a template and
/// a run-as user, since that is when the job starts making pipelines from them.
@Description(
        "Makes a pipeline and an enabled processor filter for each new combination of feed and type (or "
        + "whichever key it is given) that it finds among the streams its filter matches. Every pipeline "
        + "it makes inherits its template, so a change to the template reaches them all.")
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
        "enabled",
        "streamFilter",
        "key",
        "template",
        "folderPattern",
        "namePattern",
        "properties",
        "runAsUser",
        "filterSettings"})
@JsonInclude(Include.NON_NULL)
public class PipelineFactoryDoc extends AbstractDoc {

    public static final String TYPE = "PipelineFactory";
    public static final DocumentType DOCUMENT_TYPE = DocumentTypeRegistry.PIPELINE_FACTORY_DOCUMENT_TYPE;

    /// A feed's pipelines together, one folder per feed.
    static final String DEFAULT_FOLDER_PATTERN = "Feeds/${feed}";
    static final String DEFAULT_NAME_PATTERN = "${feed} ${type}";

    @JsonProperty
    private final String description;
    @JsonProperty
    private final boolean enabled;
    @JsonProperty
    private final ExpressionOperator streamFilter;
    @JsonProperty
    private final List<String> key;
    @JsonProperty
    private final DocRef template;
    @JsonProperty
    private final String folderPattern;
    @JsonProperty
    private final String namePattern;
    @JsonProperty
    private final List<PipelineFactoryProperty> properties;
    @JsonProperty
    private final UserRef runAsUser;
    @JsonProperty
    private final PipelineFactoryFilterSettings filterSettings;

    /// Every setting left null takes its default, so a factory written before a setting existed reads
    /// back as one made with the default. The arguments are the document's fields, each described on its
    /// getter.
    @JsonCreator
    public PipelineFactoryDoc(@JsonProperty("uuid") final String uuid,
                              @JsonProperty("name") final String name,
                              @JsonProperty("version") final String version,
                              @JsonProperty("createTimeMs") final Long createTimeMs,
                              @JsonProperty("updateTimeMs") final Long updateTimeMs,
                              @JsonProperty("createUser") final String createUser,
                              @JsonProperty("updateUser") final String updateUser,
                              @JsonProperty("description") final String description,
                              @JsonProperty("enabled") final boolean enabled,
                              @JsonProperty("streamFilter") final ExpressionOperator streamFilter,
                              @JsonProperty("key") final List<String> key,
                              @JsonProperty("template") final DocRef template,
                              @JsonProperty("folderPattern") final String folderPattern,
                              @JsonProperty("namePattern") final String namePattern,
                              @JsonProperty("properties") final List<PipelineFactoryProperty> properties,
                              @JsonProperty("runAsUser") final UserRef runAsUser,
                              @JsonProperty("filterSettings") final PipelineFactoryFilterSettings filterSettings) {
        super(TYPE, uuid, name, version, createTimeMs, updateTimeMs, createUser, updateUser);
        this.description = description;
        this.enabled = enabled;
        this.streamFilter = streamFilter;
        this.key = key == null
                ? PipelineFactoryKey.DEFAULT
                : List.copyOf(key);
        this.template = template;
        this.folderPattern = Objects.requireNonNullElse(folderPattern, DEFAULT_FOLDER_PATTERN);
        this.namePattern = Objects.requireNonNullElse(namePattern, DEFAULT_NAME_PATTERN);
        this.properties = properties == null
                ? Collections.emptyList()
                : List.copyOf(properties);
        this.runAsUser = runAsUser;
        this.filterSettings = Objects.requireNonNullElse(filterSettings, PipelineFactoryFilterSettings.DEFAULT);
    }

    /// @return A new builder for a [DocRef] to a document of this type.
    public static DocRef.TypedBuilder buildDocRef() {
        return DocRef.builder(TYPE);
    }

    /// @return What the factory is for, in a person's words.
    public String getDescription() {
        return description;
    }

    /// @return Whether the job runs this factory.
    public boolean isEnabled() {
        return enabled;
    }

    /// @return Which streams the factory looks at; null for every stream.
    public ExpressionOperator getStreamFilter() {
        return streamFilter;
    }

    /// @return The meta fields one combination of whose values is one pipeline (A106).
    public List<String> getKey() {
        return key;
    }

    /// @return The pipeline every made pipeline inherits (A105); null until one is chosen.
    public DocRef getTemplate() {
        return template;
    }

    /// @return Where a made pipeline goes, as a path from the explorer's root with the key substituted.
    public String getFolderPattern() {
        return folderPattern;
    }

    /// @return What a made pipeline is called, with the key substituted.
    public String getNamePattern() {
        return namePattern;
    }

    /// @return The values written onto the elements of every made pipeline (A113).
    public List<PipelineFactoryProperty> getProperties() {
        return properties;
    }

    /// @return Who the job makes documents as, and who the filters it makes run as (A123); null until
    /// one is chosen.
    public UserRef getRunAsUser() {
        return runAsUser;
    }

    /// @return The settings of every filter the factory makes (A108).
    public PipelineFactoryFilterSettings getFilterSettings() {
        return filterSettings;
    }

    /// What is wrong with this factory, each said so a person can put it right; empty when nothing is.
    /// The store refuses to save or import a factory with any (A119).
    ///
    /// @return The problems, in the order the factory's settings are laid out.
    public List<String> problems() {
        final List<String> problems = new ArrayList<>();
        checkKey(problems);
        if (template != null && !PipelineDoc.TYPE.equals(template.getType())) {
            problems.add("The template must be a pipeline, not a " + template.getType());
        }
        if (namePattern.isBlank()) {
            problems.add("A made pipeline needs a name, so the name pattern cannot be empty");
        }
        checkPattern("The folder pattern", folderPattern, problems);
        checkPattern("The name pattern", namePattern, problems);
        for (final PipelineFactoryProperty property : properties) {
            checkProperty(property, problems);
        }
        if (enabled) {
            if (template == null) {
                problems.add("An enabled factory needs a template to make pipelines from");
            }
            if (runAsUser == null) {
                problems.add("An enabled factory needs a run-as user to make pipelines as");
            }
        }
        return problems;
    }

    private void checkKey(final List<String> problems) {
        if (key.isEmpty()) {
            problems.add("The key needs at least one field");
        }
        final Set<String> seen = new HashSet<>();
        for (final String field : key) {
            if (!PipelineFactoryKey.FIELDS.contains(field)) {
                problems.add("'" + field + "' cannot be a key field: a key may name only "
                             + String.join(", ", PipelineFactoryKey.FIELDS));
            } else if (!seen.add(field)) {
                problems.add("'" + field + "' is in the key twice");
            }
        }
    }

    private void checkPattern(final String what, final String pattern, final List<String> problems) {
        final List<String> names;
        try {
            names = PipelineFactoryKey.placeholders(pattern);
        } catch (final IllegalArgumentException e) {
            problems.add(what + " " + e.getMessage());
            return;
        }
        for (final String name : names) {
            if (!namedByKey(name)) {
                problems.add(what + " names ${" + name + "}, which is not in the key");
            }
        }
    }

    private void checkProperty(final PipelineFactoryProperty property, final List<String> problems) {
        final String what = "The value for " + property;
        if (property.getElementId() == null || property.getElementId().isBlank()) {
            problems.add("A property value needs the id of the element it is written to");
        }
        if (property.getName() == null || property.getName().isBlank()) {
            problems.add("A property value needs the name of the property it is written to");
        }
        checkPattern(what, property.getValue(), problems);
    }

    private boolean namedByKey(final String name) {
        for (final String field : key) {
            if (PipelineFactoryKey.placeholder(field).equals(name)) {
                return true;
            }
        }
        return false;
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
        final PipelineFactoryDoc that = (PipelineFactoryDoc) o;
        return enabled == that.enabled
               && Objects.equals(description, that.description)
               && Objects.equals(streamFilter, that.streamFilter)
               && Objects.equals(key, that.key)
               && Objects.equals(template, that.template)
               && Objects.equals(folderPattern, that.folderPattern)
               && Objects.equals(namePattern, that.namePattern)
               && Objects.equals(properties, that.properties)
               && Objects.equals(runAsUser, that.runAsUser)
               && Objects.equals(filterSettings, that.filterSettings);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(),
                description,
                enabled,
                streamFilter,
                key,
                template,
                folderPattern,
                namePattern,
                properties,
                runAsUser,
                filterSettings);
    }

    @Override
    public String toString() {
        return "PipelineFactoryDoc{"
               + "name='" + getName() + '\''
               + ", enabled=" + enabled
               + ", streamFilter=" + streamFilter
               + ", key=" + key
               + ", template=" + template
               + ", folderPattern='" + folderPattern + '\''
               + ", namePattern='" + namePattern + '\''
               + ", properties=" + properties
               + ", runAsUser=" + runAsUser
               + ", filterSettings=" + filterSettings
               + '}';
    }

    /// @return A builder holding this document.
    public Builder copy() {
        return new Builder(this);
    }

    /// @return A builder for a new document, with the defaults.
    public static Builder builder() {
        return new Builder();
    }


    // --------------------------------------------------------------------------------


    /// Builds [PipelineFactoryDoc].
    public static final class Builder extends AbstractBuilder<PipelineFactoryDoc, Builder> {

        private String description;
        private boolean enabled;
        private ExpressionOperator streamFilter;
        private List<String> key = PipelineFactoryKey.DEFAULT;
        private DocRef template;
        private String folderPattern = DEFAULT_FOLDER_PATTERN;
        private String namePattern = DEFAULT_NAME_PATTERN;
        private List<PipelineFactoryProperty> properties = Collections.emptyList();
        private UserRef runAsUser;
        private PipelineFactoryFilterSettings filterSettings = PipelineFactoryFilterSettings.DEFAULT;

        private Builder() {
        }

        private Builder(final PipelineFactoryDoc doc) {
            super(doc);
            this.description = doc.description;
            this.enabled = doc.enabled;
            this.streamFilter = doc.streamFilter;
            this.key = doc.key;
            this.template = doc.template;
            this.folderPattern = doc.folderPattern;
            this.namePattern = doc.namePattern;
            this.properties = doc.properties;
            this.runAsUser = doc.runAsUser;
            this.filterSettings = doc.filterSettings;
        }

        /// @param description What the factory is for.
        /// @return This builder.
        public Builder description(final String description) {
            this.description = description;
            return self();
        }

        /// @param enabled Whether the job runs this factory.
        /// @return This builder.
        public Builder enabled(final boolean enabled) {
            this.enabled = enabled;
            return self();
        }

        /// @param streamFilter Which streams the factory looks at; null for every stream.
        /// @return This builder.
        public Builder streamFilter(final ExpressionOperator streamFilter) {
            this.streamFilter = streamFilter;
            return self();
        }

        /// @param key The meta fields that make one pipeline.
        /// @return This builder.
        public Builder key(final List<String> key) {
            this.key = key;
            return self();
        }

        /// @param template The pipeline every made pipeline inherits.
        /// @return This builder.
        public Builder template(final DocRef template) {
            this.template = template;
            return self();
        }

        /// @param folderPattern Where a made pipeline goes.
        /// @return This builder.
        public Builder folderPattern(final String folderPattern) {
            this.folderPattern = folderPattern;
            return self();
        }

        /// @param namePattern What a made pipeline is called.
        /// @return This builder.
        public Builder namePattern(final String namePattern) {
            this.namePattern = namePattern;
            return self();
        }

        /// @param properties The values written onto the elements of every made pipeline.
        /// @return This builder.
        public Builder properties(final List<PipelineFactoryProperty> properties) {
            this.properties = properties;
            return self();
        }

        /// @param runAsUser Who the job makes documents as and the made filters run as.
        /// @return This builder.
        public Builder runAsUser(final UserRef runAsUser) {
            this.runAsUser = runAsUser;
            return self();
        }

        /// @param filterSettings The settings of every filter the factory makes.
        /// @return This builder.
        public Builder filterSettings(final PipelineFactoryFilterSettings filterSettings) {
            this.filterSettings = filterSettings;
            return self();
        }

        @Override
        protected Builder self() {
            return this;
        }

        /// @return The document.
        public PipelineFactoryDoc build() {
            return new PipelineFactoryDoc(
                    uuid,
                    name,
                    version,
                    createTimeMs,
                    updateTimeMs,
                    createUser,
                    updateUser,
                    description,
                    enabled,
                    streamFilter,
                    key,
                    template,
                    folderPattern,
                    namePattern,
                    properties,
                    runAsUser,
                    filterSettings);
        }
    }
}
