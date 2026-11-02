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

package stroom.pipelinefactory.impl;

import stroom.config.common.HasDbConfig;
import stroom.util.shared.AbstractConfig;
import stroom.util.shared.IsStroomConfig;
import stroom.util.time.StroomDuration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.Min;

/// How the Pipeline Factory job works (design 06 §3.2).
public class PipelineFactoryConfig extends AbstractConfig implements IsStroomConfig, HasDbConfig {

    private final PipelineFactoryDbConfig dbConfig;
    private final int scanBatchSize;
    private final int maxScanBatchesPerRun;
    private final StroomDuration retryFailedAfter;

    /// The defaults.
    public PipelineFactoryConfig() {
        dbConfig = new PipelineFactoryDbConfig();
        scanBatchSize = 10_000;
        maxScanBatchesPerRun = 100;
        retryFailedAfter = StroomDuration.ofMinutes(10);
    }

    /// @param dbConfig             The database.
    /// @param scanBatchSize        How many streams one read takes.
    /// @param maxScanBatchesPerRun How many reads one run of a factory takes at most.
    /// @param retryFailedAfter     How long a combination that failed waits before it is tried again.
    @SuppressWarnings("unused")
    @JsonCreator
    public PipelineFactoryConfig(@JsonProperty("db") final PipelineFactoryDbConfig dbConfig,
                                 @JsonProperty("scanBatchSize") final int scanBatchSize,
                                 @JsonProperty("maxScanBatchesPerRun") final int maxScanBatchesPerRun,
                                 @JsonProperty("retryFailedAfter") final StroomDuration retryFailedAfter) {
        this.dbConfig = dbConfig;
        this.scanBatchSize = scanBatchSize;
        this.maxScanBatchesPerRun = maxScanBatchesPerRun;
        this.retryFailedAfter = retryFailedAfter;
    }

    @Override
    @JsonProperty("db")
    @JsonPropertyDescription("The database holding what each Pipeline Factory has met and made, and how far "
                             + "it has looked through the streams.")
    public PipelineFactoryDbConfig getDbConfig() {
        return dbConfig;
    }

    /// @return How many streams one read of the stream table takes.
    @Min(1)
    @JsonProperty("scanBatchSize")
    @JsonPropertyDescription("How many streams the Pipeline Factory job reads at a time when it looks for "
                             + "combinations a factory has not met. Only each stream's key values are kept, "
                             + "so a batch is cheap to hold.")
    public int getScanBatchSize() {
        return scanBatchSize;
    }

    /// @return How many reads one run of one factory takes at most.
    @Min(1)
    @JsonProperty("maxScanBatchesPerRun")
    @JsonPropertyDescription("The most batches one run of the Pipeline Factory job reads for one factory. A "
                             + "factory new to a large store reads its history over several runs rather than "
                             + "holding the job for all of it.")
    public int getMaxScanBatchesPerRun() {
        return maxScanBatchesPerRun;
    }

    /// @return How long a combination that failed waits before it is tried again.
    @JsonProperty("retryFailedAfter")
    @JsonPropertyDescription("How long a combination a Pipeline Factory could not make a pipeline for waits "
                             + "before it is tried again. A failure such as a missing permission is put right "
                             + "by a person, and trying every minute until then would make and remove the "
                             + "same pipeline over and over.")
    public StroomDuration getRetryFailedAfter() {
        return retryFailedAfter;
    }

    @Override
    public String toString() {
        return "PipelineFactoryConfig{"
               + "dbConfig=" + dbConfig
               + ", scanBatchSize=" + scanBatchSize
               + ", maxScanBatchesPerRun=" + maxScanBatchesPerRun
               + ", retryFailedAfter=" + retryFailedAfter
               + '}';
    }
}
