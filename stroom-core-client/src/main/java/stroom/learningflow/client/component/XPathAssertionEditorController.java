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

import stroom.alert.client.event.AlertEvent;
import stroom.learningflow.shared.XPathAssertion;
import stroom.util.shared.NullSafe;
import stroom.widget.component.client.Contexts;
import stroom.widget.component.client.Controller;
import stroom.widget.component.client.ControllerDialog;
import stroom.widget.component.client.HasControllerNode;
import stroom.widget.popup.client.PopupSize;
import stroom.widget.popup.client.PopupType;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.function.Consumer;

/// Shows a dialog ([XPathAssertionEditor]) to add or edit one of the business rules scorer's assertions.
///
/// The XPath is required; the name is optional. Both are trimmed of surrounding whitespace.
public class XPathAssertionEditorController extends Controller {

    private final XPathAssertionEditor view;

    /// Made by GIN.
    ///
    /// @param eventBus The application's event bus.
    /// @param view     The dialog's content.
    @Inject
    public XPathAssertionEditorController(final EventBus eventBus, final XPathAssertionEditor view) {
        super(eventBus);
        this.view = view;
        initWidget(view);
        watch(Contexts.READ_ONLY, view::setReadOnly);
    }

    /// Shows the editor as a dialog, owned by `opener` until it is closed. On OK, if an XPath has been
    /// entered the assertion is passed to `onOk`; otherwise an error is shown and the dialog stays open.
    ///
    /// @param opener    The controller that opens the dialog and owns it while it is shown.
    /// @param assertion The assertion to edit, or a new, empty one.
    /// @param onOk      Called with the edited assertion when OK is pressed and the input is valid.
    public void edit(final HasControllerNode opener,
                     final XPathAssertion assertion,
                     final Consumer<XPathAssertion> onOk) {
        view.setName(assertion.getName());
        view.setXpath(assertion.getXpath());
        ControllerDialog.showOnce(opener, this)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(PopupSize.resizableX(700))
                .caption("Assertion")
                .onShow(e -> view.focus())
                .onHideRequest(e -> {
                    if (!e.isOk()) {
                        e.hide();
                        return;
                    }
                    final XPathAssertion written = write();
                    if (written.getXpath().isEmpty()) {
                        AlertEvent.fireError(this, "An assertion needs an XPath.", e::reset);
                    } else {
                        onOk.accept(written);
                        e.hide();
                    }
                })
                .fire();
    }

    private XPathAssertion write() {
        return new XPathAssertion(NullSafe.trim(view.getName()), NullSafe.trim(view.getXpath()));
    }
}
