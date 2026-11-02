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

package stroom.pipelinefactory;

import stroom.docref.DocRef;
import stroom.docstore.api.DocDependencyService;
import stroom.explorer.api.ExplorerService;
import stroom.explorer.shared.ExplorerNode;
import stroom.explorer.shared.PermissionInheritance;
import stroom.pipeline.PipelineStore;
import stroom.pipelinefactory.impl.PipelineFactoryStore;
import stroom.pipelinefactory.shared.PipelineFactoryDoc;
import stroom.test.AbstractCoreIntegrationTest;
import stroom.util.shared.DocPath;
import stroom.util.shared.EntityServiceException;
import stroom.util.shared.UserRef;

import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// A Pipeline Factory made where a person makes one, through the explorer, and saved and read back
/// through the store the application binds (design 06, phase I slice 1).
class TestPipelineFactoryStore extends AbstractCoreIntegrationTest {

    @Inject
    private ExplorerService explorerService;
    @Inject
    private PipelineFactoryStore store;
    @Inject
    private PipelineStore pipelineStore;
    @Inject
    private DocDependencyService dependencies;

    @Test
    void aFactoryIsMadeInTheExplorerAndSavedAndReadBack() {
        final DocRef docRef = create("raw events");

        final PipelineFactoryDoc made = store.readDocument(docRef);
        assertThat(made.isEnabled()).isFalse();
        assertThat(made.getKey()).containsExactly("Feed", "Type");

        final DocRef template = pipelineStore.createDocument("AI template");
        final PipelineFactoryDoc saved = store.writeDocument(made.copy()
                .enabled(true)
                .template(template)
                .runAsUser(UserRef.forUserUuid("run-as"))
                .build());

        assertThat(store.readDocument(docRef)).isEqualTo(saved);
        // The template is the factory's one dependency, so it is shown, exported and re-pointed with it.
        assertThat(dependencies.getDependantsOf(template)).contains(docRef);
    }

    @Test
    void aFactoryWithProblemsIsRefusedByTheApplicationsStore() {
        final DocRef docRef = create("bad key");
        final PipelineFactoryDoc made = store.readDocument(docRef);

        assertThatThrownBy(() -> store.writeDocument(made.copy().key(List.of("Feed", "Type", "Id")).build()))
                .isInstanceOf(EntityServiceException.class)
                .hasMessageContaining("'Id' cannot be a key field");
        assertThat(store.readDocument(docRef).getKey()).containsExactly("Feed", "Type");
    }

    private DocRef create(final String name) {
        final ExplorerNode folder = explorerService.ensureFolderPath(
                DocPath.fromParts("Pipeline Factories"), PermissionInheritance.DESTINATION);
        return explorerService.create(PipelineFactoryDoc.TYPE, name, folder, PermissionInheritance.DESTINATION)
                .getDocRef();
    }
}
