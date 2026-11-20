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

package stroom.shapeshifter.client.component;

import stroom.shapeshifter.client.component.FeedSituation.Tone;
import stroom.shapeshifter.shared.QualityReport;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.shapeshifter.shared.SupervisorLearner;
import stroom.shapeshifter.shared.VolumeDay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/// The overall figures a QA person judges a Smart Pipeline's translation by, on its Quality Assurance tab,
/// without reading every record: what it scores lately and over how many records, against the line its Learning
/// Flow flags it below; what it scored when it went live, against the pass mark; whether it is trusted or still on
/// trial; and how many streams wait for a translation. Each is a figure with a sentence saying what it rests on,
/// and a tone where it calls for attention. A plain class, so that it is tested without a browser.
public final class QualityFigures {

    /// Said where the pipeline has no translation in use, so has nothing to judge yet.
    static final String NOTHING_IN_USE = "No translation is in use yet, so there is nothing to judge. Its "
                                         + "Learning Status says how learning one is going.";

    private final List<Figure> figures;
    private final String message;

    private QualityFigures(final List<Figure> figures, final String message) {
        this.figures = Collections.unmodifiableList(figures);
        this.message = message;
    }

    /// Works out a Smart Pipeline's figures.
    ///
    /// @param learner Its learner row.
    /// @param doc     The settings of the Learning Flow it learns under, or null where not known, when the lines
    ///                they draw aren't said.
    /// @return Its figures, or none and a message where it has no translation in use.
    public static QualityFigures of(final SupervisorLearner learner, final ShapeshifterAiDoc doc) {
        final List<Figure> figures = new ArrayList<>();
        if (learner.getBindingUuid() == null) {
            figures.add(waiting(learner));
            return new QualityFigures(figures, NOTHING_IN_USE);
        }
        figures.add(lately(learner, doc));
        figures.add(whenLearned(learner, doc));
        figures.add(trust(learner, doc));
        figures.add(waiting(learner));
        return new QualityFigures(figures, null);
    }

    /// The volume over the window, in a sentence.
    ///
    /// @param days The days, oldest first.
    /// @return E.g. "1,204 records in 37 streams over the last 14 days."
    public static String volume(final List<VolumeDay> days) {
        long streams = 0;
        long records = 0;
        for (final VolumeDay day : days) {
            streams += day.getStreams();
            records += day.getRecords();
        }
        final String over = " over the last " + PlainWords.count(days.size(), "day") + ".";
        if (streams == 0) {
            return "Nothing translated" + over;
        }
        return records == 0
                ? PlainWords.count(streams, "stream") + " translated" + over + " Their records weren't counted."
                : PlainWords.count(records, "record") + " in " + PlainWords.count(streams, "stream") + over;
    }

    /// The cardinality of the translation in use: of the records it was learned and checked on, what they
    /// became, and how many kinds of record it settled.
    ///
    /// @param report The pipeline's report.
    /// @return The figures; none where no translation is in use or it keeps no records.
    public static List<Figure> cardinality(final QualityReport report) {
        final int kept = report.getTranslated() + report.getUnknown() + report.getNone();
        if (report.getBindingUuid() == null || kept == 0) {
            return List.of();
        }
        final List<Figure> figures = new ArrayList<>();
        figures.add(new Figure("Translated", PlainWords.number(report.getTranslated()),
                "Records that became a real event.", Tone.GOOD));
        figures.add(new Figure("Marked Unknown", PlainWords.number(report.getUnknown()),
                report.getUnknown() == 0
                        ? "No record became a marked Unknown event."
                        : "Records that became a marked Unknown event: kept, but not yet translated.",
                report.getUnknown() == 0
                        ? Tone.IDLE
                        : Tone.WARNING));
        figures.add(new Figure("Nothing", PlainWords.number(report.getNone()),
                "Records that became no event, e.g. a header line.", Tone.IDLE));
        if (report.getKinds() > 0) {
            figures.add(new Figure("Kinds of record", PlainWords.number(report.getKinds()),
                    "The kinds of record it settled, each with the event it should become.", Tone.IDLE));
        }
        return figures;
    }

    /// @param report The pipeline's report.
    /// @return What the cardinality figures are of, in a sentence; null where there are none.
    public static String cardinalityNote(final QualityReport report) {
        final int kept = report.getTranslated() + report.getUnknown() + report.getNone();
        if (report.getBindingUuid() == null) {
            return null;
        }
        return kept == 0
                ? "The translation in use keeps no records it was learned and checked on."
                : "Of the " + PlainWords.count(kept, "record") + " the translation in use was learned and "
                  + "checked on, what each became.";
    }

    /// @return The figures, in the order they are shown.
    public List<Figure> getFigures() {
        return figures;
    }

    /// @return What is said in place of the figures of a translation, or null where there is one.
    public String getMessage() {
        return message;
    }

    private static Figure lately(final SupervisorLearner learner, final ShapeshifterAiDoc doc) {
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

    private static Figure whenLearned(final SupervisorLearner learner, final ShapeshifterAiDoc doc) {
        final String passMark = doc == null
                ? ""
                : " The pass mark is " + PlainWords.percent(doc.getPromotionFloor()) + ".";
        return learner.getPromotedScore() == null
                ? new Figure("Score when it went live", "Not known", "It went live without a score." + passMark,
                Tone.IDLE)
                : new Figure("Score when it went live", PlainWords.percent(learner.getPromotedScore()),
                        "On the stream it was learned from." + passMark, Tone.IDLE);
    }

    private static Figure trust(final SupervisorLearner learner, final ShapeshifterAiDoc doc) {
        if (learner.isProvisional()) {
            final String enough = doc == null
                    ? "enough clean records have"
                    : PlainWords.count(doc.getMinRecordsToLearn(), "clean record") + " have";
            return new Figure("Trust", "On trial",
                    "It was learned from fewer records than its Learning Flow asks for. It is trusted once "
                    + enough + " passed through it, or someone accepts it on its Learning Status.", Tone.INFO);
        }
        return new Figure("Trust", "Trusted", "It was learned from enough records, or accepted.", Tone.GOOD);
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


    /// One figure: what it is, its value, what it rests on, and whether it calls for attention.
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

        /// @return What it is, e.g. "Score lately".
        public String getLabel() {
            return label;
        }

        /// @return Its value, e.g. "98%".
        public String getValue() {
            return value;
        }

        /// @return What it rests on, in a sentence or two.
        public String getNote() {
            return note;
        }

        /// @return Whether it calls for attention: warning where it does, good where all is well.
        public Tone getTone() {
            return tone;
        }
    }
}
