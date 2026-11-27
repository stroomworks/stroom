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

package stroom.learningflow.scenario;

import stroom.learningflow.extraction.ExtractionCorpus.Golden;
import stroom.learningflow.shared.Binding;
import stroom.learningflow.shared.Check;
import stroom.learningflow.shared.CheckSetting;
import stroom.learningflow.shared.CheckState;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.LearningMode;
import stroom.learningflow.shared.YieldBasis;
import stroom.learningflow.shared.YieldParameters;
import stroom.learningflow.stage.Decision.Deferred;
import stroom.learningflow.stage.Decision.Promoted;
import stroom.learningflow.stage.Input;
import stroom.learningflow.stage.Places.Place;
import stroom.learningflow.stage.StageRun;
import stroom.util.shared.DocPath;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests where learned content is written and what it is named.
///
/// A learner's fragment is named and placed as its element's settings say, which a person sets from
/// patterns. A second fragment for the same learner takes the name followed by the first free number.
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

        // Retracted and learned again: a first learning writes a new fragment, beside the first one.
        scenarios.promotion().retract(doc(), binding.getUuid(), "The timestamps are wrong.", "jo");
        final StageRun second = scenarios.process(script(scenarios), doc(), stream(2L)).learned();

        assertThat(second.decision()).isInstanceOf(Promoted.class);
        assertThat(((Promoted) second.decision()).binding().getPipeline().getName())
                .describedAs("the name is taken by the first, which is history and stays")
                .isEqualTo("DOOR-ACCESS Raw Events 2");
    }

    /// A folder that cannot be created (for example a factory pattern filled in with a feed name the explorer
    /// rejects) is detected before a session opens, not after the model has been paid. Otherwise the session
    /// would fail once it had learned, and the learner's next stream would open another. The stream waits,
    /// saying why, until a person sets the folder to something that can be created.
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

        // Once the folder is corrected, the next try learns.
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

    private static LearningFlowDoc doc() {
        return Scenarios.document()
                .uuid(DOC)
                .name("door-access")
                .learningMode(LearningMode.AUTOMATIC)
                .allowedElements(List.of("DSParser", "XSLTFilter"))
                .minRecordsToLearn(1)
                .checks(List.of(
                        new CheckSetting(Check.COMPILE, CheckState.GATE, 1.0, null),
                        CheckSetting.of(Check.COVERAGE, CheckState.OFF),
                        new CheckSetting(Check.YIELD, CheckState.ADVICE, 0.5,
                                new YieldParameters(1.0, YieldBasis.RECORDS)),
                        CheckSetting.of(Check.WHOLENESS, CheckState.GATE),
                        CheckSetting.of(Check.CONFORMANCE, CheckState.OFF),
                        CheckSetting.of(Check.QUALITY, CheckState.OFF),
                        CheckSetting.of(Check.RULES, CheckState.OFF),
                        CheckSetting.of(Check.PRESERVATION, CheckState.GATE),
                        CheckSetting.of(Check.FIDELITY, CheckState.GATE),
                        CheckSetting.of(Check.REGRESSION, CheckState.GATE)))
                .build();
    }

    private static Input stream(final long id) {
        return new Input(id, FEED, "Raw Events", Map.of("Format", "CSV"), CSV.input(), "pipeline-1");
    }
}
