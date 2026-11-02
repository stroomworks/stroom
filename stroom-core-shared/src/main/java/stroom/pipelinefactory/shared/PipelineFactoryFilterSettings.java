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

import stroom.processor.shared.ProcessorFilter;
import stroom.util.shared.time.SimpleDuration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Objects;

/// The processor filter settings a Pipeline Factory writes to every filter it makes (design 06 §3.1,
/// A108): every setting a filter has that is not about one filter's identity. Which pipeline it runs and
/// what it matches are the factory's to work out per combination; who it runs as is the factory's
/// run-as user (A123); and a made filter never reprocesses. Each field means what the same field of
/// [ProcessorFilter] means.
@JsonPropertyOrder({
        "priority",
        "autoPriority",
        "maxProcessingTasks",
        "profileName",
        "enabled",
        "export",
        "minMetaCreateTimeMs",
        "maxMetaCreateTimeMs",
        "maxTaskCreationDelay"})
@JsonInclude(Include.NON_NULL)
public final class PipelineFactoryFilterSettings {

    /// What a factory writes when nothing has been set: a filter as the processing screen makes one,
    /// enabled, since the point of a factory is that what it makes processes.
    public static final PipelineFactoryFilterSettings DEFAULT = builder().build();

    @JsonProperty
    private final int priority;
    @JsonProperty
    private final boolean autoPriority;
    @JsonProperty
    private final int maxProcessingTasks;
    @JsonProperty
    private final String profileName;
    @JsonProperty
    private final boolean enabled;
    @JsonProperty
    private final boolean export;
    @JsonProperty
    private final Long minMetaCreateTimeMs;
    @JsonProperty
    private final Long maxMetaCreateTimeMs;
    @JsonProperty
    private final SimpleDuration maxTaskCreationDelay;

    /// A setting left null takes the default a filter made in the processing screen has. The arguments
    /// are the settings, each described on its getter.
    @JsonCreator
    public PipelineFactoryFilterSettings(@JsonProperty("priority") final Integer priority,
                                         @JsonProperty("autoPriority") final boolean autoPriority,
                                         @JsonProperty("maxProcessingTasks") final Integer maxProcessingTasks,
                                         @JsonProperty("profileName") final String profileName,
                                         @JsonProperty("enabled") final Boolean enabled,
                                         @JsonProperty("export") final boolean export,
                                         @JsonProperty("minMetaCreateTimeMs") final Long minMetaCreateTimeMs,
                                         @JsonProperty("maxMetaCreateTimeMs") final Long maxMetaCreateTimeMs,
                                         @JsonProperty("maxTaskCreationDelay")
                                         final SimpleDuration maxTaskCreationDelay) {
        this.priority = Objects.requireNonNullElse(priority, ProcessorFilter.DEFAULT_PRIORITY);
        this.autoPriority = autoPriority;
        this.maxProcessingTasks = Objects.requireNonNullElse(
                maxProcessingTasks, ProcessorFilter.DEFAULT_MAX_PROCESSING_TASKS);
        this.profileName = profileName;
        this.enabled = Objects.requireNonNullElse(enabled, Boolean.TRUE);
        this.export = export;
        this.minMetaCreateTimeMs = minMetaCreateTimeMs;
        this.maxMetaCreateTimeMs = maxMetaCreateTimeMs;
        this.maxTaskCreationDelay = maxTaskCreationDelay;
    }

    /// @return The priority a made filter has, as [ProcessorFilter#getPriority].
    public int getPriority() {
        return priority;
    }

    /// @return Whether a made filter's priority is worked out from its pipeline's other filters.
    public boolean isAutoPriority() {
        return autoPriority;
    }

    /// @return The most tasks a made filter may have processing at once; 0 for no limit.
    public int getMaxProcessingTasks() {
        return maxProcessingTasks;
    }

    /// @return The processor profile a made filter takes, which carries its node group and time periods;
    /// null for none.
    public String getProfileName() {
        return profileName;
    }

    /// @return Whether a made filter is enabled.
    public boolean isEnabled() {
        return enabled;
    }

    /// @return Whether a made filter is exported with its pipeline.
    public boolean isExport() {
        return export;
    }

    /// @return The earliest create time of a stream a made filter takes; null for no bound.
    public Long getMinMetaCreateTimeMs() {
        return minMetaCreateTimeMs;
    }

    /// @return The latest create time of a stream a made filter takes; null for no bound.
    public Long getMaxMetaCreateTimeMs() {
        return maxMetaCreateTimeMs;
    }

    /// @return How long a made filter may wait before creating tasks for what it has; null for none.
    public SimpleDuration getMaxTaskCreationDelay() {
        return maxTaskCreationDelay;
    }

    /// @return A builder holding these settings.
    public Builder copy() {
        return new Builder(this);
    }

