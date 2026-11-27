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

package stroom.learningflow.scoring;

import stroom.learningflow.learning.TargetSchema;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.ScorerParameters;
import stroom.learningflow.transformation.AiXsltAllowList;
import stroom.learningflow.transformation.LearnedStylesheetCheck;
import stroom.learningflow.transformation.XsltStep;
import stroom.util.shared.ElementId;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/// The permitted check, a static check on a learned stylesheet: it may use only the XSLT/XPath functions
/// and instructions on the [allow-list][AiXsltAllowList]. Anything it uses that is not permitted is fed
/// back to the model as the reason, like any other check.
///
/// This is a safety check rather than a quality one, so a Learning Flow cannot set it to advice or off
/// (`CheckSettings.problems`). With automatic promotion nobody reads the stylesheet before it runs, and
/// the list is not enforced when the stylesheet runs, so this check is the only guard.
///
/// It judges the stylesheet, not the output, so it only applies to a step that wrote one. A parser's
/// configuration is a Data Splitter configuration or a JSON path, which cannot call anything, and a chain
/// being chosen has no configuration at all; for those it gives no score and the check does not appear.
public final class PermittedScorer implements Scorer {

    private static final ElementId PERMITTED = new ElementId("Permitted");

    private final AiXsltAllowList allowList;

    /// Creates the scorer with the allow-list built into this release.
    public PermittedScorer() {
        this(AiXsltAllowList.hardCoded());
    }

    /// Creates the scorer with a given allow-list.
    ///
    /// @param allowList What a learned stylesheet may use.
    public PermittedScorer(final AiXsltAllowList allowList) {
        this.allowList = allowList;
    }

    @Override
    public Check type() {
        return Check.PERMITTED;
    }

    @Override
    public Optional<Score> score(final ScorerParameters parameters,
                                 final Attempted step,
                                 final TargetSchema target) {
        if (step.configuration() == null || !XsltStep.ELEMENT_TYPE.equals(step.elementType())) {
            return Optional.empty();
        }
        final List<String> refusals = LearnedStylesheetCheck.refusals(step.configuration(), allowList);
        if (refusals.isEmpty()) {
            return Optional.of(new Score(type(), 1.0, List.of()));
        }
        final List<StoredError> diagnostics = new ArrayList<>(refusals.size());
        for (final String refusal : refusals) {
            diagnostics.add(new StoredError(Severity.ERROR, null, PERMITTED, refusal));
        }
        return Optional.of(new Score(type(), 0.0, diagnostics));
    }
}
