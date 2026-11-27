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

import stroom.learningflow.client.component.FeedSituation.Tone;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.QualityReport;
import stroom.learningflow.shared.SupervisorLearner;
import stroom.learningflow.shared.VolumeDay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/// Works out the headline figures shown on a pipeline's Quality Assurance tab, so that a person can judge a
/// Smart Pipeline's translation without reading every record.
///
/// The figures are: its recent rolling score and how many records that covers, compared with the threshold
/// below which its Learning Flow flags it; the score it had when it went live; and how many streams are
/// waiting for a translation. Each figure has a note saying what it is based on, and a tone that highlights
/// it when it needs attention. This is a plain class so that it can be unit tested without a browser.
public final class QualityFigures {

    /// The message shown when the pipeline has no translation in use, so there is nothing to judge yet.
    static final String NOTHING_IN_USE = "No translation is in use yet, so there is nothing to judge. Its "
                                         + "Learning Status says how learning one is going.";

    private final List<Figure> figures;
    private final String message;

    private QualityFigures(final List<Figure> figures, final String message) {
        this.figures = Collections.unmodifiableList(figures);
        this.message = message;
    }

    /// Works out a Smart Pipeline's headline figures.
    ///
    /// @param learner The learner of the Smart Pipeline element.
    /// @param doc     The settings of the Learning Flow it learns under, or null if not known, in which case the
    ///                flagging threshold is not mentioned.
    /// @return The figures. If no translation is in use, only the waiting streams figure is given, with a
    ///         message.
    public static QualityFigures of(final SupervisorLearner learner, final LearningFlowDoc doc) {
        final List<Figure> figures = new ArrayList<>();
        if (learner.getBindingUuid() == null) {
            figures.add(waiting(learner));
            return new QualityFigures(figures, NOTHING_IN_USE);
        }
        figures.add(lately(learner, doc));
        figures.add(whenLearned(learner));
        figures.add(waiting(learner));
        return new QualityFigures(figures, null);
    }

    /// Describes the volume of data translated over the period covered, in a sentence.
    ///
    /// @param days The days, oldest first.
    /// @return E.g. "1,204 records in 37 streams over the last 14 days. 18 of those records were declared
    ///         skipped by the translation."
    public static String volume(final List<VolumeDay> days) {
        long streams = 0;
        long records = 0;
        long skips = 0;
        for (final VolumeDay day : days) {
            streams += day.getStreams();
            records += day.getRecords();
            skips += day.getSkips();
        }
        final String over = " over the last " + PlainWords.count(days.size(), "day") + ".";
        if (streams == 0) {
            return "Nothing translated" + over;
        }
        if (records == 0) {
            return PlainWords.count(streams, "stream") + " translated" + over + " Their records weren't counted.";
        }
        // A declared skip is a record the translation says it deliberately did not turn into an event. There
        // is no limit on how many it may declare, so the count is shown here for a person to judge.
        return PlainWords.count(records, "record") + " in " + PlainWords.count(streams, "stream") + over
               + (skips == 0
                ? ""
                : " " + PlainWords.number(skips) + " of those records were declared skipped by the "
                  + "translation.");
    }

    /// What the regression set is, and what it holds a new translation to: the section's subtitle.
    ///
    /// @param gate Whether the regression check is a gate, so that a new translation is held to it; null where
    ///             the Learning Flow's settings can't be read, when it isn't said.
    /// @return The description, in a sentence or two.
    public static String regressionNote(final Boolean gate) {
        final String what = "The records the translation in use was learned and checked on, each with the output "
                            + "it was accepted with.";
        if (Boolean.TRUE.equals(gate)) {
            return what + " A new translation must still handle every one of them, and none that translated may "
                   + "become a marked Unknown.";
        }
        if (Boolean.FALSE.equals(gate)) {
            return what + " The regression check isn't a gate, so a new translation isn't held to them.";
        }
        return what;
    }

