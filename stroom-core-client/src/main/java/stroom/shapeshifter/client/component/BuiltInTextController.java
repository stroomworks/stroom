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

import stroom.shapeshifter.shared.Template;
import stroom.widget.component.client.Controller;
import stroom.widget.component.client.ControllerDialog;
import stroom.widget.component.client.HasControllerNode;
import stroom.widget.popup.client.PopupSize;
import stroom.widget.popup.client.PopupType;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/// The dialog that loads a built-in text into one of a step's texts (design 05 §3) ([BuiltInText]):
/// the built-ins whose variables the text allows, chosen from a list with the words shown, so that a
/// person sees what they are about to load before it replaces the document's own words.
public class BuiltInTextController extends Controller {

    private final BuiltInText view;
    private Map<Template, String> builtIns = Map.of();

    /// Made by GIN, with its content.
    ///
    /// @param eventBus The application's event bus.
    /// @param view     The dialog's content.
    @Inject
    public BuiltInTextController(final EventBus eventBus, final BuiltInText view) {
        super(eventBus);
        this.view = view;
        initWidget(view);
        view.setOnChoice(this::preview);
    }

    /// Shows the dialog, owned by `opener` until it is closed. OK gives the words of the built-in
    /// chosen to `onLoad`; nothing chosen loads nothing.
    ///
    /// @param opener   What shows it.
    /// @param slot     The text being loaded into, offered first and chosen to begin with.
    /// @param suitable The built-ins whose variables the slot allows, the slot's own among them.
    /// @param builtIns The built-in texts the node serves.
    /// @param onLoad   Given the words to load.
    public void load(final HasControllerNode opener,
                     final Template slot,
                     final List<Template> suitable,
                     final Map<Template, String> builtIns,
                     final Consumer<String> onLoad) {
        this.builtIns = builtIns;
        view.setChoices(suitable);
        view.setChoice(slot);
        preview(slot);
        ControllerDialog.showOnce(opener, this)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(PopupSize.resizable(640, 460))
                .caption("Load a built-in text")
                // Its choice goes back into the text it was opened from, which must not change under it
                .modal()
                .onShow(e -> view.focus())
                .onHideRequest(e -> {
                    if (e.isOk()) {
                        final Template chosen = view.getChoice();
                        if (chosen != null) {
                            onLoad.accept(builtIns.getOrDefault(chosen, ""));
                        }
                    }
                    e.hide();
                })
                .fire();
    }

    private void preview(final Template chosen) {
        view.setPreview(chosen == null
                ? ""
                : builtIns.getOrDefault(chosen, ""));
    }
}
