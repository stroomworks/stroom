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
    void theCardinalityIsWhatTheKeptRecordsBecame() {
        final QualityReport report = new QualityReport(List.of(), "b-1", 12, 2, 1, 3);
        assertThat(QualityFigures.cardinality(report)).extracting(Figure::getLabel, Figure::getValue)
                .containsExactly(
                        tuple("Translated", "12"),
                        tuple("Marked Unknown", "2"),
                        tuple("Nothing", "1"),
                        tuple("Kinds of record", "3"));
        assertThat(QualityFigures.cardinality(report).get(1).getTone())
                .describedAs("Unknowns call for attention")
                .isEqualTo(Tone.WARNING);
        assertThat(QualityFigures.cardinalityNote(report))
                .isEqualTo("Of the 15 records the translation in use was learned and checked on, what each became.");
    }

    @Test
    void noUnknownsAndNoKindsSettledAreQuiet() {
        final QualityReport report = new QualityReport(List.of(), "b-1", 4, 0, 0, 0);
        assertThat(QualityFigures.cardinality(report)).extracting(Figure::getLabel)
                .containsExactly("Translated", "Marked Unknown", "Nothing");
        assertThat(QualityFigures.cardinality(report).get(1).getTone()).isEqualTo(Tone.IDLE);
    }

    @Test
    void nothingInUseOrNothingKeptHasNoCardinality() {
        final QualityReport none = new QualityReport(List.of(), null, 0, 0, 0, 0);
        assertThat(QualityFigures.cardinality(none)).isEmpty();
        assertThat(QualityFigures.cardinalityNote(none)).isNull();
        final QualityReport empty = new QualityReport(List.of(), "b-1", 0, 0, 0, 0);
        assertThat(QualityFigures.cardinality(empty)).isEmpty();
        assertThat(QualityFigures.cardinalityNote(empty)).contains("keeps no records");
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
