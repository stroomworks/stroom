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

import stroom.learningflow.shared.Template;
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

/// Shows the dialog ([BuiltInText]) that replaces one of the texts a Learning Flow sends to the AI with one
/// of Stroom's built-in texts. It offers only the built-ins whose variables that text allows, and previews
/// the one chosen, so that a person sees what they are about to load before it replaces the document's own
/// words.
public class BuiltInTextController extends Controller {

    private final BuiltInText view;
    private Map<Template, String> builtIns = Map.of();

    /// Created by GIN.
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

    /// Shows the dialog, owned by `opener` until it is closed. OK passes the words of the chosen built-in
    /// to `onLoad`; if nothing is chosen, nothing is loaded.
    ///
    /// @param opener   The screen that opens the dialog.
    /// @param slot     The text being loaded into. Its own built-in is selected to begin with.
    /// @param suitable The built-ins whose variables the slot allows, the slot's own among them.
    /// @param builtIns The built-in texts fetched from the server, by the text they are for.
    /// @param onLoad   Called with the words to load.
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
                // Modal because the choice is written into the text the dialog was opened from, and that text
                // must not be edited while the dialog is open
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
