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

package stroom.shapeshifter.ai.fragment;

import stroom.docref.DocRef;
import stroom.explorer.api.ExplorerNodeService;
import stroom.explorer.api.ExplorerService;
import stroom.explorer.shared.ExplorerConstants;
import stroom.explorer.shared.ExplorerNode;
import stroom.explorer.shared.PermissionInheritance;
import stroom.pipeline.shared.PipelineDoc;
import stroom.util.shared.DocPath;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/// Learned content made through the explorer. A stage makes sure of a learner's folder each time it would
/// open a session, so a folder found once is not looked for again; creating a document still makes sure
/// of its folder, so one a person deletes afterwards is made again. A folder that has a twin of the same
/// name, which tasks making it side by side can leave, is not a folder that cannot be found.
class TestExplorerContentCreator {

    private static final DocPath FOLDER = DocPath.fromParts("Feeds", "DOOR-ACCESS");
    private static final ExplorerNode ROOT = folder("root", "System");
    private static final ExplorerNode FEEDS = folder("feeds-1", "Feeds");

    private final ExplorerService explorer = Mockito.mock(ExplorerService.class);
    private final ExplorerNodeService nodes = Mockito.mock(ExplorerNodeService.class);
    private final ExplorerContentCreator creator = new ExplorerContentCreator(explorer, nodes);

    @Test
    void aFolderIsMadeOnceAndFoundThereafter() {
        final ExplorerNode made = folder("door-1", "DOOR-ACCESS");
        Mockito.when(nodes.getRoot()).thenReturn(ROOT);
        Mockito.when(nodes.getNodesByNameAndType(ROOT, "Feeds", ExplorerConstants.FOLDER_TYPE))
                .thenReturn(List.of(FEEDS));
        Mockito.when(nodes.getNodesByNameAndType(FEEDS, "DOOR-ACCESS", ExplorerConstants.FOLDER_TYPE))
                .thenReturn(List.of());
        Mockito.when(explorer.ensureFolderPath(DocPath.fromParts(false, "DOOR-ACCESS"), FEEDS,
                PermissionInheritance.DESTINATION)).thenReturn(made);

        creator.ensure(FOLDER);
        creator.ensure(FOLDER);
        creator.ensure(FOLDER);

        Mockito.verify(explorer, Mockito.times(1)).ensureFolderPath(DocPath.fromParts(false, "DOOR-ACCESS"), FEEDS,
                PermissionInheritance.DESTINATION);
        Mockito.verify(nodes, Mockito.times(1)).getNodesByNameAndType(FEEDS, "DOOR-ACCESS",
                ExplorerConstants.FOLDER_TYPE);

        // Creating a document walks to its folder again, and makes it again where it has gone.
        Mockito.when(explorer.create(PipelineDoc.TYPE, "Door", made, PermissionInheritance.DESTINATION))
                .thenReturn(ExplorerNode.builder().docRef(new DocRef(PipelineDoc.TYPE, "p-1", "Door")).build());

        final DocRef created = creator.create(FOLDER, PipelineDoc.TYPE, "Door");

        assertThat(created.getName()).isEqualTo("Door");
        Mockito.verify(nodes, Mockito.times(2)).getNodesByNameAndType(FEEDS, "DOOR-ACCESS",
                ExplorerConstants.FOLDER_TYPE);
    }

    @Test
    void aFolderWithATwinIsTheOneWithTheLowestUuid() {
        final ExplorerNode older = folder("a-door", "DOOR-ACCESS");
        final ExplorerNode twin = folder("b-door", "DOOR-ACCESS");
        Mockito.when(nodes.getRoot()).thenReturn(ROOT);
        Mockito.when(nodes.getNodesByNameAndType(ROOT, "Feeds", ExplorerConstants.FOLDER_TYPE))
                .thenReturn(List.of(FEEDS));
        Mockito.when(nodes.getNodesByNameAndType(FEEDS, "DOOR-ACCESS", ExplorerConstants.FOLDER_TYPE))
                .thenReturn(List.of(twin, older));
        Mockito.when(nodes.getChildren(older.getDocRef()))
                .thenReturn(List.of(folder("x", "DOOR-ACCESS Raw Events learned")));

        final Set<String> names = creator.names(FOLDER);

        assertThat(names).containsExactly("DOOR-ACCESS Raw Events learned");
        Mockito.verifyNoInteractions(explorer);
    }

    @Test
    void theTopOfTheTreeIsTheRoot() {
        Mockito.when(nodes.getRoot()).thenReturn(ROOT);
        Mockito.when(nodes.getChildren(ROOT.getDocRef())).thenReturn(List.of(FEEDS));

        assertThat(creator.names(DocPath.fromParts(List.of()))).containsExactly("Feeds");
    }

    private static ExplorerNode folder(final String uuid, final String name) {
        return ExplorerNode.builder()
                .docRef(new DocRef(ExplorerConstants.FOLDER_TYPE, uuid, name))
                .build();
    }
}
