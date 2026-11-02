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


package stroom.pipelinefactory.client.component;

import stroom.docref.DocRef;
import stroom.pipelinefactory.shared.PipelineFactoryDoc;
import stroom.pipelinefactory.shared.PipelineFactoryFilterSettings;
import stroom.processor.client.ProcessorProfileClient;
import stroom.processor.client.ProcessorProfileListModel;
import stroom.widget.component.client.Contexts;
import stroom.widget.component.client.Controller;
import stroom.widget.datepicker.client.component.DateTimeBoxController;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;

/// A Pipeline Factory's Filter tab ([PipelineFactoryFilter]): the processor filter it gives every
/// pipeline it makes (design 06 §3.1, A108), every filter setting but the pipeline, what it matches
/// and who it runs as, each meaning what it means in the processing screen.
public class PipelineFactoryFilterController extends Controller {

    private final PipelineFactoryFilter view;

    /// Made by GIN, with its content.
    ///
    /// @param eventBus                      The application's event bus.
    /// @param view                          The tab.
    /// @param processorProfileClient        Finds the processing profiles.
    /// @param dateTimeBoxControllerProvider Makes the create time boxes' controllers.
    @Inject
    public PipelineFactoryFilterController(final EventBus eventBus,
                                           final PipelineFactoryFilter view,
                                           final ProcessorProfileClient processorProfileClient,
                                           final Provider<DateTimeBoxController> dateTimeBoxControllerProvider) {
        super(eventBus);
        this.view = view;
        initWidget(view);
        make(dateTimeBoxControllerProvider).attach(view.getMinMetaCreateTimeBox());
        make(dateTimeBoxControllerProvider).attach(view.getMaxMetaCreateTimeBox());

        final ProcessorProfileListModel profiles = new ProcessorProfileListModel(eventBus, processorProfileClient);
        profiles.setTaskMonitorFactory(taskMonitor());
        view.setProfileModel(profiles);

        view.setOnChange(this::markChanged);
        watch(Contexts.READ_ONLY, view::setReadOnly);
    }

    /// Shows the filter settings a factory gives the filters it makes.
    ///
    /// @param docRef The factory.
    /// @param doc    Its document.
    public void read(final DocRef docRef, final PipelineFactoryDoc doc) {
        final PipelineFactoryFilterSettings settings = doc.getFilterSettings();
        view.setFilterEnabled(settings.isEnabled());
        view.setPriority(settings.getPriority());
        view.setAutoPriority(settings.isAutoPriority());
        view.setMaxProcessingTasks(settings.getMaxProcessingTasks());
        view.setProfile(settings.getProfileName());
        view.setMinMetaCreateTimeMs(settings.getMinMetaCreateTimeMs());
        view.setMaxMetaCreateTimeMs(settings.getMaxMetaCreateTimeMs());
        view.setMaxTaskCreationDelay(settings.getMaxTaskCreationDelay());
        view.setExport(settings.isExport());
    }

    /// @param doc The document to write the filter settings into.
    /// @return A copy of it with the filter settings shown.
    public PipelineFactoryDoc write(final PipelineFactoryDoc doc) {
        return doc.copy()
                .filterSettings(PipelineFactoryFilterSettings.builder()
                        .enabled(view.isFilterEnabled())
                        .priority(view.getPriority())
                        .autoPriority(view.isAutoPriority())
                        .maxProcessingTasks(view.getMaxProcessingTasks())
                        .profileName(view.getProfile())
                        .minMetaCreateTimeMs(view.getMinMetaCreateTimeMs())
                        .maxMetaCreateTimeMs(view.getMaxMetaCreateTimeMs())
                        .maxTaskCreationDelay(view.getMaxTaskCreationDelay())
                        .export(view.isExport())
                        .build())
                .build();
    }
}
