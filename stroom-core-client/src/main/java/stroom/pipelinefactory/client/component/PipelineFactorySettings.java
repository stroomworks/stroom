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

import stroom.meta.shared.MetaFields;
import stroom.query.client.component.ExpressionEditor;
import stroom.widget.component.client.Component;
import stroom.widget.dropdowntree.client.DropDownBox;
import stroom.widget.form.client.TextBox;
import stroom.widget.text.client.Label;
import stroom.widget.tickbox.client.TickBox;

import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Widget;

import java.util.ArrayList;
import java.util.List;

/// A Pipeline Factory's Settings tab: whether it runs, what makes one pipeline, the template, the
/// names and folders and who it makes them as, beside the streams it looks at and the values it
/// writes onto each pipeline. The key is a tick box per field a key may name (A119), so a field no
/// filter could select is never offered. What is wrong with the factory is shown above the form.
/// The template and run-as user pickers, the stream filter's editor and the property values' list
/// are parts its controller attaches.
public class PipelineFactorySettings extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    Label problems;
    @UiField
    TickBox enabled;
    @UiField
    TickBox keyFeed;
    @UiField
    TickBox keyType;
    @UiField
    TickBox keyPipeline;
    @UiField
    DropDownBox template;
    @UiField
    TextBox folderPattern;
    @UiField
    TextBox namePattern;
    @UiField
    DropDownBox runAsUser;
    @UiField
    ExpressionEditor streamFilter;
    @UiField
    FactoryPropertyList properties;

    private Runnable onChange = () -> {
    };

    /// Makes the tab, with nothing in it.
    public PipelineFactorySettings() {
        initWidget(BINDER.createAndBindUi(this));
        problems.setVisible(false);
        enabled.setOnChange(value -> onChange.run());
        keyFeed.setOnChange(value -> onChange.run());
        keyType.setOnChange(value -> onChange.run());
        keyPipeline.setOnChange(value -> onChange.run());
        folderPattern.addValueChangeHandler(event -> onChange.run());
        namePattern.addValueChangeHandler(event -> onChange.run());
    }

    /// @param onChange Run when the user changes a setting this holds itself (not one of its parts).
    public void setOnChange(final Runnable onChange) {
        this.onChange = onChange;
    }

    /// @return The template picker's box, which its controller attaches the picker to.
    public DropDownBox getTemplateBox() {
        return template;
    }

    /// @return The run-as user picker's box, which its controller attaches the picker to.
    public DropDownBox getRunAsUserBox() {
        return runAsUser;
    }

    /// @return The stream filter's editor, which its controller attaches the editor's controller to.
    public ExpressionEditor getStreamFilterEditor() {
        return streamFilter;
    }

    /// @return The property values' list, which its controller attaches the list's controller to.
    public FactoryPropertyList getPropertyList() {
        return properties;
    }

    /// @return Whether the job runs the factory.
    public boolean isEnabled() {
        return enabled.getValue();
    }

    /// @param enabled Whether the job runs the factory.
    public void setEnabled(final boolean enabled) {
        this.enabled.setValue(enabled);
    }

    /// @return The key fields ticked, in the order a key lists them.
    public List<String> getKey() {
        final List<String> key = new ArrayList<>();
        if (keyFeed.getValue()) {
            key.add(MetaFields.FEED.getFldName());
        }
        if (keyType.getValue()) {
            key.add(MetaFields.TYPE.getFldName());
        }
        if (keyPipeline.getValue()) {
            key.add(MetaFields.PIPELINE.getFldName());
        }
        return key;
    }

    /// @param key The key fields to tick.
    public void setKey(final List<String> key) {
        keyFeed.setValue(key.contains(MetaFields.FEED.getFldName()));
        keyType.setValue(key.contains(MetaFields.TYPE.getFldName()));
        keyPipeline.setValue(key.contains(MetaFields.PIPELINE.getFldName()));
    }

    /// @return Where a made pipeline goes.
    public String getFolderPattern() {
        return folderPattern.getValue();
    }

    /// @param folderPattern Where a made pipeline goes.
    public void setFolderPattern(final String folderPattern) {
        this.folderPattern.setValue(folderPattern);
    }

    /// @return What a made pipeline is called.
    public String getNamePattern() {
        return namePattern.getValue();
    }

    /// @param namePattern What a made pipeline is called.
    public void setNamePattern(final String namePattern) {
        this.namePattern.setValue(namePattern);
    }

    /// Shows what is wrong with the factory, since the store refuses to save a factory with problems;
    /// nothing is shown when nothing is.
    ///
    /// @param problems What is wrong with the factory as it stands; empty when nothing is.
    public void setProblems(final List<String> problems) {
        this.problems.setText(problems.isEmpty()
                ? ""
                : "Cannot be saved until put right: " + String.join("; ", problems) + ".");
        this.problems.setVisible(!problems.isEmpty());
    }

    @Override
    protected void onReadOnlyChange(final boolean readOnly) {
        enabled.setReadOnly(readOnly);
        keyFeed.setReadOnly(readOnly);
        keyType.setReadOnly(readOnly);
        keyPipeline.setReadOnly(readOnly);
        folderPattern.setReadOnly(readOnly);
        namePattern.setReadOnly(readOnly);
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, PipelineFactorySettings> {

    }
}
