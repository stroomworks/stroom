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

import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.learningflow.shared.AttemptCriteria;
import stroom.learningflow.shared.LearnerRef;
import stroom.learningflow.shared.LearningFlowDoc;
import stroom.learningflow.shared.QualityResource;
import stroom.learningflow.shared.SupervisorLearner;
import stroom.learningflow.shared.SupervisorResource;
import stroom.preferences.client.DateTimeFormatter;
import stroom.util.shared.NullSafe;
import stroom.util.shared.PageRequest;
import stroom.widget.component.client.Contexts;
import stroom.widget.component.client.Controller;

import com.google.gwt.core.client.GWT;
import com.google.gwt.i18n.client.DateTimeFormat;
import com.google.gwt.i18n.client.TimeZone;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/// A Smart Pipeline's Quality Assurance tab ([QualityTab]; design 07), for a QA person judging one pipeline's
/// translated output: the figures of the translation each of its Smart Pipeline elements has in use
/// ([QualityFigures]) and its learning runs' scores, read by the pipeline's permissions as Learning Status is.
/// Its owner tells it the pipeline's Smart Pipeline elements, which Learning Status finds ([PipelineAiElement]),
/// so that the pipeline isn't read twice; it is shown only where there are any.
///
/// Beside the figures, through the quality resource ([QualityResource]): the volume day by day and what the
/// records of the translation in use became, samples of those records beside what they became (for someone who
/// may also view data; for anyone else, a sentence says why there are none).
///
/// Read when it is shown, and when another element is chosen: quality changes over hours, not seconds, so it
/// isn't read again on a timer.
public class QualityTabController extends Controller {

    private static final SupervisorResource SUPERVISOR_RESOURCE = GWT.create(SupervisorResource.class);
    private static final QualityResource QUALITY_RESOURCE = GWT.create(QualityResource.class);
    static final String NO_DATA = "Samples aren't shown: they are this pipeline's data, which you may not view.";
    private static final int RUNS = 50;
    private static final DateTimeFormat DAY_FORMAT = DateTimeFormat.getFormat("d MMM");
    private static final TimeZone UTC = TimeZone.createTimeZone(0);

    private final QualityTab view;
    private final RestFactory restFactory;
    /// The settings of each Learning Flow an element learns under, read once while the tab is open.
    private final PipelineLearners learners;
    private DocRef pipeline;
    private List<PipelineAiElement> elements = List.of();
    private PipelineAiElement chosen;
    private boolean showing;
    /// Whether the pipeline can't be edited here: then an open issue isn't offered to be resolved.
    private boolean readOnly = true;
    /// Which read is current, so that a slower earlier reply is ignored.
    private int asked;

    /// Made by GIN, with its tab.
    ///
    /// @param eventBus          The application's event bus.
    /// @param view              The tab.
    /// @param restFactory       Makes REST requests.
    /// @param dateTimeFormatter        Says when each learning run started, as the user likes dates.
    @Inject
    public QualityTabController(final EventBus eventBus,
                                final QualityTab view,
                                final RestFactory restFactory,
                                final DateTimeFormatter dateTimeFormatter) {
        super(eventBus);
        this.view = view;
        this.restFactory = restFactory;
        this.learners = new PipelineLearners(SUPERVISOR_RESOURCE, restFactory, this, this::taskMonitor);
        initWidget(view);
        view.setDateFormat(dateTimeFormatter::format);
        view.setDayFormat(QualityTabController::day);
        watch(Contexts.READ_ONLY, value -> {
            readOnly = value;
            if (showing) {
                refresh();
            }
        });
        view.setOnElement(element -> {
            if (element != null && !element.equals(chosen)) {
                chosen = element;
                refresh();
            }
        });
    }

