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

package stroom.shapeshifter.ai.element;

import stroom.docref.DocRef;
import stroom.explorer.api.ExplorerNodeService;
import stroom.explorer.shared.ExplorerConstants;
import stroom.explorer.shared.ExplorerNode;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipeline.shared.data.PipelinePropertyValue;
import stroom.security.mock.MockSecurityContext;
import stroom.shapeshifter.ai.element.LearnerDocuments.Element;
import stroom.shapeshifter.ai.stage.Learner;
import stroom.shapeshifter.ai.stage.Places.Place;
import stroom.util.shared.DocPath;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/// Design 06 A113: where a learner's content goes in a node. What the element says, read from its pipeline
/// as merged — a value a Pipeline Factory wrote from its patterns — and beside the pipeline under its name
/// where the element says nothing.
class TestPipelinePlaces {

    private static final Learner LEARNER = new Learner("pipeline-1", "shapeshifterAi");
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
        // The pipeline sits in Feeds/DOOR-ACCESS, under the root the explorer's path starts from.
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
        // A factory whose pattern substitutes to nothing writes a blank; a blank is not a folder or a name.
        element(Map.of(
                PipelinePlaces.FOLDER_PROPERTY, new PipelinePropertyValue("  "),
                PipelinePlaces.NAME_PROPERTY, new PipelinePropertyValue("")));

        assertThat(places.of(LEARNER))
                .isEqualTo(new Place(DocPath.fromParts("Feeds", "DOOR-ACCESS"), "DOOR-ACCESS Raw Events learned"));
    }

    @Test
    void aFolderIsTakenAsMeantNotAsTyped() {
        // A leading slash, space around one, or an empty part where a pattern substituted to nothing.
        element(Map.of(PipelinePlaces.FOLDER_PROPERTY, new PipelinePropertyValue(" /Learned // DOOR-ACCESS/ ")));

        assertThat(places.of(LEARNER).folder()).isEqualTo(DocPath.fromParts("Learned", "DOOR-ACCESS"));

        element(Map.of(PipelinePlaces.FOLDER_PROPERTY, new PipelinePropertyValue("/")));

        assertThat(places.of(LEARNER).folder())
                .describedAs("the top of the tree")
                .isEqualTo(DocPath.fromParts(List.of()));
    }

    @Test
    void aPipelineAtTheTopOfTheTreePutsItAtTheTop() {
        // A pipeline in no folder, or one made through its store and so in none the explorer knows: the
        // path is the root's, or nothing, and either is the top of the tree.
        Mockito.when(explorer.getPath(PIPELINE.asDocRef())).thenReturn(List.of(ExplorerConstants.SYSTEM_NODE));
        element(Map.of());

        assertThat(places.of(LEARNER).folder()).isEqualTo(DocPath.fromParts(List.of()));

        Mockito.when(explorer.getPath(PIPELINE.asDocRef())).thenReturn(List.of());

        assertThat(places.of(LEARNER).folder()).isEqualTo(DocPath.fromParts(List.of()));
    }

    @Test
    void aPipelineThatCannotBeReadStillHasSomewhereToPutWhatWasLearned() {
        // Deleted while a session it opened was still being carried on: what the session learns is kept
        // where a person can find it, under the pipeline's uuid.
        Mockito.when(documents.elementOf(LEARNER)).thenReturn(Optional.empty());

        assertThat(places.of(LEARNER)).isEqualTo(new Place(DocPath.fromParts("Shapeshifter"), "pipeline-1"));
    }

    private void element(final Map<String, PipelinePropertyValue> properties) {
        Mockito.when(documents.elementOf(LEARNER)).thenReturn(Optional.of(new Element(PIPELINE, properties)));
    }

    private static ExplorerNode folder(final String name) {
        return ExplorerNode.builder().docRef(new DocRef("Folder", "folder-" + name, name)).build();
    }
}
