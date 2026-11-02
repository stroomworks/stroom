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

import stroom.processor.shared.ProcessorFilter;
import stroom.util.shared.time.SimpleDuration;
import stroom.util.shared.time.TimeUnit;
import stroom.widget.component.client.Component;
import stroom.widget.datepicker.client.component.DateTimeBox;
import stroom.widget.duration.client.DurationPicker;
import stroom.widget.selection.client.SelectionBox;
import stroom.widget.selection.client.SelectionListModel;
import stroom.widget.selection.client.SimpleSelectionItemWrapper;
import stroom.widget.tickbox.client.TickBox;
import stroom.widget.valuespinner.client.ValueSpinner;

import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Widget;

import java.util.Objects;

/// A Pipeline Factory's Filter tab: the processor filter it gives every pipeline it makes (design 06
/// §3.1, A108), laid out as the processing screen lays out a filter: every filter setting but the
/// pipeline, what it matches and who it runs as, each meaning what it means there.
public class PipelineFactoryFilter extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    private static final SimpleDuration DEFAULT_MAX_TASK_CREATION_DELAY = new SimpleDuration(30L, TimeUnit.SECONDS);

    @UiField
    TickBox filterEnabled;
    @UiField
    ValueSpinner priority;
    @UiField
    TickBox autoPriority;
    @UiField
    ValueSpinner maxProcessingTasks;
    @UiField
    SelectionBox<String> profile;
    @UiField
    DateTimeBox minMetaCreateTimeMs;
    @UiField
    DateTimeBox maxMetaCreateTimeMs;
    @UiField
    TickBox maxTaskCreationDelayEnabled;
    @UiField
    DurationPicker maxTaskCreationDelay;
    @UiField
    TickBox export;

    private Runnable onChange = () -> {
    };

    /// Makes the tab, with the processing screen's defaults.
    public PipelineFactoryFilter() {
        initWidget(BINDER.createAndBindUi(this));
        priority.setMin(ProcessorFilter.MIN_PRIORITY);
        priority.setMax(ProcessorFilter.MAX_PRIORITY);
        // Zero means no limit, so it must stay reachable
        maxProcessingTasks.setMin(ProcessorFilter.MIN_MAX_PROCESSING_TASKS);
        maxProcessingTasks.setMax(ProcessorFilter.MAX_MAX_PROCESSING_TASKS);
        maxTaskCreationDelay.setValue(DEFAULT_MAX_TASK_CREATION_DELAY);
        updateEnabled();

        filterEnabled.setOnChange(value -> onChange.run());
        autoPriority.setOnChange(value -> onChange.run());
        export.setOnChange(value -> onChange.run());
        maxTaskCreationDelayEnabled.setOnChange(value -> {
            updateEnabled();
            onChange.run();
        });
        priority.setOnChange(value -> onChange.run());
        maxProcessingTasks.setOnChange(value -> onChange.run());
        profile.setOnChange(value -> onChange.run());
        minMetaCreateTimeMs.setOnChange(() -> onChange.run());
        maxMetaCreateTimeMs.setOnChange(() -> onChange.run());
        maxTaskCreationDelay.setOnChange(value -> onChange.run());
    }

    /// @param onChange Run when the user changes a setting.
    public void setOnChange(final Runnable onChange) {
        this.onChange = onChange;
    }

    /// @param model The processing profiles that can be chosen.
    public void setProfileModel(final SelectionListModel<String, SimpleSelectionItemWrapper<String>> model) {
        profile.setModel(model);
    }

    /// @return The earliest create time box, which its controller attaches the date picker to.
    public DateTimeBox getMinMetaCreateTimeBox() {
        return minMetaCreateTimeMs;
    }

    /// @return The latest create time box, which its controller attaches the date picker to.
    public DateTimeBox getMaxMetaCreateTimeBox() {
        return maxMetaCreateTimeMs;
    }

    /// @return Whether a made filter is enabled.
    public boolean isFilterEnabled() {
        return filterEnabled.getValue();
    }

    /// @param enabled Whether a made filter is enabled.
    public void setFilterEnabled(final boolean enabled) {
        filterEnabled.setValue(enabled);
    }

    /// @return A made filter's priority.
    public int getPriority() {
        return priority.getIntValue();
    }

    /// @param priority A made filter's priority.
    public void setPriority(final int priority) {
        this.priority.setValue(priority);
    }

    /// @return Whether a made filter's priority is worked out.
    public boolean isAutoPriority() {
        return autoPriority.getValue();
    }

    /// @param autoPriority Whether a made filter's priority is worked out.
    public void setAutoPriority(final boolean autoPriority) {
        this.autoPriority.setValue(autoPriority);
    }

    /// @return The most tasks a made filter may have processing; 0 for no limit.
    public int getMaxProcessingTasks() {
        return maxProcessingTasks.getIntValue();
    }

    /// @param maxProcessingTasks The most tasks a made filter may have processing; 0 for no limit.
    public void setMaxProcessingTasks(final int maxProcessingTasks) {
        this.maxProcessingTasks.setValue(maxProcessingTasks);
    }

    /// @return The name of the processing profile chosen, or null for none.
    public String getProfile() {
        return profile.getValue();
    }

    /// @param profileName The name of the processing profile, or null for none.
    public void setProfile(final String profileName) {
        profile.setValue(profileName);
    }

    /// @return The earliest create time a made filter takes; null for none.
    public Long getMinMetaCreateTimeMs() {
        return minMetaCreateTimeMs.getValue();
    }

    /// @param minMetaCreateTimeMs The earliest create time a made filter takes; null for none.
    public void setMinMetaCreateTimeMs(final Long minMetaCreateTimeMs) {
        this.minMetaCreateTimeMs.setValue(minMetaCreateTimeMs);
    }

    /// @return The latest create time a made filter takes; null for none.
    public Long getMaxMetaCreateTimeMs() {
        return maxMetaCreateTimeMs.getValue();
    }

    /// @param maxMetaCreateTimeMs The latest create time a made filter takes; null for none.
    public void setMaxMetaCreateTimeMs(final Long maxMetaCreateTimeMs) {
        this.maxMetaCreateTimeMs.setValue(maxMetaCreateTimeMs);
    }

    /// @return How long a made filter may wait to create tasks; null for the cluster's limit.
    public SimpleDuration getMaxTaskCreationDelay() {
        return maxTaskCreationDelayEnabled.getValue()
                ? maxTaskCreationDelay.getValue()
                : null;
    }

    /// @param maxTaskCreationDelay How long a made filter may wait to create tasks; null for the
    ///                             cluster's limit (the box unticked, showing 30 seconds).
    public void setMaxTaskCreationDelay(final SimpleDuration maxTaskCreationDelay) {
        // None means the filter takes the cluster's limit
        maxTaskCreationDelayEnabled.setValue(maxTaskCreationDelay != null);
        this.maxTaskCreationDelay.setValue(Objects.requireNonNullElse(maxTaskCreationDelay,
                DEFAULT_MAX_TASK_CREATION_DELAY));
        updateEnabled();
    }

    /// @return Whether a made filter is exported with its pipeline.
    public boolean isExport() {
        return export.getValue();
    }

    /// @param export Whether a made filter is exported with its pipeline.
    public void setExport(final boolean export) {
        this.export.setValue(export);
    }

    @Override
    protected void onReadOnlyChange(final boolean readOnly) {
        filterEnabled.setReadOnly(readOnly);
        priority.setReadOnly(readOnly);
        autoPriority.setReadOnly(readOnly);
        maxProcessingTasks.setReadOnly(readOnly);
        profile.setReadOnly(readOnly);
        minMetaCreateTimeMs.setReadOnly(readOnly);
        maxMetaCreateTimeMs.setReadOnly(readOnly);
        maxTaskCreationDelayEnabled.setReadOnly(readOnly);
        export.setReadOnly(readOnly);
        updateEnabled();
    }

    // The delay can be set only while its tick box is ticked, and never when read only
    private void updateEnabled() {
        maxTaskCreationDelay.setEnabled(!isReadOnly() && maxTaskCreationDelayEnabled.getValue());
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, PipelineFactoryFilter> {

    }
}
