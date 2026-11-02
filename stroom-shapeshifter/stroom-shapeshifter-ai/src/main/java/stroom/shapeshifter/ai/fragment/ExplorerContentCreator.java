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

package stroom.shapeshifter.ai.fragment;

import stroom.docref.DocRef;
import stroom.explorer.api.ExplorerNodeService;
import stroom.explorer.api.ExplorerService;
import stroom.explorer.shared.ExplorerConstants;
import stroom.explorer.shared.ExplorerNode;
import stroom.explorer.shared.PermissionInheritance;
import stroom.util.shared.DocPath;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Creates learned content through the explorer, so it has a node in the tree and inherits the folder's
 * permissions. The folder path is made sure of on every create; the walk is cheap when it exists.
 */
@Singleton
public class ExplorerContentCreator implements ContentCreator {

    /// The folders this node has made sure of. A stage makes sure of a learner's folder each time it would
    /// open a session, which for a learner nothing serves is each time one of its streams is tried; a
    /// folder found once is not looked for again. Creating a document still makes sure of its folder, so a
    /// folder a person deletes afterwards is made again when it is needed.
    private final Set<DocPath> ensured = ConcurrentHashMap.newKeySet();
    private final ExplorerService explorerService;
    private final ExplorerNodeService explorerNodeService;

    @Inject
    ExplorerContentCreator(final ExplorerService explorerService,
                           final ExplorerNodeService explorerNodeService) {
        this.explorerService = explorerService;
        this.explorerNodeService = explorerNodeService;
    }

    @Override
    public DocRef create(final DocPath folder, final String type, final String name) {
        return explorerService.create(type, name, folder(folder), PermissionInheritance.DESTINATION).getDocRef();
    }

    @Override
    public void ensure(final DocPath folder) {
        if (!ensured.contains(folder)) {
            folder(folder);
            ensured.add(folder);
        }
    }

    /// Made sure of, as a create would make sure of it: a name is asked for only to create under it.
    @Override
    public Set<String> names(final DocPath folder) {
        return explorerNodeService.getChildren(folder(folder).getDocRef()).stream()
                .map(ExplorerNode::getName)
                .collect(Collectors.toSet());
    }

    /// The folder, found or made a part at a time from the top of the tree. Not the explorer's own walk,
    /// which fails for good once a folder has a twin of the same name: a folder has no unique name, and
    /// the tasks trying a new learner's streams side by side, on this node or another, each make sure of
    /// its folder before a session is opened, so two can each make it before either sees the other's. A
    /// twin is harmless here, since the walk takes the one with the lowest uuid, as every node would; and
    /// on one node the walk is made one at a time, so that twins are not made in the first place.
    private synchronized ExplorerNode folder(final DocPath path) {
        ExplorerNode parent = explorerNodeService.getRoot();
        // The empty path's parts are one null leaf: the top of the tree has none to walk.
        final List<String> parts = path.isBlank()
                ? List.of()
                : path.getAllParts();
        for (final String part : parts) {
            final ExplorerNode found = explorerNodeService
                    .getNodesByNameAndType(parent, part, ExplorerConstants.FOLDER_TYPE)
                    .stream()
                    .min(Comparator.comparing(ExplorerNode::getUuid))
                    .orElse(null);
            // A part not there is made as the explorer makes it, one part below the folder found.
            parent = found != null
                    ? found
                    : explorerService.ensureFolderPath(DocPath.fromParts(false, part), parent,
                            PermissionInheritance.DESTINATION);
        }
        return parent;
    }
}
