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

package stroom.learningflow.element;

import stroom.learningflow.stage.Learner;
import stroom.learningflow.stage.StreamHeld;
import stroom.meta.api.MetaService;
import stroom.meta.shared.Meta;
import stroom.pipeline.PipelineStore;
import stroom.pipeline.state.MetaDataHolder;
import stroom.pipeline.state.MetaHolder;
import stroom.pipeline.task.StreamMetaDataProvider;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

/// Puts the stream being judged by the background worker into the pipeline's state, the way a processor
/// task does for the stream it is processing (`AbstractProcessorTaskExecutor`). This makes
/// `stroom:format-date` take a missing year from the stream being judged rather than from the clock, and
/// makes `stroom:meta` return that stream's metadata.
///
/// Both holders are pipeline-scoped, so this must be called inside the scope the attempt runs in, and
/// for **every** attempt: the worker runs a batch of attempts in one scope, and an attempt that did not
/// replace the previous one's meta would silently use the previous stream's year.
public class PipelineStreamHeld implements StreamHeld {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(PipelineStreamHeld.class);

    private final MetaService metaService;
    private final PipelineZones zones;
    private final Provider<MetaHolder> metaHolderProvider;
    private final Provider<MetaDataHolder> metaDataHolderProvider;
    private final PipelineStore pipelineStore;

    @Inject
    public PipelineStreamHeld(final MetaService metaService,
                              final PipelineZones zones,
                              final Provider<MetaHolder> metaHolderProvider,
                              final Provider<MetaDataHolder> metaDataHolderProvider,
                              final PipelineStore pipelineStore) {
        this.metaService = metaService;
        this.zones = zones;
        this.metaHolderProvider = metaHolderProvider;
        this.metaDataHolderProvider = metaDataHolderProvider;
        this.pipelineStore = pipelineStore;
    }

    @Override
    public void hold(final long metaId, final Learner learner) {
        try {
            final Meta meta = metaService.getMeta(metaId);
            if (meta == null) {
                // The stream has been deleted since the attempt was opened, so the attempt will be
                // abandoned anyway; leaving a previous stream's meta in place would be worse than none.
                clear();
                return;
            }
            final MetaHolder metaHolder = metaHolderProvider.get();
            metaHolder.setMeta(meta);
            final StreamMetaDataProvider provider = new StreamMetaDataProvider(metaHolder, pipelineStore);
            // The zone goes in the metadata while learning as well as while serving, otherwise a date
            // judged in one zone and served in another would change meaning on promotion.
            final ResolvedZone zone = zones.of(learner, provider.get(ResolvedZone.HEADER));
            metaDataHolderProvider.get().setMetaDataProvider(new ZonedMetaData(provider, zone));
        } catch (final RuntimeException e) {
            // A run that cannot be told which stream it is judging must not quietly use the previous one:
            // it is better for a date without a year to fail than to be given a stale year.
            LOGGER.error(() -> LogUtil.message(
                    "The stream {} could not be held for judging, so a date with no year has nothing to "
                    + "take its year from: {}", metaId, e.getMessage()), e);
            clear();
        }
    }

    private void clear() {
        metaHolderProvider.get().setMeta(null);
    }
}
