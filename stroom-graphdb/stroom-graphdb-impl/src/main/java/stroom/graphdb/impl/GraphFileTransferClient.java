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

package stroom.graphdb.impl;

import stroom.planb.impl.data.FileDescriptor;

import java.nio.file.Path;

/**
 * Sends a completed graph fragment to the nodes that hold graph data.
 *
 * <p>Separate from Plan B's {@code FileTransferClient} on purpose. The two features have distinct node lists and
 * distinct staging directories, and a fragment delivered to the wrong feature's staging area would be deleted as
 * unresolvable rather than rejected - so the routing is kept apart at the type level rather than by convention.</p>
 */
public interface GraphFileTransferClient {

    /**
     * Delivers a fragment to every node that holds graph data.
     *
     * <p><b>Preconditions:</b> {@code path} is a complete fragment zip whose hash matches {@code fileDescriptor}.
     * <b>Postconditions:</b> the fragment has been delivered to every target node, or the method throws. The file
     * at {@code path} may have been moved when the local node is the only target, so callers must tolerate it no
     * longer existing; otherwise it is left for the caller to delete.
     * <b>Null status:</b> neither {@code fileDescriptor} nor {@code path} is nullable.
     *
     * @param fileDescriptor   identifies the fragment and carries its hash.
     * @param path             the fragment zip to send.
     * @param synchroniseMerge whether to wait for each target to merge the fragment before returning.
     */
    void storePart(FileDescriptor fileDescriptor,
                   Path path,
                   boolean synchroniseMerge);
}
