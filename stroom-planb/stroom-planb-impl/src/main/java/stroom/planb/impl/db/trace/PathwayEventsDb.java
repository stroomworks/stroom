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

package stroom.planb.impl.db.trace;

import stroom.bytebuffer.impl6.ByteBuffers;
import stroom.planb.impl.db.HashClashCommitRunnable;
import stroom.planb.impl.db.LmdbWriter;
import stroom.planb.impl.db.PlanBEnv;
import stroom.planb.impl.db.trace.PathwaysDb.SimpleDb;
import stroom.planb.shared.StateSettings;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.logging.LogUtil;

import org.lmdbjava.DbiFlags;
import org.lmdbjava.PutFlags;

import java.nio.file.Path;

/**
 * A single-DBI LMDB store holding the pathway events for one shard of a pathways doc.
 * Events are kept in their own environment (one per trace shard) so that the append-heavy
 * event log grows independently of the small pathway model environment, and so events can
 * be sharded the same way as the traces that produce them.
 *
 * <p>The environment lives on the shared filesystem (falling back to node-local storage when
 * no shared path is configured) and is written by a single node at a time under the existing
 * {@code pathways-write-*} cluster lock, mirroring the design's single-writer guarantee.
 */
public class PathwayEventsDb {

    protected static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(PathwayEventsDb.class);

    private final PlanBEnv env;
    private final SimpleDb pathwayEvents;

    private PathwayEventsDb(final PlanBEnv env, final boolean readOnly) {
        this.env = env;
        // A read-only environment cannot create DBIs, so only pass MDB_CREATE when writable.
        final DbiFlags[] dbiFlags = readOnly
                ? new DbiFlags[]{}
                : new DbiFlags[]{DbiFlags.MDB_CREATE};
        pathwayEvents = new SimpleDb(
                env,
                env.openDbi("pathway-events", dbiFlags),
                new PutFlags[]{});
    }

    public SimpleDb getPathwayEvents() {
        return pathwayEvents;
    }

    public LmdbWriter createWriter() {
        return env.createWriter();
    }

    public static PathwayEventsDb create(final Path path,
                                         final boolean readOnly) {
        final StateSettings settings = new StateSettings.Builder().build();
        final HashClashCommitRunnable hashClashCommitRunnable = new HashClashCommitRunnable();
        final PlanBEnv env = new PlanBEnv(path,
                settings.getMaxStoreSize(),
                5,
                readOnly,
                hashClashCommitRunnable);
        try {
            return new PathwayEventsDb(env, readOnly);
        } catch (final RuntimeException e) {
            // Close the env if we get any exceptions to prevent them staying open.
            try {
                env.close();
            } catch (final Exception e2) {
                LOGGER.debug(LogUtil.message("message={}", e.getMessage()), e);
            }
            throw e;
        }
    }

    public void close() {
        try {
            env.close();
        } catch (final Exception e) {
            LOGGER.error("Error closing PathwayEventsDb env", e);
        }
    }
}
