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

package stroom.learningflow.doc;

import stroom.docref.DocRef;
import stroom.docstore.api.DependencyRemapFunction;
import stroom.docstore.api.DependencyRemapper;
import stroom.learningflow.shared.Binding;
import stroom.learningflow.shared.LearningFlowDoc;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests the Learning Flow store's dependency remap function on its own.
///
/// `StoreImpl` uses this function for import remapping, copying and the dependency graph, and if the store
/// returned the inherited `null` all three would be silently disabled.
class TestLearningFlowDependencyRemap {

    private final DependencyRemapFunction<LearningFlowDoc> function = function();

    private static DependencyRemapFunction<LearningFlowDoc> function() {
        final DependencyRemapFunction<LearningFlowDoc> function =
                LearningFlowFixture.store().getDependencyRemapFunction();
        assertThat(function)
                .describedAs("A null function disables remapping and dependency tracking entirely")
                .isNotNull();
        return function;
    }

    @Test
    void declaresTheModelAndNothingElseAsADependency() {
        // Learned fragments are recorded in the binding tables, not on the document, so an exported document
        // depends only on its model, just as a processor filter's state is not part of the pipeline it runs.
        final DependencyRemapper remapper = new DependencyRemapper();

        function.remap(doc(), remapper);

        assertThat(remapper.getDependencies()).containsExactly(LearningFlowFixture.MODEL);
        assertThat(remapper.isChanged()).isFalse();
    }

    @Test
    void rewritesTheModelAndNothingElse() {
        // The document names no fragment, because bindings are held in tables. An exported document carries
        // only what a person configured, none of what was learned, so remapping a fragment changes nothing.
        final DocRef importedModel = LearningFlowFixture.MODEL.copy().uuid("model-2").build();
        final DocRef importedFragment = LearningFlowFixture.FRAGMENT.copy().uuid("fragment-2").build();
        final DependencyRemapper remapper = new DependencyRemapper(Map.of(
                LearningFlowFixture.MODEL, importedModel,
                LearningFlowFixture.FRAGMENT, importedFragment));
        final LearningFlowDoc original = doc();

        final LearningFlowDoc remapped = function.remap(original, remapper);

        assertThat(remapper.isChanged()).isTrue();
        assertThat(remapped.getModel()).isEqualTo(importedModel);
        assertThat(remapped.copy().model(original.getModel()).build())
                .describedAs("every field other than the model is untouched")
                .isEqualTo(original);
    }

    @Test
    void noModelAndAnEmptyTableIsANoOp() {
        final DependencyRemapper remapper = new DependencyRemapper();
        final LearningFlowDoc bare = LearningFlowDoc.builder().uuid("policy-1").name("bare").build();

        final LearningFlowDoc remapped = function.remap(bare, remapper);

        assertThat(remapped).isEqualTo(bare);
        assertThat(remapper.getDependencies()).isEmpty();
        assertThat(remapper.isChanged()).isFalse();
    }

    private static LearningFlowDoc doc() {
        return LearningFlowFixture.configured(LearningFlowDoc.builder()
                        .uuid("policy-1")
                        .name("syslog-ai")
                        .build())
                .build();
    }
}
