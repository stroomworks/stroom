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

package stroom.learningflow.fragment;

import stroom.pipeline.factory.ConfigurableElement;
import stroom.pipeline.filter.AbstractXMLFilter;
import stroom.pipeline.shared.data.PipelineElementType;
import stroom.svg.shared.SvgImage;

import jakarta.inject.Inject;

/// An internal pipeline element that [PipelineFragmentRunner] adds as a second child of a fragment's
/// parser during a serving run. The parser's output still goes to the split and the transform, and also
/// to this tap, which forwards it to the handler held by [FragmentTap]. That handler keeps the record
/// currently being transformed, so that a record whose output falls short of the bar can be shown as the
/// parser produced it. It is not part of the stored fragment.
@ConfigurableElement(
        type = FragmentTapFilter.TYPE,
        roles = {
                PipelineElementType.ROLE_TARGET,
                PipelineElementType.VISABILITY_STEPPING},
        icon = SvgImage.AI)
public class FragmentTapFilter extends AbstractXMLFilter {

    public static final String TYPE = "LearningFlowTap";

    private final FragmentTap tap;

    @Inject
    public FragmentTapFilter(final FragmentTap tap) {
        this.tap = tap;
    }

    @Override
    public void startProcessing() {
        if (tap.getHandler() != null) {
            setContentHandler(tap.getHandler());
        }
        super.startProcessing();
    }
}
