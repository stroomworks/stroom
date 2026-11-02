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

import stroom.alert.client.event.AlertEvent;
import stroom.shapeshifter.shared.XPathAssertion;
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

/// Adds or edits one of a business rules scorer's assertions in a dialog ([XPathAssertionEditor]): its
/// name and its XPath, which must be given. Both are trimmed.
public class XPathAssertionEditorController extends Controller {

    private final XPathAssertionEditor view;

    /// Made by GIN, with its content.
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

    /// Shows the editor as a dialog, owned by `opener` until it is closed. OK gives the assertion to
    /// `onOk` if it has an XPath; otherwise it says so and the dialog stays open.
    ///
    /// @param opener    What shows it.
    /// @param assertion The assertion to edit, or a new one.
    /// @param onOk      What to do with the assertion when OK is pressed.
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
