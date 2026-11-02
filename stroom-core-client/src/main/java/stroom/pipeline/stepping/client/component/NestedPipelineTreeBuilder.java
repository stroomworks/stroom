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

package stroom.pipeline.stepping.client.component;

import stroom.pipeline.shared.data.PipelineElement;
import stroom.pipeline.structure.client.DefaultPipelineTreeBuilder;
import stroom.pipeline.structure.client.PipelineModel;
import stroom.util.shared.NullSafe;
import stroom.widget.htree.client.treelayout.util.DefaultTreeForTreeLayout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// The pipeline's tree with the elements an element ran *inside* itself hung beneath it (A30,
/// design 01 §11.7).
///
/// Some elements are not one step but several. A supervised stage runs a whole learned chain over what it
/// is given and emits what came out of the far end, so from outside it is one element with a stream going
/// in and events coming out. This is what opens it: the chain's own elements, beneath the stage, where a
/// person debugging the pipeline already looks.
///
/// They are not in the pipeline. Nothing here touches the model: the elements come from the last step's
/// answer, they last only as long as that answer does, and the pipeline a person saves is the one they
/// drew. What is nested is only known once the pipeline has *run* — which chain a stage ran
/// depends on the stream in front of it — so it cannot be in the structure, and this is why the stepper
/// builds its own tree rather than showing the structure editor's.
public class NestedPipelineTreeBuilder extends DefaultPipelineTreeBuilder {

    private final Map<String, List<PipelineElement>> nested = new HashMap<>();

    /// What the last step said each element ran inside itself, by the id of the element that ran them.
    /// Replaced whole: a step that found nothing nested leaves nothing hanging from the step before.
    public void setNested(final Map<String, List<PipelineElement>> discovered) {
        nested.clear();
        nested.putAll(NullSafe.map(discovered));
    }

    /// Whether this element is one of the nested ones rather than one of the pipeline's own. The stepper
    /// asks before offering anything that edits, filters or saves.
    public boolean isNested(final PipelineElement element) {
        return element != null && nested.values().stream()
                .anyMatch(children -> children.contains(element));
    }

    @Override
    public DefaultTreeForTreeLayout<PipelineElement> getTree(final PipelineModel model) {
        if (model == null || model.getChildMap() == null) {
            return null;
        }
        if (nested.isEmpty()) {
            return super.getTree(model);
        }
        // A copy, because the model's child map is the pipeline's own and what is hung here is not.
        final Map<PipelineElement, List<PipelineElement>> childMap = new HashMap<>();
        model.getChildMap().forEach((parent, children) -> childMap.put(parent, new ArrayList<>(children)));
        childMap.keySet().stream()
                .filter(parent -> nested.containsKey(parent.getId()))
                .toList()
                .forEach(parent -> hang(childMap, parent));
        // A stage at the end of a pipeline has no children at all, so it is in nobody's key set; it is
        // still something that ran a chain.
        model.getChildMap().values().stream()
                .flatMap(List::stream)
                .filter(element -> nested.containsKey(element.getId()))
                .filter(element -> !childMap.containsKey(element))
                .toList()
                .forEach(parent -> hang(childMap, parent));
        return build(childMap);
    }

    /// The chain beneath the element that ran it, in the order it ran, and above whatever the pipeline
    /// links below: what the stage produced goes on to its own children, so the chain that produced it
    /// reads first.
    private void hang(final Map<PipelineElement, List<PipelineElement>> childMap,
                      final PipelineElement parent) {
        final List<PipelineElement> children = childMap.computeIfAbsent(parent, key -> new ArrayList<>());
        children.addAll(0, nested.get(parent.getId()));
    }
}
