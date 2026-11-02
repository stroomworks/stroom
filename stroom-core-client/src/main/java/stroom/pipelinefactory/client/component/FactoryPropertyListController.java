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

import stroom.pipelinefactory.shared.PipelineFactoryProperty;
import stroom.widget.component.client.Contexts;
import stroom.widget.component.client.Controller;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.List;
import java.util.Objects;

/// The values a Pipeline Factory writes onto each pipeline it makes ([FactoryPropertyList]), as its
/// owner gives them; it follows the document's read-only state. Its owner adds, edits and removes
/// them.
public class FactoryPropertyListController extends Controller {

    private FactoryPropertyList view;

    /// Made by GIN; [#attach(FactoryPropertyList)] gives it its list.
    ///
    /// @param eventBus The application's event bus.
    @Inject
    public FactoryPropertyListController(final EventBus eventBus) {
        super(eventBus);
    }

    /// Attaches this to a list, e.g. one a template declares. The list is then this controller's
    /// widget.
    ///
    /// @param view The list.
    /// @return This, for its owner to keep.
    public FactoryPropertyListController attach(final FactoryPropertyList view) {
        this.view = Objects.requireNonNull(view, "view");
        initWidget(view);
        watch(Contexts.READ_ONLY, view::setReadOnly);
        return this;
    }

    /// @param properties The property values to show.
    public void setData(final List<PipelineFactoryProperty> properties) {
        view.setData(properties);
    }

    /// @return The property value selected, or null if none is.
    public PipelineFactoryProperty getSelected() {
        return view.getSelectionModel().getSelected();
    }

    /// @param property The property value to select, or null to select none.
    public void setSelected(final PipelineFactoryProperty property) {
        view.setSelected(property);
    }

    /// @param onAdd Run when Add is pressed.
    public void setOnAdd(final Runnable onAdd) {
        view.setOnAdd(onAdd);
    }

    /// @param onEdit Run when Edit is pressed, or a row double clicked.
    public void setOnEdit(final Runnable onEdit) {
        view.setOnEdit(onEdit);
    }

    /// @param onRemove Run when Remove is pressed.
    public void setOnRemove(final Runnable onRemove) {
        view.setOnRemove(onRemove);
    }
}
