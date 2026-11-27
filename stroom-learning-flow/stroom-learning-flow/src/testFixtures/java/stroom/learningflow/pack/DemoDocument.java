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

package stroom.learningflow.pack;

import stroom.learningflow.learning.FlowExamples;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckState;
import stroom.learningflow.shared.ExtractionQualityParameters;
import stroom.learningflow.shared.FlowExample;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.LearningMode;
import stroom.learningflow.shared.PromotionMode;
import stroom.learningflow.shared.YieldBasis;
import stroom.learningflow.shared.YieldParameters;

import java.util.List;

/// The settings of the demo Learning Flow document, which is meant to be pointed at a feed nobody has
/// looked at yet and learn to translate it.
///
/// The settings are defined in one place because two things must use exactly the same configuration:
/// `TestScenario51AnyFormatFromAnyFeed`, which shows that these settings learn four unrelated formats, and
/// [DemoContentPack], which ships them. If the pack's settings drifted from the scenario's, the pack would
/// no longer be tested.
///
/// There are no template overrides: the model sees the built-in question text, so the pack does not fix
/// today's wording into a document that would then go out of date. The model document and the feeds are
/// defined by the pack, since the scenario needs neither.
public final class DemoDocument {

    /// Every parser the Smart Pipeline element knows how to write a configuration for. A person setting up
    /// an unknown feed cannot know which to narrow this list to, so the demo allows them all and lets the
    /// model choose when it is asked for the chain.
    public static final List<String> ALLOWED_ELEMENTS =
            List.of("DSParser", "JSONParser", "XMLFragmentParser", "XSLTFilter");

    /// Instructions added before the built-in question text. They say what the built-in text cannot know:
    /// which schema this installation translates to, and the style of the events it wants.
    public static final String INSTRUCTIONS = """
            You are configuring a Stroom pipeline stage that turns raw machine-generated records into \
            Stroom event-logging XML.

            The output must validate against the event-logging schema in the EVENTS schema group. \
            Every event needs an EventTime/TimeCreated in ISO 8601 with a timezone, an EventSource that \
            names the system the record came from, and an EventDetail whose typed element says what \
            happened — Authenticate, Process, View, Create, Delete and so on.

            Prefer a typed element to a generic one. Where a record names a user, put it in \
            EventSource/User/Id rather than in a Data element; the same goes for the host, the device \
            and the file or document a record is about. Use Data elements only for values the schema \
            has no home for, and never emit an event whose detail is Unknown when the record says what \
            happened.

            Keep the whole record. If a field cannot be placed, carry it as a named Data element rather \
            than dropping it. Do not invent values that the record does not contain, and do not \
            normalise an identifier into something prettier than what was logged.

            Write British English in any text you produce.""";

    private DemoDocument() {
    }

    /// Returns the checks the document uses for every format. Compile and schema conformance are gates,
    /// since a candidate that does not compile, or whose output is not valid events, is of no use.
    /// Extraction quality is a gate too, because the failure this feature exists to avoid is a translation
    /// that validates while saying nothing useful. Yield, times and literals are advice only; coverage and
    /// the business rules are off; the remaining checks are gates.
    public static List<CheckSetting> checks() {
        return List.of(
                new CheckSetting(Check.COMPILE, CheckState.GATE, 1.0, null),
                CheckSetting.of(Check.COVERAGE, CheckState.OFF),
                new CheckSetting(Check.YIELD, CheckState.ADVICE, 0.5,
                        new YieldParameters(1.0, YieldBasis.RECORDS)),
                CheckSetting.of(Check.WHOLENESS, CheckState.GATE),
                new CheckSetting(Check.CONFORMANCE, CheckState.GATE, 1.0, null),
                new CheckSetting(Check.QUALITY, CheckState.GATE, 0.7,
                        new ExtractionQualityParameters(false, List.of())),
                CheckSetting.of(Check.RULES, CheckState.OFF),
                CheckSetting.of(Check.TIMES, CheckState.ADVICE),
                CheckSetting.of(Check.LITERALS, CheckState.ADVICE),
                CheckSetting.of(Check.PERMITTED, CheckState.GATE),
                CheckSetting.of(Check.PRESERVATION, CheckState.GATE),
                CheckSetting.of(Check.FIDELITY, CheckState.GATE),
                CheckSetting.of(Check.REGRESSION, CheckState.GATE));
    }

    /// Applies the demo settings to a builder that the caller has already given a UUID and a name.
    ///
    /// The flow is the escalating one: it settles how the input splits into records, asks for the
    /// configurations directly, and only asks for target events where the translation falls short. That
    /// makes a single flow reasonable for formats nobody has seen yet. `minRecordsToLearn` is five rather
    /// than the default ten, so that the small demo files are enough to promote automatically; below that
    /// minimum, a result is left as a draft for a person to approve.
    ///
    /// @param builder A builder to apply the demo's settings to.
    /// @return The same builder, for chaining.
    public static LearningFlowDoc.Builder configure(final LearningFlowDoc.Builder builder) {
        return builder
                .learningMode(LearningMode.AUTOMATIC)
                // Deliberately automatic, whereas a new Learning Flow defaults to review: the demo is meant
                // to learn a feed from start to finish while somebody watches, and stopping at a draft
                // would add a step. Importing it needs the `APPROVE_SMART_PIPELINES_AUTOMATICALLY`
                // permission, which an administrator has.
                .promotionMode(PromotionMode.AUTOMATIC)
                .flow(FlowExamples.flow(FlowExample.ESCALATING))
                .allowedElements(ALLOWED_ELEMENTS)
                .instructions(INSTRUCTIONS)
                .minRecordsToLearn(5)
                .checks(checks());
    }
}
