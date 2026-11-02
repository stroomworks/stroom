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

package stroom.shapeshifter.ai.pack;

import stroom.shapeshifter.shared.ExtractionQualityParameters;
import stroom.shapeshifter.shared.FlowExample;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.SchemaConformanceParameters;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.YieldBasis;
import stroom.shapeshifter.shared.YieldParameters;

import java.util.List;

/// The configuration of the demo document: one Shapeshifter AI stage meant to be pointed at a feed
/// nobody has looked at yet, and to learn it.
///
/// It lives here, in one place, because two things must be the same configuration and not merely a
/// similar one: design 02 §5 scenario 51, which proves the settings learn four unrelated formats, and
/// the demo content pack, which ships them. A pack whose settings had drifted from the scenario that
/// proved them would be a plausible arrangement rather than a tested one.
///
/// What is *not* here is anything a demo would have to guess at. There are no template overrides
/// (A33): the built-in question text is what the model sees, so the pack cannot pin today's wording
/// into a document and go stale against it. The model reference and the feeds belong to the pack,
/// since the scenario has neither.
public final class DemoDocument {

    /// Every parser the stage knows how to write a configuration for. Narrowing this list is what every
    /// format scenario before 51 did, and it is exactly what a person setting up an unknown feed cannot
    /// do — so the demo allows them all and lets the chain question choose (A21).
    public static final List<String> ALLOWED_ELEMENTS =
            List.of("DSParser", "JSONParser", "XMLFragmentParser", "XSLTFilter");

    /// Prepended to the prompt contract of design 01 §10. It says what the built-in text cannot know:
    /// which schema this installation translates to, and the house style of the events it wants.
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

    /// The scorers one document uses for every format it meets. Compile and schema conformance gate —
    /// a candidate that does not compile, or whose output is not an event, is not a candidate — and
    /// extraction quality gates too, because the failure this feature exists to avoid is a transform
    /// that validates while saying nothing (design 01 §8.3, A16).
    public static List<ScorerSetting> scorers() {
        return List.of(
                new ScorerSetting(ScorerType.COMPILE, 0.0, 1.0, true, null),
                new ScorerSetting(ScorerType.YIELD, 1.0, 0.5, false,
                        new YieldParameters(1.0, YieldBasis.RECORDS)),
                new ScorerSetting(ScorerType.SCHEMA_CONFORMANCE, 1.0, 1.0, true,
                        new SchemaConformanceParameters("EVENTS")),
                new ScorerSetting(ScorerType.EXTRACTION_QUALITY, 1.0, 0.7, true,
                        new ExtractionQualityParameters(false, List.of())));
    }

    /// The settings themselves, applied to a builder the caller has already given a uuid and a name.
    ///
    /// The flow is the escalating one (A37): direct for a feed that gives its meaning up easily, and a
    /// target only where the transform falls short — which is what makes a single flow reasonable for
    /// formats nobody has seen yet. `minRecordsToLearn` is five rather than the default ten so that
    /// the small demo files are judged rather than bound provisionally, and the floor is 0.85 rather
    /// than 0.9 so that a good first attempt on an awkward format is promoted rather than retried.
    ///
    /// @param builder A builder to apply the demo's settings to.
    /// @return The same builder, for chaining.
    public static ShapeshifterAiDoc.Builder configure(final ShapeshifterAiDoc.Builder builder) {
        return builder
                .learningMode(LearningMode.AUTOMATIC)
                .flow(FlowExample.ESCALATING)
                .allowedElements(ALLOWED_ELEMENTS)
                .instructions(INSTRUCTIONS)
                .minRecordsToLearn(5)
                .promotionFloor(0.85)
                .scorers(scorers());
    }
}
