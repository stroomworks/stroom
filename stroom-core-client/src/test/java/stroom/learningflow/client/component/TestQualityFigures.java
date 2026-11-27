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

package stroom.learningflow.client.component;

import stroom.docref.DocRef;
import stroom.learningflow.client.component.FeedSituation.Tone;
import stroom.learningflow.client.component.QualityFigures.Figure;
import stroom.learningflow.shared.LearnerRef;
import stroom.learningflow.shared.LearnerState;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.QualityReport;
import stroom.learningflow.shared.SupervisorLearner;
import stroom.learningflow.shared.VolumeDay;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class TestQualityFigures {

    private static final DocRef DOC_REF = new DocRef(LearningFlowDoc.TYPE, "ss-1", "Firewall logs");
    private static final DocRef FRAGMENT = new DocRef("Pipeline", "f-1", "Firewall pipeline learned");
    private static final LearningFlowDoc DOC = LearningFlowDoc.builder()
            .uuid("ss-1")
            .relearnThreshold(0.8)
            .minRecordsToLearn(20)
            .build();

    @Test
    void aTranslationInUseIsJudgedByItsScoresAndWaitingStreams() {
        final QualityFigures figures = QualityFigures.of(serving(0.98, 0.95, 1204, false, 0, false), DOC);

        assertThat(figures.getMessage()).isNull();
        assertThat(figures.getFigures()).extracting(Figure::getLabel)
                .containsExactly("Score lately", "Score when it went live", "Waiting streams");
        assertThat(figures.getFigures()).extracting(Figure::getValue)
                .containsExactly("98%", "95%", "None");
        assertThat(figures.getFigures().get(0).getNote())
                .isEqualTo("Over the last 1,204 records translated. Its Learning Flow flags a translation in use "
                           + "below 80%.");
        assertThat(figures.getFigures().get(0).getTone()).isEqualTo(Tone.GOOD);
        assertThat(figures.getFigures().get(1).getNote()).isEqualTo("On the stream it was learned from.");
    }

    @Test
    void aScoreBelowTheFlagCallsForAttention() {
        final Figure lately = QualityFigures.of(serving(0.62, 0.95, 300, false, 0, true), DOC)
                .getFigures().get(0);
        assertThat(lately.getValue()).isEqualTo("62%");
        assertThat(lately.getTone()).isEqualTo(Tone.WARNING);
        assertThat(lately.getNote()).contains("It is below that, so it is flagged");
    }

    @Test
    void aTranslationNotScoredSinceItWentLiveSaysSo() {
        final Figure lately = QualityFigures.of(serving(null, 0.95, 0, false, 0, false), DOC)
                .getFigures().get(0);
        assertThat(lately.getValue()).isEqualTo("Not scored yet");
        assertThat(lately.getTone()).isEqualTo(Tone.IDLE);
    }

    @Test
    void overdueWaitingStreamsCallForAttention() {
        final Figure waiting = QualityFigures.of(serving(0.97, 0.95, 400, true, 3, false), DOC)
                .getFigures().get(2);
        assertThat(waiting.getValue()).isEqualTo("3");
        assertThat(waiting.getTone()).isEqualTo(Tone.WARNING);
        assertThat(waiting.getNote()).contains("overdue");
    }

    @Test
    void withoutItsLearningFlowsSettingsTheLinesAreNotSaid() {
        final QualityFigures figures = QualityFigures.of(serving(0.98, 0.95, 10, false, 0, false), null);
        assertThat(figures.getFigures().get(0).getNote()).isEqualTo("Over the last 10 records translated.");
        assertThat(figures.getFigures().get(1).getNote()).isEqualTo("On the stream it was learned from.");
    }

    @Test
    void nothingInUseHasNothingToJudgeButWhatWaits() {
        final SupervisorLearner learning = new SupervisorLearner(new LearnerRef("p-1", "learningFlow"), null,
                DOC_REF, LearnerState.LEARNING, null, null, null, null, null, 0, 2, null, false, null, 0,
                false, false);
        final QualityFigures figures = QualityFigures.of(learning, DOC);
        assertThat(figures.getMessage()).isEqualTo(QualityFigures.NOTHING_IN_USE);
        assertThat(figures.getFigures()).singleElement()
                .satisfies(figure -> assertThat(figure.getValue()).isEqualTo("2"));
    }

    @Test
    void theVolumeIsSaidInRecordsAndStreams() {
        assertThat(QualityFigures.volume(List.of(day(0, 3L, 1200L), day(1, 1L, 4L))))
                .isEqualTo("1,204 records in 4 streams over the last 2 days.");
        assertThat(QualityFigures.volume(List.of(day(0, 0L, 0L))))
                .isEqualTo("Nothing translated over the last 1 day.");
        assertThat(QualityFigures.volume(List.of(day(0, 2L, 0L))))
                .isEqualTo("2 streams translated over the last 1 day. Their records weren't counted.");
    }

    @Test
    void theVolumeSaysHowManyRecordsTheTranslationDeclaredItSkipped() {
        // There is no limit on how many records a translation may declare skipped, so showing the count is
        // the only safeguard against a translation quietly skipping the records it finds awkward
        assertThat(QualityFigures.volume(List.of(day(0, 3L, 1200L, 12L), day(1, 1L, 4L, 6L))))
                .isEqualTo("1,204 records in 4 streams over the last 2 days. 18 of those records were "
                           + "declared skipped by the translation.");
    }

    @Test
    void aTranslationThatSkippedNothingSaysNothingAboutSkips() {
        assertThat(QualityFigures.volume(List.of(day(0, 3L, 1200L, 0L))))
                .isEqualTo("1,200 records in 3 streams over the last 1 day.");
    }

    @Test
    void theRegressionSummaryCountsWhatEachRecordBecame() {
        final QualityReport report = new QualityReport(List.of(), "b-1", 4, 1, 1, 3);
        assertThat(QualityFigures.regressionSummary(report, 6))
                .isEqualTo("6 records: 4 translated, 1 marked Unknown, 1 with no event. 3 kinds of record.");
        final QualityReport oneKind = new QualityReport(List.of(), "b-1", 1, 0, 0, 1);
        assertThat(QualityFigures.regressionSummary(oneKind, 1))
                .describedAs("counts of nought are said, not left out")
                .isEqualTo("1 record: 1 translated, 0 marked Unknown, 0 with no event. 1 kind of record.");
        final QualityReport noKinds = new QualityReport(List.of(), "b-1", 2, 0, 0, 0);
        assertThat(QualityFigures.regressionSummary(noKinds, 2))
                .describedAs("no kinds settled are not said")
                .isEqualTo("2 records: 2 translated, 0 marked Unknown, 0 with no event.");
    }

    @Test
    void aRegressionSetLargerThanShownSaysOnlyTheNewestAreShown() {
        final QualityReport report = new QualityReport(List.of(), "b-1", 1440, 8, 2, 0);
        assertThat(QualityFigures.regressionSummary(report, 20))
                .isEqualTo("1,450 records: 1,440 translated, 8 marked Unknown, 2 with no event. The newest 20 are "
                           + "shown.");
        assertThat(QualityFigures.regressionSummary(report, 0))
                .describedAs("records not shown, e.g. refused, aren't said to be the newest")
                .doesNotContain("newest");
    }

    @Test
    void anEmptyRegressionSetOrNoReportSaysSo() {
        final String empty = "The regression set is empty: no translation is in use, or it keeps no records.";
        assertThat(QualityFigures.regressionSummary(new QualityReport(List.of(), null, 0, 0, 0, 0), 0))
                .isEqualTo(empty);
        assertThat(QualityFigures.regressionSummary(new QualityReport(List.of(), "b-1", 0, 0, 0, 0), 0))
                .isEqualTo(empty);
        assertThat(QualityFigures.regressionSummary(null, 6))
                .describedAs("nothing is said until the report is read")
                .isNull();
    }

    @Test
    void theRegressionNoteSaysWhatItHoldsANewTranslationTo() {
        assertThat(QualityFigures.regressionNote(true))
                .isEqualTo("The records the translation in use was learned and checked on, each with the output it "
                           + "was accepted with. A new translation must still handle every one of them, and none "
                           + "that translated may become a marked Unknown.");
        assertThat(QualityFigures.regressionNote(false))
                .endsWith("The regression check isn't a gate, so a new translation isn't held to them.");
        assertThat(QualityFigures.regressionNote(null))
                .describedAs("without the Learning Flow's settings, nothing is said of the check")
                .endsWith("each with the output it was accepted with.");
    }

    @Test
    void aFigureShowsItsNoteOnlyWhereItSaysSomethingWorthKnowing() {
        final List<Figure> figures = QualityFigures.of(serving(0.42, 0.95, 1204, false, 2, true), DOC)
                .getFigures();
        assertThat(figures).extracting(Figure::getLabel, Figure::isNoteShown)
                .containsExactly(
                        tuple("Score lately", true),
                        tuple("Score when it went live", false),
                        tuple("Waiting streams", true));
        final List<Figure> quiet = QualityFigures.of(serving(0.98, 0.97, 1204, false, 0, false), DOC)
                .getFigures();
        assertThat(quiet).extracting(Figure::isNoteShown).containsOnly(false);
    }

    @Test
    void thePositionCountsFromOne() {
        assertThat(QualityFigures.position(0, 6)).isEqualTo("Record 1 of 6");
        assertThat(QualityFigures.position(5, 6)).isEqualTo("Record 6 of 6");
        assertThat(QualityFigures.position(999, 1200)).isEqualTo("Record 1,000 of 1,200");
    }

    private static VolumeDay day(final int day, final Long streams, final Long records) {
        return day(day, streams, records, 0L);
    }

    /// @param day     The day's number, counted from zero.
    /// @param streams How many streams were translated that day.
    /// @param records How many records those streams held.
    /// @param skips   How many of those records the translation declared skipped.
    /// @return The day's volume.
    private static VolumeDay day(final int day, final Long streams, final Long records, final Long skips) {
        return new VolumeDay(day * 86_400_000L, streams, records, skips);
    }

    private static SupervisorLearner serving(final Double lately,
                                             final Double promoted,
                                             final int records,
                                             final boolean raised,
                                             final int waiting,
                                             final boolean below) {
        return new SupervisorLearner(new LearnerRef("p-1", "learningFlow"), null, DOC_REF, LearnerState.SERVING,
                null, "b-1", FRAGMENT, promoted, lately, records, waiting, null, raised, null, 0,
                false, below);
    }
}
