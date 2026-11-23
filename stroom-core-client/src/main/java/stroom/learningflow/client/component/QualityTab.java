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
import stroom.learningflow.client.component.QualityFigures.Figure;
import stroom.learningflow.shared.QualityReport;
import stroom.learningflow.shared.QualitySample;
import stroom.learningflow.shared.SupervisorAttempt;
import stroom.learningflow.shared.VolumeDay;
import stroom.util.client.DataGridUtil;
import stroom.widget.button.client.Button;
import stroom.widget.component.client.AriaPanel;
import stroom.widget.component.client.Component;
import stroom.widget.datagrid.client.DataGrid;
import stroom.widget.selection.client.SelectionBox;
import stroom.widget.text.client.Html;
import stroom.widget.text.client.InlineLabel;
import stroom.widget.text.client.Label;

import com.google.gwt.core.client.GWT;
import com.google.gwt.safehtml.shared.SafeHtmlUtils;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Widget;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.LongFunction;

/// A Smart Pipeline's **Quality Assurance** tab, for a QA person judging one pipeline's translated output on its
/// own: which of its Smart Pipeline elements, where it has more than one; the overall figures its translation is
/// judged by ([QualityFigures]), each with what it rests on and coloured where it calls for attention; its
/// **volume** day by day, as a chart ([VolumeChart]) and, folded, a table; **what its records became** (its
/// cardinality); **samples** of those records beside what they became; and each learning run's score, newest
/// first. Where there is nothing to judge, a sentence says why.
/// [QualityTabController] fills it.
public class QualityTab extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    FlowPanel chooser;
    @UiField
    SelectionBox<PipelineAiElement> element;
    @UiField
    Label nothing;
    @UiField
    FlowPanel content;
    @UiField
    Label message;
    @UiField
    AriaPanel figures;
    @UiField
    Label volumeSummary;
    @UiField
    Html volumeChart;
    @UiField
    DataGrid<VolumeDay> days;
    @UiField
    Label cardinalityNote;
    @UiField
    AriaPanel cardinality;
    @UiField
    Label samplesNote;
    @UiField
    AriaPanel samples;
    @UiField
    DataGrid<SupervisorAttempt> runs;

    private Consumer<PipelineAiElement> onElement = chosen -> {
    };
    private LongFunction<String> dates = String::valueOf;
    private LongFunction<String> dayLabels = String::valueOf;

    /// Makes the tab, empty.
    public QualityTab() {
        initWidget(BINDER.createAndBindUi(this));
        element.setOnChange(chosen -> onElement.accept(chosen));
        days.addResizableColumn(
                DataGridUtil.textColumnBuilder((VolumeDay day) -> dayLabels.apply(day.getDayMs())).build(),
                DataGridUtil.headingBuilder("Day").build(),
                120);
        days.addResizableColumn(
                DataGridUtil.textColumnBuilder((VolumeDay day) -> PlainWords.number(day.getStreams())).build(),
                DataGridUtil.headingBuilder("Streams").build(),
                100);
        days.addAutoResizableColumn(
                DataGridUtil.textColumnBuilder((VolumeDay day) -> PlainWords.number(day.getRecords())).build(),
                DataGridUtil.headingBuilder("Records").build(),
                100);
        runs.addResizableColumn(
                DataGridUtil.textColumnBuilder((SupervisorAttempt run) -> dates.apply(run.getCreateTimeMs())).build(),
                DataGridUtil.headingBuilder("Started").build(),
                180);
        runs.addResizableColumn(
                DataGridUtil.textColumnBuilder((SupervisorAttempt run) -> run.getStatus() == null
                        ? ""
                        : run.getStatus().getDisplayValue()).build(),
                DataGridUtil.headingBuilder("Result").build(),
                140);
        runs.addResizableColumn(
                DataGridUtil.textColumnBuilder((SupervisorAttempt run) -> run.getScore() == null
                        ? ""
                        : PlainWords.percent(run.getScore())).build(),
                DataGridUtil.headingBuilder("Score").build(),
                80);
        runs.addResizableColumn(
                DataGridUtil.textColumnBuilder((SupervisorAttempt run) -> PlainWords.number(run.getTokensSpent()))
                        .build(),
                DataGridUtil.headingBuilder("Tokens").build(),
                100);
        runs.addAutoResizableColumn(
                DataGridUtil.textColumnBuilder((SupervisorAttempt run) -> run.getDecision() == null
                        ? ""
                        : run.getDecision()).build(),
                DataGridUtil.headingBuilder("Decision").build(),
                300);
    }

    /// @param dates Says when a run started, as the user likes dates, from its time in milliseconds.
    public void setDateFormat(final LongFunction<String> dates) {
        this.dates = dates;
    }

    /// @param dayLabels Names a day (UTC) from its start in milliseconds, e.g. "17 Nov".
    public void setDayFormat(final LongFunction<String> dayLabels) {
        this.dayLabels = dayLabels;
    }

    /// Shows the volume and what the records of the translation in use became.
    ///
    /// @param report The pipeline's report.
    public void showReport(final QualityReport report) {
        final List<VolumeDay> window = report.getDays() == null
                ? List.of()
                : report.getDays();
        volumeSummary.setText(QualityFigures.volume(window));
        volumeChart.setHTML(SafeHtmlUtils.fromTrustedString(VolumeChart.draw(window, dayLabels)));
        days.setRowData(0, window);
        days.setRowCount(window.size(), true);
        final String note = QualityFigures.cardinalityNote(report);
        cardinalityNote.setText(note == null
                ? "No translation is in use, so there are no records to count."
                : note);
        cardinality.clear();
        for (final Figure figure : QualityFigures.cardinality(report)) {
            cardinality.add(figure(figure));
        }
        cardinality.setVisible(cardinality.getWidgetCount() > 0);
    }

    /// Shows records the translation in use was learned and checked on, beside what they became.
    ///
    /// @param shown The samples, newest first.
    public void showSamples(final List<QualitySample> shown) {
        samples.clear();
        for (final QualitySample sample : shown) {
            samples.add(sample(sample));
        }
        samples.setVisible(!shown.isEmpty());
        samplesNote.setText(shown.isEmpty()
                ? "There are no samples: no translation is in use, or it keeps no records."
                : PlainWords.count(shown.size(), "record") + ", newest first.");
    }

    /// Says why no samples are shown, e.g. that the person may not view the data.
    ///
    /// @param why What to say.
    public void showSamplesNote(final String why) {
        samples.clear();
        samples.setVisible(false);
        samplesNote.setText(why);
    }

    /// The pipeline's Smart Pipeline elements: the choice of them is offered only where there is more than one.
    ///
    /// @param elements Its Smart Pipeline elements.
    /// @param chosen   The one shown.
    public void setElements(final List<PipelineAiElement> elements, final PipelineAiElement chosen) {
        element.clear();
        element.addItems(elements);
        element.setValue(chosen);
        chooser.setVisible(elements.size() > 1);
    }

    /// Shows the figures of the element chosen, and its learning runs.
    ///
    /// @param shown       Its figures.
    /// @param learnedRuns Its learning runs, newest first.
    public void showQuality(final QualityFigures shown, final List<SupervisorAttempt> learnedRuns) {
        nothing.setVisible(false);
        content.setVisible(true);
        message.setText(shown.getMessage() == null
                ? ""
                : shown.getMessage());
        message.setVisible(shown.getMessage() != null);
        figures.clear();
        for (final Figure figure : shown.getFigures()) {
            figures.add(figure(figure));
        }
        runs.setRowData(0, learnedRuns);
        runs.setRowCount(learnedRuns.size(), true);
    }

    /// Says why there is nothing to judge for the element chosen, e.g. that it hasn't learned anything yet.
    ///
    /// @param why What to say.
    public void showNothing(final String why) {
        nothing.setText(why);
        nothing.setVisible(true);
        content.setVisible(false);
    }

    /// @param onElement Told the element the user chooses.
    public void setOnElement(final Consumer<PipelineAiElement> onElement) {
        this.onElement = onElement;
    }

    // A record beside what it became: what it is and scored, then the record as received and its output
    private AriaPanel sample(final QualitySample sample) {
        final AriaPanel item = new AriaPanel();
        item.setRole("listitem");
        item.addStyleName("quality-tab__sample");
        final StringBuilder head = new StringBuilder(sample.getKind() == null
                ? "Record"
                : sample.getKind().getDisplayValue());
        if (sample.getTarget() != null) {
            head.append(" as ").append(sample.getTarget());
        }
        head.append(" · scored ").append(PlainWords.percent(sample.getScore()))
                .append(" · accepted ").append(dates.apply(sample.getAcceptedMs()));
        final Label heading = new Label(head.toString());
        heading.setStyleName("quality-tab__sample-head");
        item.add(heading);
        final FlowPanel pair = new FlowPanel();
        pair.setStyleName("quality-tab__sample-pair");
        pair.add(side(sample.getText() == null
                        ? "Received, as the parser read it"
                        : "Received",
                sample.getText() == null
                        ? sample.getRecord()
                        : sample.getText()));
        pair.add(side("Became", sample.getOutput() == null
                ? "No event: this kind of record yields none."
                : sample.getOutput()));
        item.add(pair);
        return item;
    }

    private static FlowPanel side(final String title, final String text) {
        final FlowPanel side = new FlowPanel();
        side.setStyleName("quality-tab__sample-side");
        final Label label = new Label(title);
        label.setStyleName("quality-tab__sample-title");
        side.add(label);
        final Label code = new Label(text == null
                ? ""
                : text);
        code.setStyleName("quality-tab__code");
        side.add(code);
        return side;
    }

    // One figure: what it is, its value, and what it rests on, coloured by its tone
    private static AriaPanel figure(final Figure figure) {
        final AriaPanel item = new AriaPanel();
        item.setRole("listitem");
        item.addStyleName("quality-tab__figure");
        item.addStyleName(toneClass(figure.getTone()));
        final Label label = new Label(figure.getLabel());
        label.setStyleName("quality-tab__figure-label");
        item.add(label);
        final Label value = new Label(figure.getValue());
        value.setStyleName("quality-tab__figure-value");
        item.add(value);
        final Label note = new Label(figure.getNote());
        note.setStyleName("quality-tab__figure-note");
        item.add(note);
        return item;
    }

    private static String toneClass(final Tone tone) {
        switch (tone) {
            case GOOD:
                return "quality-tab__figure--good";
            case WARNING:
                return "quality-tab__figure--warning";
            case DANGER:
                return "quality-tab__figure--danger";
            case INFO:
                return "quality-tab__figure--info";
            default:
                return "quality-tab__figure--idle";
        }
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, QualityTab> {

    }
}
