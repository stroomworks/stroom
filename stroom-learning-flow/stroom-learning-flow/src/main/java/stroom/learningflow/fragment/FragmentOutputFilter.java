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

import stroom.pipeline.factory.ConfigurableElement;
import stroom.pipeline.filter.AbstractXMLFilter;
import stroom.pipeline.shared.data.PipelineElementType;
import stroom.svg.shared.SvgImage;

import jakarta.inject.Inject;

/// An internal pipeline element that [PipelineFragmentRunner] adds to the end of a fragment when it runs
/// it. A fragment has no writer or destination of its own, so this element forwards every event the
/// fragment's last element emits to the handler held by [FragmentOutput].
@ConfigurableElement(
        type = FragmentOutputFilter.TYPE,
        // Stepping visibility is needed because a pipeline built with a capture drops any element without
        // it and links its parent straight to its children (`PipelineFactory#getChildElements`). This
        // element has no children, so if it were dropped its events would reach nothing, and a fragment
        // that is being judged is always run with a capture.
        roles = {
                PipelineElementType.ROLE_TARGET,
                PipelineElementType.VISABILITY_STEPPING},
        icon = SvgImage.AI)
public class FragmentOutputFilter extends AbstractXMLFilter {

    public static final String TYPE = "LearningFlowOutput";

    private final FragmentOutput output;

    @Inject
    public FragmentOutputFilter(final FragmentOutput output) {
        this.output = output;
    }

    @Override
    public void startProcessing() {
        setContentHandler(output.getHandler());
        super.startProcessing();
    }
}
