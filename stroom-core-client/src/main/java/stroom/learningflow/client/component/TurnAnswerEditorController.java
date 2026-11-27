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

/// Shows a dialog ([TurnAnswerEditor]) in which a person answers a turn of a learning run in place of the
/// model, or views or edits the answer the turn already has. It is opened from a Smart Pipeline's Learning
/// Status tab.
///
/// It uses a code editor rather than a simple text box because an answer is often a configuration (a Data
/// Splitter document or an XSLT stylesheet), which a person needs to see laid out to edit.
///
/// The dialog is not modal. The turn being answered is fixed when the dialog opens, so the list behind it can
/// still be read and scrolled while it is open.
public class TurnAnswerEditorController extends Controller {

    private final CodeEditorController editor;

    /// Made by GIN.
    ///
    /// @param eventBus                     The application's event bus.
    /// @param view                         The dialog's content.
    /// @param codeEditorControllerProvider Makes the controller for the code editor in the dialog.
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

    /// Shows an answer in a dialog, owned by `opener` until it is closed.
    ///
    /// @param opener   The controller that opens the dialog and owns it while it is shown.
    /// @param caption  The dialog's caption, naming the turn and its question.
    /// @param answer   The current answer, which may be empty.
    /// @param readOnly Whether the answer may only be viewed. If so, the dialog has only a Close button.
    /// @param onOk     Called with the edited answer when OK is pressed. Never called when `readOnly` is true.
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
