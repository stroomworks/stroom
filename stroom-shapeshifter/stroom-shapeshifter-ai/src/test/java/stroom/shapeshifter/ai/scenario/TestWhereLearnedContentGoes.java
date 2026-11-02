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

package stroom.shapeshifter.ai.scenario;

import stroom.shapeshifter.ai.extraction.ExtractionCorpus.Golden;
import stroom.shapeshifter.ai.stage.Decision.Deferred;
import stroom.shapeshifter.ai.stage.Decision.Promoted;
import stroom.shapeshifter.ai.stage.Input;
import stroom.shapeshifter.ai.stage.Places.Place;
import stroom.shapeshifter.ai.stage.StageRun;
import stroom.shapeshifter.shared.Binding;
import stroom.shapeshifter.shared.LearningMode;
import stroom.shapeshifter.shared.ScorerSetting;
import stroom.shapeshifter.shared.ScorerType;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.YieldBasis;
import stroom.shapeshifter.shared.YieldParameters;
import stroom.util.shared.DocPath;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Design 06 A113 and A132: what a learner learns is named as its element says — which a Pipeline Factory
/// sets from its patterns — and a second fragment it learns takes the first free number after the name.
class TestWhereLearnedContentGoes {

    private static final String DOC = "doc-1";
    private static final String FEED = "DOOR-ACCESS";
    private static final String XSLT = Scenarios.resource("csv-logon.xsl");
    private static final Golden CSV = Scenarios.corpus("001_csv_with_header");

    @Test
    void aLearnerIsNamedAsItsElementSaysAndItsSecondFragmentIsNumbered() {
        final Scenarios scenarios = new Scenarios();
        scenarios.places.put(Scenarios.LEARNER,
                new Place(DocPath.fromParts("Feeds", FEED), "DOOR-ACCESS Raw Events"));

        final StageRun first = scenarios.process(script(scenarios), doc(), stream(1L)).learned();

        assertThat(first.decision()).isInstanceOf(Promoted.class);
        final Binding binding = ((Promoted) first.decision()).binding();
        assertThat(binding.getPipeline().getName()).isEqualTo("DOOR-ACCESS Raw Events");

        // Taken back, and learned again: a new fragment (design 01 §7.3 rule 1), beside the first.
        scenarios.stage(Script.of()).retract(doc(), binding.getUuid(), "The timestamps are wrong.", "jo");
        final StageRun second = scenarios.process(script(scenarios), doc(), stream(2L)).learned();

        assertThat(second.decision()).isInstanceOf(Promoted.class);
        assertThat(((Promoted) second.decision()).binding().getPipeline().getName())
                .describedAs("the name is taken by the first, which is history and stays")
                .isEqualTo("DOOR-ACCESS Raw Events 2");
    }

    /// A folder the explorer cannot make — a factory pattern substituted with a feed name it refuses — is
    /// found out before a session opens, not after the model has been paid: once it had learned, the
    /// session would close in error, and the learner's next stream would open another. The stream waits,
    /// saying why, until a person sets the folder to something that can be made.
    @Test
    void aFolderThatCannotBeMadeIsFoundOutBeforeAnythingIsAsked() {
        final Scenarios scenarios = new Scenarios();
        scenarios.places.put(Scenarios.LEARNER, new Place(DocPath.fromParts("Feeds", "DOOR&ACCESS"), "Door"));
        final Script silent = Script.of();

        final StageRun waiting = scenarios.stage(silent).decide(doc(), Scenarios.LEARNER, stream(1L));

        assertThat(waiting.decision()).isInstanceOf(Deferred.class);
        assertThat(((Deferred) waiting.decision()).reason())
                .contains("cannot be put in the folder")
                .contains("learnedFolder");
        assertThat(silent.asked()).describedAs("nothing asked").isEmpty();
        assertThat(scenarios.attempts.forDocument(DOC, 10)).describedAs("and no session opened").isEmpty();
        assertThat(scenarios.deferrals.of(Scenarios.LEARNER, 1L)).describedAs("the stream waits").isPresent();

        // Set right, the next try learns.
        scenarios.places.put(Scenarios.LEARNER, new Place(DocPath.fromParts("Feeds", "DOOR-ACCESS"), "Door"));
        final StageRun learned = scenarios.process(script(scenarios), doc(), stream(1L)).learned();

        assertThat(learned.decision()).isInstanceOf(Promoted.class);
        assertThat(((Promoted) learned.decision()).binding().getPipeline().getName()).isEqualTo("Door");
    }

    private static Script script(final Scenarios scenarios) {
        return scenarios.script(CSV.configuration(), XSLT)
                .expect(QuestionMatcher.chain()).reply("DSParser -> XSLTFilter")
                .expect(QuestionMatcher.configuration("DSParser")).reply(Scenarios.fenced(CSV.configuration()))
                .expect(QuestionMatcher.configuration("XSLTFilter")).reply(Scenarios.fenced(XSLT));
    }

    private static ShapeshifterAiDoc doc() {
        return Scenarios.document()
                .uuid(DOC)
                .name("door-access")
                .learningMode(LearningMode.AUTOMATIC)
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsToLearn(1)
                .scorers(List.of(
                        new ScorerSetting(ScorerType.COMPILE, 0.0, 1.0, true, null),
                        new ScorerSetting(ScorerType.YIELD, 1.0, 0.5, false,
                                new YieldParameters(1.0, YieldBasis.RECORDS))))
                .build();
    }

    private static Input stream(final long id) {
        return new Input(id, FEED, "Raw Events", Map.of("Format", "CSV"), CSV.input(), "pipeline-1");
    }
}