    /// How many records the regression set holds, by what each became (its cardinality), and how many are shown.
    ///
    /// @param report The pipeline's report, or null where it hasn't been read.
    /// @param shown  How many of its records are shown: the newest, as many as the server gives.
    /// @return E.g. "6 records: 4 translated, 1 marked Unknown, 1 with no event. 3 kinds of record."; null where
    ///         the report hasn't been read.
    public static String regressionSummary(final QualityReport report, final int shown) {
        if (report == null) {
            return null;
        }
        final int total = report.getTranslated() + report.getUnknown() + report.getNoOutput();
        if (report.getServingBindingUuid() == null || total == 0) {
            return "The regression set is empty: no translation is in use, or it keeps no records.";
        }
        final StringBuilder summary = new StringBuilder(PlainWords.count(total, "record"))
                .append(": ").append(PlainWords.number(report.getTranslated())).append(" translated, ")
                .append(PlainWords.number(report.getUnknown())).append(" marked Unknown, ")
                .append(PlainWords.number(report.getNoOutput())).append(" with no event.");
        if (report.getDistinctTargets() > 0) {
            summary.append(' ').append(PlainWords.count(report.getDistinctTargets(), "kind")).append(" of record.");
        }
        if (shown > 0 && shown < total) {
            summary.append(" The newest ").append(PlainWords.number(shown)).append(" are shown.");
        }
        return summary.toString();
    }

    /// @param index The record shown, from 0.
    /// @param count How many records there are to step through.
    /// @return Which record is shown, e.g. "Record 3 of 6".
    public static String position(final int index, final int count) {
        return "Record " + PlainWords.number(index + 1L) + " of " + PlainWords.number(count);
    }

    /// @return The figures, in the order they are shown.
    public List<Figure> getFigures() {
        return figures;
    }

    /// @return The message shown when there is no translation in use, or null if there is one.
    public String getMessage() {
        return message;
    }

    private static Figure lately(final SupervisorLearner learner, final LearningFlowDoc doc) {
        final String flag = doc == null
                ? ""
                : " Its Learning Flow flags a translation in use below "
                  + PlainWords.percent(doc.getRelearnThreshold()) + ".";
        if (learner.getRollingScore() == null) {
            return new Figure("Score lately", "Not scored yet",
                    "No record has been translated since it went live." + flag, Tone.IDLE);
        }
        final String over = "Over the last " + PlainWords.count(learner.getRecords(), "record") + " translated.";
        return learner.isBelowThreshold()
                ? new Figure("Score lately", PlainWords.percent(learner.getRollingScore()),
                over + flag + " It is below that, so it is flagged; it goes on translating.", Tone.WARNING)
                : new Figure("Score lately", PlainWords.percent(learner.getRollingScore()), over + flag, Tone.GOOD);
    }

    private static Figure whenLearned(final SupervisorLearner learner) {
        return learner.getPromotedScore() == null
                ? new Figure("Score when it went live", "Not known", "It went live without a score.",
                Tone.IDLE)
                : new Figure("Score when it went live", PlainWords.percent(learner.getPromotedScore()),
                        "On the stream it was learned from.", Tone.IDLE);
    }

    private static Figure waiting(final SupervisorLearner learner) {
        if (learner.getWaiting() == 0) {
            return new Figure("Waiting streams", "None", "Every stream it has been given has been translated.",
                    Tone.IDLE);
        }
        return new Figure("Waiting streams", PlainWords.number(learner.getWaiting()),
                learner.isRaised()
                        ? "Streams no translation could take, waiting for it to be learned again; the longest is "
                          + "overdue."
                        : "Streams no translation could take, waiting for it to be learned again.",
                learner.isRaised()
                        ? Tone.WARNING
                        : Tone.INFO);
    }


    // --------------------------------------------------------------------------------


    /// One figure: its label, its value, a note on what it is based on, and a tone saying whether it needs
    /// attention.
    public static final class Figure {

        private final String label;
        private final String value;
        private final String note;
        private final Tone tone;

        Figure(final String label, final String value, final String note, final Tone tone) {
            this.label = label;
            this.value = value;
            this.note = note;
            this.tone = tone;
        }

        /// @return The figure's label, e.g. "Score lately".
        public String getLabel() {
            return label;
        }

        /// @return The figure's value, e.g. "98%".
        public String getValue() {
            return value;
        }

        /// @return A sentence or two on what the figure is based on.
        public String getNote() {
            return note;
        }

        /// @return The tone, e.g. warning when the figure needs attention or good when all is well.
        public Tone getTone() {
            return tone;
        }

        /// @return Whether its note is shown on the tile: where it says something to act on or worth knowing (a
        ///         warning, a danger, or information such as being on trial). Otherwise the note is only its
        ///         tooltip, as the value says enough.
        public boolean isNoteShown() {
            return tone == Tone.WARNING || tone == Tone.DANGER || tone == Tone.INFO;
        }
    }
}
