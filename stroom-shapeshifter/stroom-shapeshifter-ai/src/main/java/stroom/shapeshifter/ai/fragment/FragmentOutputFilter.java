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

package stroom.shapeshifter.ai.fragment;

import stroom.pipeline.factory.ConfigurableElement;
import stroom.pipeline.filter.AbstractXMLFilter;
import stroom.pipeline.shared.data.PipelineElementType;
import stroom.svg.shared.SvgImage;

import jakarta.inject.Inject;

/**
 * The tail added to a fragment when it is run: every event the fragment's last element emits goes
 * wherever {@link FragmentOutput} points. Internal — a fragment has no destination of its own (A20),
 * and this is the one it is lent.
 */
@ConfigurableElement(
        type = FragmentOutputFilter.TYPE,
        // Stepping visibility because a build that captures drops any element without it and links
        // straight past to its children (`PipelineFactory#getChildElements`). This element has no
        // children, so being dropped means the tail's events reach nobody — and a fragment is always
        // run under a capture, since a run is judged as well as served.
        roles = {
                PipelineElementType.ROLE_TARGET,
                PipelineElementType.VISABILITY_STEPPING},
        icon = SvgImage.AI)
public class FragmentOutputFilter extends AbstractXMLFilter {

    public static final String TYPE = "ShapeshifterAiOutput";

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