    /// @return A builder holding the default settings.
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final PipelineFactoryFilterSettings that = (PipelineFactoryFilterSettings) o;
        return priority == that.priority
               && autoPriority == that.autoPriority
               && maxProcessingTasks == that.maxProcessingTasks
               && enabled == that.enabled
               && export == that.export
               && Objects.equals(profileName, that.profileName)
               && Objects.equals(minMetaCreateTimeMs, that.minMetaCreateTimeMs)
               && Objects.equals(maxMetaCreateTimeMs, that.maxMetaCreateTimeMs)
               && Objects.equals(maxTaskCreationDelay, that.maxTaskCreationDelay);
    }

    @Override
    public int hashCode() {
        return Objects.hash(priority,
                autoPriority,
                maxProcessingTasks,
                profileName,
                enabled,
                export,
                minMetaCreateTimeMs,
                maxMetaCreateTimeMs,
                maxTaskCreationDelay);
    }

    @Override
    public String toString() {
        return "PipelineFactoryFilterSettings{"
               + "priority=" + priority
               + ", autoPriority=" + autoPriority
               + ", maxProcessingTasks=" + maxProcessingTasks
               + ", profileName='" + profileName + '\''
               + ", enabled=" + enabled
               + ", export=" + export
               + ", minMetaCreateTimeMs=" + minMetaCreateTimeMs
               + ", maxMetaCreateTimeMs=" + maxMetaCreateTimeMs
               + ", maxTaskCreationDelay=" + maxTaskCreationDelay
               + '}';
    }


    // --------------------------------------------------------------------------------


    /// Builds [PipelineFactoryFilterSettings].
    public static final class Builder {

        private int priority = ProcessorFilter.DEFAULT_PRIORITY;
        private boolean autoPriority;
        private int maxProcessingTasks = ProcessorFilter.DEFAULT_MAX_PROCESSING_TASKS;
        private String profileName;
        private boolean enabled = true;
        private boolean export;
        private Long minMetaCreateTimeMs;
        private Long maxMetaCreateTimeMs;
        private SimpleDuration maxTaskCreationDelay;

        private Builder() {
        }

        private Builder(final PipelineFactoryFilterSettings settings) {
            this.priority = settings.priority;
            this.autoPriority = settings.autoPriority;
            this.maxProcessingTasks = settings.maxProcessingTasks;
            this.profileName = settings.profileName;
            this.enabled = settings.enabled;
            this.export = settings.export;
            this.minMetaCreateTimeMs = settings.minMetaCreateTimeMs;
            this.maxMetaCreateTimeMs = settings.maxMetaCreateTimeMs;
            this.maxTaskCreationDelay = settings.maxTaskCreationDelay;
        }

        /// @param priority A made filter's priority.
        /// @return This builder.
        public Builder priority(final int priority) {
            this.priority = priority;
            return this;
        }

        /// @param autoPriority Whether a made filter's priority is worked out.
        /// @return This builder.
        public Builder autoPriority(final boolean autoPriority) {
            this.autoPriority = autoPriority;
            return this;
        }

        /// @param maxProcessingTasks The most tasks a made filter may have processing; 0 for no limit.
        /// @return This builder.
        public Builder maxProcessingTasks(final int maxProcessingTasks) {
            this.maxProcessingTasks = maxProcessingTasks;
            return this;
        }

        /// @param profileName The processor profile a made filter takes; null for none.
        /// @return This builder.
        public Builder profileName(final String profileName) {
            this.profileName = profileName;
            return this;
        }

        /// @param enabled Whether a made filter is enabled.
        /// @return This builder.
        public Builder enabled(final boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        /// @param export Whether a made filter is exported with its pipeline.
        /// @return This builder.
        public Builder export(final boolean export) {
            this.export = export;
            return this;
        }

        /// @param minMetaCreateTimeMs The earliest create time a made filter takes; null for no bound.
        /// @return This builder.
        public Builder minMetaCreateTimeMs(final Long minMetaCreateTimeMs) {
            this.minMetaCreateTimeMs = minMetaCreateTimeMs;
            return this;
        }

        /// @param maxMetaCreateTimeMs The latest create time a made filter takes; null for no bound.
        /// @return This builder.
        public Builder maxMetaCreateTimeMs(final Long maxMetaCreateTimeMs) {
            this.maxMetaCreateTimeMs = maxMetaCreateTimeMs;
            return this;
        }

        /// @param maxTaskCreationDelay How long a made filter may wait to create tasks; null for none.
        /// @return This builder.
        public Builder maxTaskCreationDelay(final SimpleDuration maxTaskCreationDelay) {
            this.maxTaskCreationDelay = maxTaskCreationDelay;
            return this;
        }

        /// @return The settings.
        public PipelineFactoryFilterSettings build() {
            return new PipelineFactoryFilterSettings(priority,
                    autoPriority,
                    maxProcessingTasks,
                    profileName,
                    enabled,
                    export,
                    minMetaCreateTimeMs,
                    maxMetaCreateTimeMs,
                    maxTaskCreationDelay);
        }
    }
}
