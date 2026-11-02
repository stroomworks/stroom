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

import stroom.editor.client.component.CodeEditorController;
import stroom.widget.component.client.Controller;
import stroom.widget.component.client.ControllerDialog;
import stroom.widget.component.client.HasControllerNode;
import stroom.widget.popup.client.PopupSize;
import stroom.widget.popup.client.PopupType;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;
import edu.ycp.cs.dh.acegwt.client.ace.AceEditorMode;

import java.util.function.Consumer;

/// Answers a turn in place of whoever answered it (A28), in a dialog ([TurnAnswerEditor]).
///
/// An editor and not a prompt box, because an answer is often a configuration (a Data Splitter document,
/// a stylesheet) and a single-line box cannot hold one, let alone show a person what they are editing.
///
/// Not modal: what it answers is taken when it is opened, so the list behind it may be read, and moved
/// about, while it is open.
public class TurnAnswerEditorController extends Controller {

    private final CodeEditorController editor;

    /// Made by GIN, with its editor.
    ///
    /// @param eventBus                     The application's event bus.
    /// @param view                         Its component.
    /// @param codeEditorControllerProvider Makes the code editor's controller.
    @Inject
    public TurnAnswerEditorController(final EventBus eventBus,
                                      final TurnAnswerEditor view,
                                      final Provider<CodeEditorController> codeEditorControllerProvider) {
        super(eventBus);
        initWidget(view);
        editor = make(codeEditorControllerProvider).attach(view.getEditor());
        editor.setMode(AceEditorMode.XML);
        editor.getOptions().getLineNumbersOption().setOn();
    }

    /// Shows an answer as a dialog, owned by `opener` until it is closed.
    ///
    /// @param opener   What shows it.
    /// @param caption  The dialog's caption, naming the turn and its question.
    /// @param answer   The answer as it stands, perhaps empty.
    /// @param readOnly Whether it may only be read: it then closes without asking anything.
    /// @param onOk     Given the answer as edited when OK is pressed; never where it may only be read.
    public void edit(final HasControllerNode opener,
                     final String caption,
                     final String answer,
                     final boolean readOnly,
                     final Consumer<String> onOk) {
        editor.setText(answer);
        editor.setReadOnly(readOnly);
        ControllerDialog.showOnce(opener, this)
                .popupType(readOnly
                        ? PopupType.CLOSE_DIALOG
                        : PopupType.OK_CANCEL_DIALOG)
                .popupSize(PopupSize.resizable(900, 600))
                .modeless()
                .caption(caption)
                .onShow(e -> editor.focus())
                .onHideRequest(e -> {
                    final String edited = editor.getText();
                    e.hide();
                    if (e.isOk() && !readOnly) {
                        onOk.accept(edited);
                    }
                })
                .fire();
    }
}
