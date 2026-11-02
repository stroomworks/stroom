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

import stroom.alert.client.event.AlertEvent;
import stroom.alert.client.event.ConfirmEvent;
import stroom.docref.DocRef;
import stroom.explorer.client.component.DocSelectionController;
import stroom.meta.shared.MetaFields;
import stroom.pipeline.shared.PipelineDoc;
import stroom.pipelinefactory.shared.PipelineFactoryDoc;
import stroom.pipelinefactory.shared.PipelineFactoryProperty;
import stroom.query.api.ExpressionOperator;
import stroom.query.client.component.ExpressionEditorController;
import stroom.query.client.model.SimpleFieldSelectionListModel;
import stroom.security.client.component.UserRefSelectionController;
import stroom.security.shared.FindUserContext;
import stroom.widget.component.client.Contexts;
import stroom.widget.component.client.Controller;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/// A Pipeline Factory's Settings tab ([PipelineFactorySettings]), what it makes and where (design 06
/// §3.1): whether it runs, the streams it looks at, what makes one pipeline, the template, the names
/// and folders, the values it writes onto each pipeline (A113), and who it makes them as. What is
/// wrong with the factory is shown as it is edited, since the store refuses to save a factory with
/// problems.
public class PipelineFactorySettingsController extends Controller {

    private final PipelineFactorySettings view;
    private final ExpressionEditorController streamFilter;
    private final DocSelectionController template;
    private final UserRefSelectionController runAsUser;
    private final FactoryPropertyListController propertyList;
    private final Provider<FactoryPropertyEditorController> propertyEditorProvider;
    private final List<PipelineFactoryProperty> properties = new ArrayList<>();
    // The factory as read, which this tab's settings are written over to show its problems
    private PipelineFactoryDoc doc;
    private boolean readOnly = true;

    /// Made by GIN, with its content and its parts.
    ///
    /// @param eventBus               The application's event bus.
    /// @param view                   The tab.
    /// @param streamFilter           Edits the streams the factory looks at.
    /// @param docSelectionProvider   Makes the template picker.
    /// @param runAsUserProvider      Makes the run-as user picker.
    /// @param propertyListProvider   Makes the property values' list.
    /// @param propertyEditorProvider Makes the Property value dialog.
    @Inject
    public PipelineFactorySettingsController(
            final EventBus eventBus,
            final PipelineFactorySettings view,
            final ExpressionEditorController streamFilter,
            final Provider<DocSelectionController> docSelectionProvider,
            final Provider<UserRefSelectionController> runAsUserProvider,
            final Provider<FactoryPropertyListController> propertyListProvider,
            final Provider<FactoryPropertyEditorController> propertyEditorProvider) {
        super(eventBus);
        this.view = view;
        this.propertyEditorProvider = propertyEditorProvider;
        initWidget(view);
        view.setOnChange(this::onSettingChange);

        // The fields a processor filter can query, since the stream filter is part of every filter made
        this.streamFilter = own(streamFilter).attach(view.getStreamFilterEditor());
        final SimpleFieldSelectionListModel fields = new SimpleFieldSelectionListModel();
        fields.addItems(MetaFields.getProcessorFilterFields());
        streamFilter.init(MetaFields.STREAM_STORE_DOC_REF, fields);
        streamFilter.setOnChange(this::onSettingChange);

        template = make(docSelectionProvider);
        template.attach(view.getTemplateBox());
        template.setIncludedTypes(PipelineDoc.TYPE);
        own(template.addSelectionListener(docRef -> onSettingChange()));

        runAsUser = make(runAsUserProvider).attach(view.getRunAsUserBox());
        runAsUser.setContext(FindUserContext.RUN_AS);
        own(runAsUser.addSelectionListener(userRef -> onSettingChange()));

        propertyList = make(propertyListProvider).attach(view.getPropertyList());
        propertyList.setOnAdd(this::onAdd);
        propertyList.setOnEdit(this::onEdit);
        propertyList.setOnRemove(this::onRemove);

        watch(Contexts.READ_ONLY, readOnly -> {
            this.readOnly = readOnly;
            view.setReadOnly(readOnly);
            streamFilter.setReadOnly(readOnly);
            template.setReadOnly(readOnly);
            runAsUser.setReadOnly(readOnly);
        });
    }

    /// Shows a factory's settings, and what is wrong with it.
    ///
    /// @param docRef The factory.
    /// @param doc    Its document.
    public void read(final DocRef docRef, final PipelineFactoryDoc doc) {
        this.doc = doc;
        view.setEnabled(doc.isEnabled());
        view.setKey(doc.getKey());
        view.setFolderPattern(doc.getFolderPattern());
        view.setNamePattern(doc.getNamePattern());
        streamFilter.read(Objects.requireNonNullElse(doc.getStreamFilter(),
                ExpressionOperator.builder().build()));
        template.setSelectedEntityReference(doc.getTemplate(), true);
        runAsUser.setSelected(doc.getRunAsUser());
        properties.clear();
        properties.addAll(doc.getProperties());
        propertyList.setData(properties);
        propertyList.setSelected(null);
        view.setProblems(doc.problems());
    }

    /// @param doc The document to write the settings into.
    /// @return A copy of it with the settings shown.
    public PipelineFactoryDoc write(final PipelineFactoryDoc doc) {
        return doc.copy()
                .enabled(view.isEnabled())
                .streamFilter(FactoryStreamFilter.toKeep(streamFilter.write(), doc.getStreamFilter()))
                .key(view.getKey())
                .template(template.getSelectedEntityReference())
                .folderPattern(view.getFolderPattern())
                .namePattern(view.getNamePattern())
                .properties(new ArrayList<>(properties))
                .runAsUser(runAsUser.getSelected())
                .build();
    }

    // A setting was changed: the document is dirty, and what it would save is checked again
    private void onSettingChange() {
        markChanged();
        if (doc != null) {
            view.setProblems(write(doc).problems());
        }
    }

    private void onAdd() {
        if (!readOnly) {
            show(new PipelineFactoryProperty(null, null, null), property -> {
                properties.add(property);
                propertyList.setData(properties);
                propertyList.setSelected(property);
                onSettingChange();
            });
        }
    }

    private void onEdit() {
        final PipelineFactoryProperty existing = propertyList.getSelected();
        if (existing != null && !readOnly) {
            show(existing, property -> {
                // The dialog isn't modal, so the value may have gone while it was open
                final int index = properties.indexOf(existing);
                if (index < 0) {
                    AlertEvent.fireWarn(this, "The property value for " + describe(existing)
                                              + " was removed while it was being edited.", null);
                    return;
                }
                properties.set(index, property);
                propertyList.setData(properties);
                propertyList.setSelected(property);
                if (!existing.equals(property)) {
                    onSettingChange();
                }
            });
        }
    }

    private void onRemove() {
        final PipelineFactoryProperty existing = propertyList.getSelected();
        if (existing != null && !readOnly) {
            final String question = "Remove the value for " + describe(existing) + "?";
            ConfirmEvent.fire(this, question, ok -> {
                if (ok && !isDisposed()) {
                    properties.remove(existing);
                    propertyList.setData(properties);
                    propertyList.setSelected(null);
                    onSettingChange();
                }
            });
        }
    }

    private void show(final PipelineFactoryProperty property, final Consumer<PipelineFactoryProperty> onOk) {
        propertyEditorProvider.get().edit(this, property, guard(onOk));
    }

    private static String describe(final PipelineFactoryProperty property) {
        return property.getElementId() + "." + property.getName();
    }
}
