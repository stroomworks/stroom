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

package stroom.shapeshifter.ai.doc;

import stroom.docref.DocRef;
import stroom.docstore.api.DependencyRemapFunction;
import stroom.docstore.api.DependencyRemapper;
import stroom.shapeshifter.shared.Binding;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dependency function on its own. {@code StoreImpl} uses it for three things — import remapping,
 * copying and the dependency graph — and the inherited null would silently disable all three.
 */
class TestShapeshifterAiDependencyRemap {

    private final DependencyRemapFunction<ShapeshifterAiDoc> function = function();

    private static DependencyRemapFunction<ShapeshifterAiDoc> function() {
        final DependencyRemapFunction<ShapeshifterAiDoc> function =
                ShapeshifterAiFixture.store().getDependencyRemapFunction();
        assertThat(function)
                .describedAs("A null function disables remapping and dependency tracking entirely")
                .isNotNull();
        return function;
    }

    @Test
    void declaresTheModelAndNothingElseAsADependency() {
        // Since A41 the fragments are named by rows, not by the document, so an exported document depends
        // on its model alone — as a processor filter's state is not part of the pipeline it runs.
        final DependencyRemapper remapper = new DependencyRemapper();

        function.remap(doc(), remapper);

        assertThat(remapper.getDependencies()).containsExactly(ShapeshifterAiFixture.MODEL);
        assertThat(remapper.isChanged()).isFalse();
    }

    @Test
    void rewritesTheModelAndNothingElse() {
        // Since A41 the document names no fragment: the bindings are rows, and an exported document carries
        // its configuration and none of what it learned.
        final DocRef importedModel = ShapeshifterAiFixture.MODEL.copy().uuid("model-2").build();
        final DocRef importedFragment = ShapeshifterAiFixture.FRAGMENT.copy().uuid("fragment-2").build();
        final DependencyRemapper remapper = new DependencyRemapper(Map.of(
                ShapeshifterAiFixture.MODEL, importedModel,
                ShapeshifterAiFixture.FRAGMENT, importedFragment));
        final ShapeshifterAiDoc original = doc();

        final ShapeshifterAiDoc remapped = function.remap(original, remapper);

        assertThat(remapper.isChanged()).isTrue();
        assertThat(remapped.getModel()).isEqualTo(importedModel);
        assertThat(remapped.copy().model(original.getModel()).build())
                .describedAs("every field other than the model is untouched")
                .isEqualTo(original);
    }

    @Test
    void noModelAndAnEmptyTableIsANoOp() {
        final DependencyRemapper remapper = new DependencyRemapper();
        final ShapeshifterAiDoc bare = ShapeshifterAiDoc.builder().uuid("policy-1").name("bare").build();

        final ShapeshifterAiDoc remapped = function.remap(bare, remapper);

        assertThat(remapped).isEqualTo(bare);
        assertThat(remapper.getDependencies()).isEmpty();
        assertThat(remapper.isChanged()).isFalse();
    }

    private static ShapeshifterAiDoc doc() {
        return ShapeshifterAiFixture.configured(ShapeshifterAiDoc.builder()
                        .uuid("policy-1")
                        .name("syslog-ai")
                        .build())
                .build();
    }
}
