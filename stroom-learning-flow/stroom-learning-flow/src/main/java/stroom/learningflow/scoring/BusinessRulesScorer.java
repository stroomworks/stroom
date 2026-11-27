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

package stroom.learningflow.scoring;

import stroom.learningflow.learning.TargetSchema;
import stroom.learningflow.shared.BusinessRulesParameters;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.ScorerParameters;
import stroom.learningflow.shared.XPathAssertion;
import stroom.util.shared.ElementId;
import stroom.util.shared.ErrorType;
import stroom.util.shared.Severity;
import stroom.util.shared.StoredError;

import net.sf.saxon.s9api.XdmNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/// The business rules scorer. It checks the document's named XPath assertions, each of which every
/// record must satisfy, and, where the document says so, counts the transform's own `xsl:message`
/// warnings as broken rules reported by the stylesheet. The score is the proportion of records that meet
/// every rule; each message counts against one record, since the transform does not say which. The
/// feedback names each rule and how many records broke it. It applies only to a step that transformed
/// records into records.
public final class BusinessRulesScorer implements Scorer {

    private static final ElementId RULES = new ElementId("BusinessRules");

    @Override
    public Check type() {
        return Check.RULES;
    }

    @Override
    public void validate(final ScorerParameters parameters) {
        for (final XPathAssertion assertion : ((BusinessRulesParameters) parameters).getAssertions()) {
            OutputRecords.check(assertion.getXpath());
        }
    }

    @Override
    public Optional<Score> score(final ScorerParameters parameters,
                                 final Attempted step,
                                 final TargetSchema target) {
        final BusinessRulesParameters rules = (BusinessRulesParameters) parameters;
        final Optional<OutputRecords> parsed = OutputRecords.ofTransformed(step);
        if (parsed.isEmpty() || parsed.get().records().isEmpty()) {
            return Optional.empty();
        }
        final OutputRecords output = parsed.get();
        final List<XdmNode> records = output.records();
        final List<StoredError> diagnostics = new ArrayList<>();
        final Set<Integer> failing = new HashSet<>();
        boolean judged = false;

        for (final XPathAssertion assertion : rules.getAssertions()) {
            judged = true;
            long broken = 0;
            for (int i = 0; i < records.size(); i++) {
                if (!output.holds(records.get(i), assertion.getXpath())) {
                    broken++;
                    failing.add(i);
                }
            }
            if (broken > 0) {
                diagnostics.add(new StoredError(Severity.WARNING, null, RULES, "Rule '" + assertion.getName()
                                                                              + "' (" + assertion.getXpath()
                                                                              + ") fails for " + broken + " of "
                                                                              + records.size() + " records"));
            }
        }
        int messageFailures = 0;
        if (rules.isIncludeTransformMessages()) {
            judged = true;
            final List<StoredError> messages = step.result().diagnostics().stream()
                    .filter(error -> error.getErrorType() == ErrorType.CODE
                                     && error.getSeverity().greaterThanOrEqual(Severity.WARNING))
                    .toList();
            messageFailures = messages.size();
            if (!messages.isEmpty()) {
                diagnostics.add(new StoredError(Severity.WARNING, null, RULES, "The transform reported "
                                                                              + messages.size() + " message(s) over "
                                                                              + records.size() + " records; the first: "
                                                                              + messages.get(0).getMessage()));
            }
        }
        if (!judged) {
            return Optional.empty();
        }
        final int failed = Math.min(records.size(), failing.size() + messageFailures);
        // A transform's messages are not tied to a record, so only the assertions identify failing records.
        return Optional.of(new Score(type(), 1.0 - (double) failed / records.size(), diagnostics, failing));
    }
}
