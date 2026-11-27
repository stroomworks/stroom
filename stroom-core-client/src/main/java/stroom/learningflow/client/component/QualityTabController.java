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

/// Controls a pipeline's Quality Assurance tab ([QualityTab]), where a person checks the quality of a Smart
/// Pipeline's translated output.
///
/// For the chosen Smart Pipeline element it shows the headline figures of the translation in use
/// ([QualityFigures]) and the scores of its learning runs. As on the Learning Status tab, access follows the
/// pipeline's permissions. The owner passes in the pipeline's Smart Pipeline elements, as found by the Learning
/// Status tab ([PipelineAiElement]), so that the pipeline is not read twice; the tab is shown only when there
/// are any.
///
/// It also reads, from the quality resource ([QualityResource]), the daily volume, the cardinality of the
/// translation in use, and samples of its records beside their output. Samples are only shown to users who
/// may view the pipeline's data; anyone else sees a message saying why there are none.
///
/// The tab is read when it is shown and when another element is chosen. It is not refreshed on a timer,
/// because quality changes over hours rather than seconds.
public class QualityTabController extends Controller {

    private static final SupervisorResource SUPERVISOR_RESOURCE = GWT.create(SupervisorResource.class);
    private static final QualityResource QUALITY_RESOURCE = GWT.create(QualityResource.class);
    static final String NO_DATA = "Samples aren't shown: they are this pipeline's data, which you may not view.";
    private static final int RUNS = 50;
    private static final DateTimeFormat DAY_FORMAT = DateTimeFormat.getFormat("d MMM");
    private static final TimeZone UTC = TimeZone.createTimeZone(0);

    private final QualityTab view;
    private final RestFactory restFactory;
    /// Finds learners and caches the settings of each Learning Flow they learn under while the tab is open.
    private final PipelineLearners learners;
    private DocRef pipeline;
    private List<PipelineAiElement> elements = List.of();
    private PipelineAiElement chosen;
    private boolean showing;
    /// Whether the pipeline is read-only. It is tracked, and a change refreshes the tab, but nothing in this
    /// controller currently reads it.
    private boolean readOnly = true;
    /// A counter identifying the latest refresh, so that replies to an earlier, slower refresh are ignored.
    private int asked;

    /// Made by GIN.
    ///
    /// @param eventBus          The application's event bus.
    /// @param view              The tab.
    /// @param restFactory       Makes REST requests.
    /// @param dateTimeFormatter Formats dates and times in the user's preferred format.
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

    /// Sets the pipeline and its Smart Pipeline elements, as found by the Learning Status tab. The element
    /// chosen before stays chosen if it is still present; otherwise the first is chosen.
    ///
    /// @param pipelineRef The pipeline.
    /// @param found       The pipeline's Smart Pipeline elements; empty if it has none.
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

    /// Chooses one of the pipeline's Smart Pipeline elements, e.g. when asked from the Learning Status tab. Its
    /// figures are read now if the tab is visible, otherwise when it is next shown.
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

    /// Tells the controller whether the tab is visible. The tab is read again each time it becomes visible.
    ///
    /// @param showing Whether this tab is the selected tab of the visible pipeline.
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

    // Reads the element's learning runs, newest first, then shows the figures and runs and reads the rest
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

    // Reads the quality report (volume and cardinality) and the samples, showing each as its reply arrives
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
                // Samples are the pipeline's data, so the request is refused for a user who may not view
                // data. Show a message rather than an error alert.
                .onFailure(error -> {
                    if (request == asked) {
                        view.showSamplesNote(NO_DATA);
                    }
                })
                .owner(this)
                .taskMonitorFactory(taskMonitor())
                .exec();
    }

    // Formats a day (UTC) as the volume table and chart label it, e.g. "17 Nov"
    private static String day(final long dayMs) {
        return DAY_FORMAT.format(new Date(dayMs), UTC);
    }
}
