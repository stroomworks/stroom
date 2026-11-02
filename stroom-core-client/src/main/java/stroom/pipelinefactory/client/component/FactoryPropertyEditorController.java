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
import stroom.pipelinefactory.shared.PipelineFactoryProperty;
import stroom.util.shared.NullSafe;
import stroom.widget.component.client.Controller;
import stroom.widget.component.client.ControllerDialog;
import stroom.widget.component.client.HasControllerNode;
import stroom.widget.popup.client.PopupSize;
import stroom.widget.popup.client.PopupType;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.function.Consumer;

/// Adds or edits one value a Pipeline Factory writes onto an element of each pipeline it makes
/// (A113), in the Property value dialog ([FactoryPropertyEditor]). The element and the property name
/// must both be given. Made for one use.
public class FactoryPropertyEditorController extends Controller {

    private final FactoryPropertyEditor view;

    /// Made by GIN, with its content.
    ///
    /// @param eventBus The application's event bus.
    /// @param view     The content.
    @Inject
    public FactoryPropertyEditorController(final EventBus eventBus, final FactoryPropertyEditor view) {
        super(eventBus);
        this.view = view;
        initWidget(view);
    }

    /// Shows the dialog, owned by `opener` until it is closed. It isn't modal: it edits an item in a
    /// list, which the page behind still shows. OK gives what it holds to `onOk` if the element and
    /// the property name are given; otherwise it says so and the dialog stays open.
    ///
    /// @param opener   What shows it.
    /// @param property What to show, or a new one; its fields may be null.
    /// @param onOk     Given the property value when OK is pressed.
    public void edit(final HasControllerNode opener,
                     final PipelineFactoryProperty property,
                     final Consumer<PipelineFactoryProperty> onOk) {
        view.setElementId(property.getElementId());
        view.setName(property.getName());
        view.setValue(property.getValue());
        ControllerDialog.showOnce(opener, this)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(PopupSize.resizableX(500))
                .caption("Property value")
                .onShow(e -> view.focus())
                .onHideRequest(e -> {
                    if (!e.isOk()) {
                        e.hide();
                        return;
                    }
                    final PipelineFactoryProperty written = write();
                    if (written.getElementId().isEmpty() || written.getName().isEmpty()) {
                        AlertEvent.fireError(this, "A property value needs an element and a property name.",
                                e::reset);
                    } else {
                        onOk.accept(written);
                        e.hide();
                    }
                })
                .fire();
    }

    // What the dialog holds, the element and name trimmed
    private PipelineFactoryProperty write() {
        return new PipelineFactoryProperty(NullSafe.trim(view.getElementId()),
                NullSafe.trim(view.getName()),
                view.getValue());
    }
}
