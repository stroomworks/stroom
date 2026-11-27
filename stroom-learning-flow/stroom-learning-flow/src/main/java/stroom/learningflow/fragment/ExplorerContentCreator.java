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

package stroom.learningflow.fragment;

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

/// The [ContentCreator] used on a node. It creates learned content through the explorer, so that each
/// document has a node in the tree and inherits the folder's permissions. The folder path is checked, and
/// created if missing, on every create; the walk is cheap when the folder exists.
@Singleton
public class ExplorerContentCreator implements ContentCreator {

    /// The folders this node has already ensured exist. A stage ensures a learner's folder exists each time
    /// it would open a session, which for a learner that nothing serves is every time one of its streams
    /// is tried, so a folder found once is not looked for again. Creating a document still ensures its
    /// folder exists, so a folder that a person deletes later is created again when it is needed.
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
    public void delete(final DocRef docRef) {
        explorerNodeService.getNode(docRef)
                .ifPresent(node -> explorerService.delete(List.of(node)));
    }

    @Override
    public void ensure(final DocPath folder) {
        if (!ensured.contains(folder)) {
            folder(folder);
            ensured.add(folder);
        }
    }

    /// Creates the folder if it is missing, as [#create] would, since names are only asked for in order to
    /// create something in the folder.
    @Override
    public Set<String> names(final DocPath folder) {
        return explorerNodeService.getChildren(folder(folder).getDocRef()).stream()
                .map(ExplorerNode::getName)
                .collect(Collectors.toSet());
    }

    /// Finds the folder, creating any missing part, one path part at a time from the top of the tree.
    ///
    /// This does not use the explorer's own path walk, because that fails permanently once a folder has a
    /// duplicate with the same name. Folder names are not unique, and the tasks processing a new learner's
    /// streams in parallel, on this node or another, each ensure its folder exists before a session is
    /// opened, so two can each create it before either sees the other's. A duplicate is harmless here,
    /// because this walk always takes the one with the lowest UUID, as every node would. On one node the
    /// method is synchronised, so that duplicates are not created in the first place.
    private synchronized ExplorerNode folder(final DocPath path) {
        ExplorerNode parent = explorerNodeService.getRoot();
        // An empty path's parts are a single null leaf, so it is treated as having no parts to walk.
        final List<String> parts = path.isBlank()
                ? List.of()
                : path.getAllParts();
        for (final String part : parts) {
            final ExplorerNode found = explorerNodeService
                    .getNodesByNameAndType(parent, part, ExplorerConstants.FOLDER_TYPE)
                    .stream()
                    .min(Comparator.comparing(ExplorerNode::getUuid))
                    .orElse(null);
            // A missing part is created by the explorer, one level below the folder found so far.
            parent = found != null
                    ? found
                    : explorerService.ensureFolderPath(DocPath.fromParts(false, part), parent,
                            PermissionInheritance.DESTINATION);
        }
        return parent;
    }
}