    /// Tells it the pipeline's Smart Pipeline elements, as Learning Status found them.
    ///
    /// @param pipelineRef The pipeline.
    /// @param found       Its Smart Pipeline elements; empty for none.
    public void setElements(final DocRef pipelineRef, final List<PipelineAiElement> found) {
        if (pipeline == null || !pipeline.getUuid().equals(pipelineRef.getUuid())) {
            learners.clear();
        }
        pipeline = pipelineRef;
        elements = new ArrayList<>(found);
        final String before = NullSafe.get(chosen, PipelineAiElement::getId);
        chosen = elements.stream()
                .filter(element -> element.getId().equals(before))
                .findFirst()
                .orElse(elements.isEmpty()
                        ? null
                        : elements.get(0));
        view.setElements(elements, chosen);
        if (showing) {
            refresh();
        }
    }

    /// Shows one of the pipeline's Smart Pipeline elements, e.g. from Learning Status, read when the tab is shown.
    ///
    /// @param elementId The element's id.
    public void showElement(final String elementId) {
        for (final PipelineAiElement element : elements) {
            if (element.getId().equals(elementId) && !element.equals(chosen)) {
                chosen = element;
                view.setElements(elements, chosen);
                if (showing) {
                    refresh();
                }
            }
        }
    }

    /// Says whether the tab is on screen: it is read whenever it comes to be.
    ///
    /// @param showing Whether it is the tab shown, in the pipeline shown.
    public void setShowing(final boolean showing) {
        final boolean back = showing && !this.showing;
        this.showing = showing;
        if (back) {
            refresh();
        }
    }

    /// Reads the chosen element's figures and learning runs again.
    public void refresh() {
        final PipelineAiElement element = chosen;
        if (pipeline == null || element == null) {
            return;
        }
        if (element.getDocument() == null) {
            view.showNothing(PipelineAiTabController.NO_DOCUMENT);
            return;
        }
        final int request = ++asked;
        restFactory
                .create(SUPERVISOR_RESOURCE)
                .method(resource -> resource.pipelineLearners(pipeline.getUuid()))
                .onSuccess(found -> {
                    if (request != asked) {
                        return;
                    }
                    final SupervisorLearner row = PipelineLearners.learnerOf(found, element.getId());
                    if (row == null) {
                        view.showNothing(PipelineAiTabController.NOT_LEARNED);
                    } else {
                        learners.readOrNull(row, doc -> readRuns(request, row, doc));
                    }
                })
                .owner(this)
                .taskMonitorFactory(taskMonitor())
                .exec();
    }

    // The element's learning runs, newest first, then everything shown
    private void readRuns(final int request, final SupervisorLearner row, final LearningFlowDoc doc) {
        restFactory
                .create(SUPERVISOR_RESOURCE)
                .method(resource -> resource.find(AttemptCriteria.forLearner(new PageRequest(0, RUNS),
                        row.getLearner())))
                .onSuccess(page -> {
                    if (request == asked) {
                        view.showQuality(QualityFigures.of(row, doc), NullSafe.list(page.getValues()));
                        readQuality(request, row);
                    }
                })
                .owner(this)
                .taskMonitorFactory(taskMonitor())
                .exec();
    }

    // The volume and cardinality, and the samples, each shown as it comes
    private void readQuality(final int request, final SupervisorLearner row) {
        final String pipelineUuid = row.getLearner().getPipelineUuid();
        final String elementId = row.getLearner().getElementId();
        restFactory
                .create(QUALITY_RESOURCE)
                .method(resource -> resource.report(pipelineUuid, elementId))
                .onSuccess(report -> {
                    if (request == asked) {
                        view.showReport(report);
                    }
                })
                .owner(this)
                .taskMonitorFactory(taskMonitor())
                .exec();
        restFactory
                .create(QUALITY_RESOURCE)
                .method(resource -> resource.samples(pipelineUuid, elementId))
                .onSuccess(samples -> {
                    if (request == asked) {
                        view.showSamples(NullSafe.list(samples));
                    }
                })
                // Refused for someone who may not view data, which the samples are: said, not alerted
                .onFailure(error -> {
                    if (request == asked) {
                        view.showSamplesNote(NO_DATA);
                    }
                })
                .owner(this)
                .taskMonitorFactory(taskMonitor())
                .exec();
    }

    // A day (UTC) as the volume's table and chart name it, e.g. "17 Nov"
    private static String day(final long dayMs) {
        return DAY_FORMAT.format(new Date(dayMs), UTC);
    }
}
