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

package stroom.learningflow.transformation;

import stroom.learningflow.learning.StepResult;
import stroom.learningflow.learning.StepRunner;
import stroom.pipeline.shared.XsltDoc;

import java.util.Optional;

/// The `XSLTFilter` element as a step of the A21 conversation, described but not run: what it is called,
/// the id it takes in a fragment and the document it is configured with.
///
/// A node runs it in a pipeline, wrapped in [stroom.learningflow.element.PipelineStepRunner], which takes
/// this description and replaces what the step does. The Tier 1 harness runs it with Saxon directly,
/// through the test fixture `StandInXsltStep`.
public final class XsltStep implements StepRunner {

    /// The pipeline element type of Stroom's XSLT filter.
    public static final String ELEMENT_TYPE = "XSLTFilter";
    /// The id the element takes in a fragment.
    public static final String ELEMENT_ID = "xsltFilter";
    private static final Configured CONFIGURED = new Configured(XsltDoc.TYPE, "xslt");

    @Override
    public String elementType() {
        return ELEMENT_TYPE;
    }

    @Override
    public String elementId() {
        return ELEMENT_ID;
    }

    @Override
    public Optional<Configured> configured() {
        return Optional.of(CONFIGURED);
    }

    /// Not run here: a stylesheet is run in a pipeline, by the runner that wraps this description.
    ///
    /// @throws UnsupportedOperationException Always.
    @Override
    public StepResult run(final String configuration, final String input) {
        throw new UnsupportedOperationException("An XSLTFilter step is run in a pipeline, by a "
                                                + "PipelineStepRunner that wraps this description");
    }
}
