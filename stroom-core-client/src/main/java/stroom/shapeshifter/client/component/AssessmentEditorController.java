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

import stroom.shapeshifter.shared.AssessmentVerdict;
import stroom.widget.component.client.Controller;
import stroom.widget.component.client.ControllerDialog;
import stroom.widget.component.client.HasControllerNode;
import stroom.widget.popup.client.PopupSize;
import stroom.widget.popup.client.PopupType;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.function.BiConsumer;

/// Records a QA person's assessment of a Smart Pipeline's translated output, in a dialog ([AssessmentEditor]),
/// from its Quality Assurance tab. OK is refused, with why beneath the comment, for an issue that doesn't say what
/// it is; the dialog stays open with what was written.
public class AssessmentEditorController extends Controller {

    private final AssessmentEditor view;

    /// Made by GIN, with its editor.
    ///
    /// @param eventBus The application's event bus.
    /// @param view     Its component.
    @Inject
    public AssessmentEditorController(final EventBus eventBus, final AssessmentEditor view) {
        super(eventBus);
        this.view = view;
        initWidget(view);
    }

    /// Shows the dialog, empty, owned by `opener` until it is closed.
    ///
    /// @param opener What shows it.
    /// @param onOk   Given what is said and what was written (null for nothing) when OK is pressed and the
    ///               assessment is whole; not called on Cancel.
    public void assess(final HasControllerNode opener, final BiConsumer<AssessmentVerdict, String> onOk) {
        view.clear();
        ControllerDialog.showOnce(opener, this)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(PopupSize.resizable(560, 340))
                .modal()
                .caption("Record an assessment")
                .onShow(e -> view.focus())
                .onHideRequest(e -> {
                    if (!e.isOk()) {
                        e.hide();
                        return;
                    }
                    final AssessmentVerdict verdict = view.getVerdict();
                    final String comment = view.getComment();
                    if (verdict == AssessmentVerdict.ISSUE && comment.isEmpty()) {
                        view.showCommentNeeded();
                        // Without a reset, the dialog's buttons stay disabled
                        e.reset();
                        return;
                    }
                    e.hide();
                    onOk.accept(verdict, comment.isEmpty()
                            ? null
                            : comment);
                })
                .fire();
    }
}
