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

import java.nio.file.Path;

/**
 * The authoritative store a {@link PartMergeProcessor} merges one received fragment into.
 *
 * <p>This is the whole of what the merge engine needs to know about its target, which is what lets one engine
 * serve both Plan B shards and graph stores. Everything else - how the store is opened, cached, locked or
 * evicted - stays behind the feature's own {@link MergeTargetResolver}.</p>
 */
public interface MergeTarget {

    /**
     * The target's human-readable name, used in task and log messages.
     *
     * <p><b>Postconditions:</b> may be null if the name cannot be determined; callers must tolerate that.
     * <b>Null status:</b> the return value is nullable.
     *
     * @return the display name, or null if unknown.
     */
    String getDisplayName();

    /**
     * Merges a received fragment into this target.
     *
     * <p><b>Preconditions:</b> {@code sourceDir} holds one complete fragment of this target's own format.
     * <b>Postconditions:</b> every mutation in the fragment is present in this target, or the method throws and
     * the target is unchanged as far as its own transactional guarantees allow.
     * <b>Null status:</b> {@code sourceDir} is not nullable.
     *
     * @param sourceDir the fragment to merge.
     */
    void merge(Path sourceDir);
}
