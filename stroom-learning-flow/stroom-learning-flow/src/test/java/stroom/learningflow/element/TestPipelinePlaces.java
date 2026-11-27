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

import stroom.docref.DocRef;
import stroom.explorer.api.ExplorerNodeService;
import stroom.explorer.shared.ExplorerConstants;
import stroom.explorer.shared.ExplorerNode;
import stroom.learningflow.element.LearnerDocuments.Element;
import stroom.learningflow.stage.Learner;
import stroom.learningflow.stage.Places.Place;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.shared.data.PipelinePropertyValue;
import stroom.security.mock.MockSecurityContext;
import stroom.util.shared.DocPath;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests where in the explorer tree a learner's learned content is written.
///
/// The folder and name come from the Smart Pipeline element's properties, read from the merged pipeline so that
/// a value set on the pipeline itself counts. Where the element sets neither, the content goes in the pipeline's
/// own folder, named after the pipeline.
class TestPipelinePlaces {

    private static final Learner LEARNER = new Learner("pipeline-1", "learningFlow");
    private static final PipelineDoc PIPELINE = PipelineDoc.builder()
            .uuid("pipeline-1")
            .name("DOOR-ACCESS Raw Events")
            .build();

    private final LearnerDocuments documents = Mockito.mock(LearnerDocuments.class);
    private final ExplorerNodeService explorer = Mockito.mock(ExplorerNodeService.class);
    private final PipelinePlaces places = new PipelinePlaces(documents, () -> explorer, MockSecurityContext::new);

    @BeforeEach
    void setUp() {
        Mockito.when(explorer.getRoot()).thenReturn(ExplorerConstants.SYSTEM_NODE);
        // The pipeline is in Feeds/DOOR-ACCESS. The explorer's path starts with the root node.
        Mockito.when(explorer.getPath(PIPELINE.asDocRef())).thenReturn(List.of(
                ExplorerConstants.SYSTEM_NODE, folder("Feeds"), folder("DOOR-ACCESS")));
    }

    @Test
    void whatTheElementSaysIsWhereItGoes() {
        element(Map.of(
                PipelinePlaces.FOLDER_PROPERTY, new PipelinePropertyValue("Learned/DOOR-ACCESS"),
                PipelinePlaces.NAME_PROPERTY, new PipelinePropertyValue("Door access")));

        assertThat(places.of(LEARNER))
                .isEqualTo(new Place(DocPath.fromParts("Learned", "DOOR-ACCESS"), "Door access"));
    }

    @Test
    void anElementThatSaysNothingPutsItBesideThePipelineUnderItsName() {
        element(Map.of());

        assertThat(places.of(LEARNER))
                .describedAs("the pipeline's own folder, from the top of the tree, not the root's, and its name "
                             + "with what tells the two apart (A133)")
                .isEqualTo(new Place(DocPath.fromParts("Feeds", "DOOR-ACCESS"), "DOOR-ACCESS Raw Events learned"));
    }

    @Test
    void aBlankValueIsAnUnsetOne() {
        // A Pipeline Factory whose name pattern substitutes to nothing writes a blank value, which must be
        // treated as unset rather than as a folder or a name.
        element(Map.of(
                PipelinePlaces.FOLDER_PROPERTY, new PipelinePropertyValue("  "),
                PipelinePlaces.NAME_PROPERTY, new PipelinePropertyValue("")));

        assertThat(places.of(LEARNER))
                .isEqualTo(new Place(DocPath.fromParts("Feeds", "DOOR-ACCESS"), "DOOR-ACCESS Raw Events learned"));
    }

    @Test
    void aFolderIsTakenAsMeantNotAsTyped() {
        // A leading slash, spaces around a slash, or an empty part where a pattern substituted to nothing are
        // all tidied away.
        element(Map.of(PipelinePlaces.FOLDER_PROPERTY, new PipelinePropertyValue(" /Learned // DOOR-ACCESS/ ")));

        assertThat(places.of(LEARNER).folder()).isEqualTo(DocPath.fromParts("Learned", "DOOR-ACCESS"));

        element(Map.of(PipelinePlaces.FOLDER_PROPERTY, new PipelinePropertyValue("/")));

        assertThat(places.of(LEARNER).folder())
                .describedAs("the top of the tree")
                .isEqualTo(DocPath.fromParts(List.of()));
    }

    @Test
    void aPipelineAtTheTopOfTheTreePutsItAtTheTop() {
        // A pipeline in no folder has only the root as its path. A pipeline created through its store, and so
        // unknown to the explorer, has an empty path. Both mean the top of the tree.
        Mockito.when(explorer.getPath(PIPELINE.asDocRef())).thenReturn(List.of(ExplorerConstants.SYSTEM_NODE));
        element(Map.of());

        assertThat(places.of(LEARNER).folder()).isEqualTo(DocPath.fromParts(List.of()));

        Mockito.when(explorer.getPath(PIPELINE.asDocRef())).thenReturn(List.of());

        assertThat(places.of(LEARNER).folder()).isEqualTo(DocPath.fromParts(List.of()));
    }

    @Test
    void aPipelineThatCannotBeReadStillHasSomewhereToPutWhatWasLearned() {
        // The pipeline was deleted while one of its sessions was still running. What the session learns is
        // still written somewhere a person can find it: a "Learning Flow" folder, named by the pipeline's UUID.
        Mockito.when(documents.elementOf(LEARNER)).thenReturn(Optional.empty());

        assertThat(places.of(LEARNER)).isEqualTo(new Place(DocPath.fromParts("Learning Flow"), "pipeline-1"));
    }

    private void element(final Map<String, PipelinePropertyValue> properties) {
        Mockito.when(documents.elementOf(LEARNER)).thenReturn(Optional.of(new Element(PIPELINE, properties)));
    }

    private static ExplorerNode folder(final String name) {
        return ExplorerNode.builder().docRef(new DocRef("Folder", "folder-" + name, name)).build();
    }
}
