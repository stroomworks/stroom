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

package stroom.shapeshifter.ai.fragment;

import stroom.docref.DocRef;
import stroom.shapeshifter.ai.learning.LearnedStep;
import stroom.shapeshifter.ai.learning.StepResult;
import stroom.shapeshifter.ai.scoring.Attempted;
import stroom.shapeshifter.ai.scoring.Verdict;
import stroom.shapeshifter.ai.transformation.XsltStep;
import stroom.shapeshifter.shared.RecordBoundary;
import stroom.util.shared.DocPath;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A written fragment run the way the pipeline will run it (§12 item 25): from the `SplitFilter` on, one
 * record at a time — and only one filter, however many elements follow it.
 */
class TestStandInFragmentRunner {

    private static final DocPath FOLDER = DocPath.fromParts("Shapeshifter", "SYSLOG");
    private static final Verdict UNSCORED = new Verdict(List.of(), 1.0);
    private static final String TWO_RECORDS = "<a><b><record>1</record><record>2</record></b></a>";

    /// One event per record, whatever the record says: what matters here is how many documents the
    /// element was given, not what it made of them.
    private static final String ONE_EVENT = """
            <xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="2.0">
              <xsl:template match="/"><Events><Event/></Events></xsl:template>
            </xsl:stylesheet>""";

    /// Counts the events in the document it was given, which says how the element before it was run.
    private static final String COUNT_EVENTS = """
            <xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="2.0">
              <xsl:template match="/"><Out><count><xsl:value-of select="count(//Event)"/></count></Out>
              </xsl:template>
            </xsl:stylesheet>""";

    @Test
    void aSecondTransformIsGivenWhatTheFirstWroteForEachRecordAndNotTheWholeOfIt() {
        // A repeated element is a legal chain, and a fragment carries one SplitFilter, not one per
        // element: the filter stands in front of the first transform, and the second is handed each
        // record's output as the pipeline hands it on. Run instead at the boundary's own depth, the
        // second element would be re-splitting a document of events where the records used to be.
        final ContentStores stores = new ContentStores();
        final List<LearnedStep> chain = List.of(
                new LearnedStep(new XsltStep(), ONE_EVENT, new StepResult("<Events/>", List.of()), UNSCORED),
                new LearnedStep(new XsltStep(), COUNT_EVENTS, new StepResult("<Out/>", List.of()), UNSCORED));
        final RecordBoundary boundary = RecordBoundary.ofElement("record").atDepth(2);
        final DocRef fragment = stores.writer().write(FOLDER, "two-pass", chain, boundary);

        final List<Attempted> steps = new StandInFragmentRunner(stores.pipelines, stores.stackLoader,
                stores.textConverters, stores.xslts, List.of(new XsltStep()))
                .run(fragment, TWO_RECORDS, boundary);

        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).result().output().replaceAll("\\s+", ""))
                .describedAs("the first transform is given each of the two records")
                .contains("<Event/><Event/>");
        assertThat(steps.get(1).result().output().replaceAll("\\s+", ""))
                .describedAs("and the second each of the two events, one at a time")
                .contains("<count>1</count><count>1</count>");
    }
}
