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

package stroom.planb.impl.data;

import stroom.planb.impl.dao.StatePaths;
import stroom.planb.shared.PlanBDoc;
import stroom.security.api.SecurityContext;
import stroom.task.api.ExecutorProvider;
import stroom.task.api.TaskContextFactory;
import stroom.util.shared.NullSafe;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Merges received Plan B fragments into their shards.
 *
 * <p>The receive-stage-unzip-merge lifecycle lives in {@link PartMergeProcessor}, which Graph DB uses too; this
 * class supplies the Plan B specifics - the {@link StatePaths} directories, the shard lookup, and shard
 * maintenance, which has no analogue in the shared engine.</p>
 */
@Singleton
public class MergeProcessor {

    public static final String MERGE_TASK_NAME = "Plan B Merge Processor";
    public static final String MAINTAIN_TASK_NAME = "Plan B Maintenance Processor";

    private static final String FEATURE_NAME = "Plan B";

    private final PartMergeProcessor partMergeProcessor;
    private final SecurityContext securityContext;
    private final TaskContextFactory taskContextFactory;
    private final ShardManager shardManager;

    @Inject
    public MergeProcessor(final StatePaths statePaths,
                          final SecurityContext securityContext,
                          final TaskContextFactory taskContextFactory,
                          final ShardManager shardManager,
                          final ExecutorProvider executorProvider) {
        this.securityContext = securityContext;
        this.taskContextFactory = taskContextFactory;
        this.shardManager = shardManager;
        this.partMergeProcessor = new PartMergeProcessor(
                FEATURE_NAME,
                MERGE_TASK_NAME,
                statePaths.getStagingDir(),
                statePaths.getMergingDir(),
                statePaths.getUnzipDir(),
                securityContext,
                taskContextFactory,
                executorProvider.get(),
                this::resolveShard,
                () -> {
                    // Plan B has always relied on the ERROR log alone for merge failures.
                });
    }

    private MergeTarget resolveShard(final String docUuid) {
        final Shard shard = shardManager.getShardForDocUuid(docUuid);
        return new MergeTarget() {
            @Override
            public String getDisplayName() {
                return NullSafe.get(shard, Shard::getDoc, PlanBDoc::getName);
            }

            @Override
            public void merge(final Path sourceDir) {
                shard.merge(sourceDir);
            }
        };
    }

    public void add(final FileDescriptor fileDescriptor,
                    final Path file,
                    final boolean synchroniseMerge) throws IOException {
        partMergeProcessor.add(fileDescriptor, file, synchroniseMerge);
    }

    public void merge() {
        partMergeProcessor.merge();
    }

    public void maintainShards() {
        securityContext.asProcessingUser(() ->
                taskContextFactory.context(MAINTAIN_TASK_NAME, shardManager::condenseAll).run());
    }

    public void mergeCurrent() {
        partMergeProcessor.mergeCurrent();
    }

    public void merge(final long storeId) {
        partMergeProcessor.merge(storeId);
    }
}
