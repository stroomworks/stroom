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

import stroom.docref.DocRef;
import stroom.pipelinefactory.impl.CombinationScanner.Scan;
import stroom.pipelinefactory.shared.PipelineFactoryDoc;
import stroom.security.api.SecurityContext;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

/// One run of every enabled Pipeline Factory (design 06 §3.2): for each, try again what it could not
/// make before, then read the streams that have arrived since it last looked and make a pipeline for
/// each combination it meets for the first time.
///
/// A combination is recorded as met before its pipeline is made, and the factory's place in the stream
/// table moves on only after the batch's combinations are recorded. So a run that stops part-way loses
/// nothing: a batch read again finds its combinations already met, and one stopped while its pipeline was
/// being made is tried again with the failures.
public class PipelineFactoryRunner {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(PipelineFactoryRunner.class);

    private final PipelineFactoryStore store;
    private final CombinationScanner scanner;
    private final PipelineMaker maker;
    private final Combinations combinations;
    private final Provider<PipelineFactoryConfig> configProvider;
    private final SecurityContext securityContext;

    @Inject
    PipelineFactoryRunner(final PipelineFactoryStore store,
                          final CombinationScanner scanner,
                          final PipelineMaker maker,
                          final Combinations combinations,
                          final Provider<PipelineFactoryConfig> configProvider,
                          final SecurityContext securityContext) {
        this.store = store;
        this.scanner = scanner;
        this.maker = maker;
        this.combinations = combinations;
        this.configProvider = configProvider;
        this.securityContext = securityContext;
    }

    /// Run every enabled factory once. A factory that fails is logged and the others still run.
    public void exec() {
        securityContext.asProcessingUser(() -> {
            for (final DocRef docRef : store.listDocuments()) {
                try {
                    final PipelineFactoryDoc factory = store.readDocument(docRef);
                    if (factory != null && factory.isEnabled() && factory.problems().isEmpty()) {
                        run(factory);
                    }
                } catch (final RuntimeException e) {
                    LOGGER.error(() -> LogUtil.message("Pipeline Factory {} could not run: {}",
                            docRef.getName(), e.getMessage()), e);
                }
            }
        });
    }

    /// Run one factory.
    ///
    /// @param factory An enabled factory without problems.
    public void run(final PipelineFactoryDoc factory) {
        final PipelineFactoryConfig config = configProvider.get();
        final long retryBefore = System.currentTimeMillis() - config.getRetryFailedAfter().toMillis();
        for (final Combination combination : combinations.unmade(factory.getUuid(), retryBefore)) {
            make(factory, combination);
        }
        long lastMetaId = combinations.lastMetaId(factory.getUuid());
        for (int batch = 0; batch < config.getMaxScanBatchesPerRun(); batch++) {
            final Scan scan = scanner.scan(factory, lastMetaId, config.getScanBatchSize());
            for (final Combination combination : scan.found()) {
                if (combinations.met(factory.getUuid(), combination)) {
                    make(factory, combination);
                }
            }
            if (scan.lastId() > lastMetaId) {
                lastMetaId = scan.lastId();
                combinations.lastMetaId(factory.getUuid(), lastMetaId);
            }
            if (!scan.full()) {
                break;
            }
        }
    }

    private void make(final PipelineFactoryDoc factory, final Combination combination) {
        try {
            combinations.made(factory.getUuid(), combination, maker.make(factory, combination));
        } catch (final PipelineMakerException e) {
            LOGGER.warn(e::getMessage);
            combinations.failed(factory.getUuid(), combination, e.getMessage());
        }
    }
}
