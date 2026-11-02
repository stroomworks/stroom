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

package stroom.shapeshifter.ai.scoring;

import stroom.shapeshifter.ai.learning.StepResult;
import stroom.shapeshifter.shared.RecordBoundary;
import stroom.shapeshifter.shared.YieldBasis;
import stroom.shapeshifter.shared.YieldParameters;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestYieldScorer {

    private static final String TWO_RECORDS = "<records xmlns=\"records:2\"><record/><record/></records>";

    private static double scored(final YieldBasis basis, final double expected, final String input) {
        return new YieldScorer().score(new YieldParameters(expected, basis),
                        new Attempted("DSParser", true, input, new StepResult(TWO_RECORDS, List.of())))
                .orElseThrow().value();
    }

    @Test
    void textThatLooksLikeMarkupIsCountedAsText() {
        // Two lines beginning with a bracketed word are not a document: by lines, two records from two lines.
        assertThat(scored(YieldBasis.LINES, 1.0, "<alice> hello\n<bob> hi\n")).isEqualTo(1.0);
        assertThat(scored(YieldBasis.LINES, 1.0,
                "<38>Sep 21 09:15:00 gate01 sshd[1]: x\n<38>Sep 21 09:15:01 gate01 sshd[2]: y\n")).isEqualTo(1.0);
    }

    @Test
    void aDocumentOfNoRecordsIsStillRecordsIn() {
        // A parser that produced an empty document leaves the transform with nothing: judged, and found wanting.
        assertThat(scored(YieldBasis.RECORDS, 1.0, "<records xmlns=\"records:2\"/>")).isEqualTo(0.0);
        assertThat(scored(YieldBasis.LINES, 0.35, "<records xmlns=\"records:2\"/>")).isEqualTo(0.0);
    }

    @Test
    void aRecordsInputIsCountedByTheBoundaryWhereOneWasSettled() {
        // The parser's XML of a JSON document has one root child, the map holding the array: by the array the
        // split named, the input is three records, and three events is one per record (A35).
        final String document = "<map xmlns=\"http://www.w3.org/2013/XSL/json\"><string key=\"source\">x</string>"
                                + "<array key=\"events\"><map/><map/><map/></array></map>";
        final String three = "<Events xmlns=\"event-logging:3\"><Event/><Event/><Event/></Events>";
        final YieldScorer scorer = new YieldScorer();
        final YieldParameters records = new YieldParameters(1.0, YieldBasis.RECORDS);
        assertThat(scorer.score(records, new Attempted("XSLTFilter", false, document,
                new StepResult(three, List.of()), RecordBoundary.ofArray("events"))).orElseThrow().value())
                .isEqualTo(1.0);
        assertThat(scorer.score(records, new Attempted("XSLTFilter", false, document,
                new StepResult(three, List.of()))).orElseThrow().value())
                .describedAs("with no boundary the root's two children are the count").isEqualTo(2.0 / 3);
        // An element boundary over nested XML: two entries under one container.
        final String nested = "<log><entries><entry/><entry/></entries></log>";
        final String two = "<Events xmlns=\"event-logging:3\"><Event/><Event/></Events>";
        assertThat(scorer.score(records, new Attempted("XSLTFilter", false, nested,
                new StepResult(two, List.of()), RecordBoundary.ofElement("entry"))).orElseThrow().value())
                .isEqualTo(1.0);
        // The output is counted by the boundary too, or the two ends disagree: a parser's XML of one map
        // holding three items is three records to the stage and would be one here. Three JSON lines in,
        // three records out, one per line.
        final String parsed = "<map xmlns=\"http://www.w3.org/2013/XSL/json\"><array key=\"events\">"
                              + "<map/><map/><map/></array></map>";
        final Attempted json = new Attempted("JSONParser", true, "{\"a\":1}\n{\"a\":2}\n{\"a\":3}\n",
                new StepResult(parsed, List.of()), RecordBoundary.ofArray("events"));
        final YieldParameters perLine = new YieldParameters(1.0, YieldBasis.LINES);
        assertThat(scorer.score(perLine, json).orElseThrow().value()).isEqualTo(1.0);
        assertThat(scorer.score(perLine, new Attempted(json.elementType(), true, json.input(), json.result()))
                .orElseThrow().value())
                .describedAs("with no boundary the root's one child is all the output it sees")
                .isEqualTo(1.0 / 3);
    }

    @Test
    void aStepWhoseInputIsRecordsIsJudgedRecordForRecordWhateverTheBasis() {
        // A lines basis describes raw input; over records in, one out per one in is expected.
        assertThat(scored(YieldBasis.LINES, 0.35, TWO_RECORDS)).isEqualTo(1.0);
        assertThat(scored(YieldBasis.RECORDS, 0.5, TWO_RECORDS)).isEqualTo(0.5);
    }
}
