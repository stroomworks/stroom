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
import stroom.alert.client.event.ConfirmEvent;
import stroom.learningflow.shared.XPathAssertion;
import stroom.widget.component.client.Contexts;
import stroom.widget.component.client.Controller;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// Manages the editable list of the business rules scorer's assertions ([XPathAssertionList]), adding,
/// editing and removing them unless the document is read-only ([Contexts#READ_ONLY]).
///
/// The assertion editor is not modal, so the list may change while an assertion is being edited. The edited
/// assertion is therefore looked up again when OK is pressed, and the change is dropped with a warning if it
/// has been removed in the meantime.
public class XPathAssertionListController extends Controller {

    private final Provider<XPathAssertionEditorController> editorProvider;
    private final List<XPathAssertion> assertions = new ArrayList<>();
    private XPathAssertionList view;
    private boolean readOnly = true;

    /// Made by GIN. Call [#attach(XPathAssertionList)] to give it the list to manage.
    ///
    /// @param eventBus       The application's event bus.
    /// @param editorProvider Makes the dialog for editing a single assertion.
    @Inject
    public XPathAssertionListController(final EventBus eventBus,
                                        final Provider<XPathAssertionEditorController> editorProvider) {
        super(eventBus);
        this.editorProvider = editorProvider;
    }

    /// Attaches this controller to a list, e.g. one declared in its owner's template. The list becomes this
    /// controller's widget.
    ///
    /// @param view The list.
    /// @return This controller, for its owner to keep.
    public XPathAssertionListController attach(final XPathAssertionList view) {
        this.view = Objects.requireNonNull(view, "view");
        initWidget(view);
        view.setOnAdd(this::onAdd);
        view.setOnEdit(this::onEdit);
        view.setOnRemove(this::onRemove);
        watch(Contexts.READ_ONLY, readOnly -> {
            this.readOnly = readOnly;
            view.setReadOnly(readOnly);
        });
        return this;
    }

    /// Shows the given assertions, with none selected.
    ///
    /// @param assertions The assertions, or null for none.
    public void read(final List<XPathAssertion> assertions) {
        this.assertions.clear();
        if (assertions != null) {
            this.assertions.addAll(assertions);
        }
        view.clearSelection();
        update();
    }

    /// @return A copy of the assertions shown, in order.
    public List<XPathAssertion> write() {
        return new ArrayList<>(assertions);
    }

    private void onAdd() {
        if (!readOnly) {
            editorProvider.get().edit(this, new XPathAssertion("", ""), assertion -> {
                assertions.add(assertion);
                update();
            });
        }
    }

    private void onEdit() {
        final XPathAssertion existing = view.getSelected();
        if (!readOnly && existing != null) {
            editorProvider.get().edit(this, existing, assertion -> {
                final int index = assertions.indexOf(existing);
                if (index < 0) {
                    AlertEvent.fireWarn(this,
                            "The assertion '" + existing.getName()
                            + "' was removed while it was being edited, so the change is not kept.",
                            null);
                } else {
                    assertions.set(index, assertion);
                    update();
                }
            });
        }
    }

    private void onRemove() {
        final XPathAssertion selected = view.getSelected();
        if (!readOnly && selected != null) {
            ConfirmEvent.fire(this, "Remove the assertion '" + selected.getName() + "'?", ok -> {
                if (ok && !isDisposed()) {
                    assertions.remove(selected);
                    view.clearSelection();
                    update();
                }
            });
        }
    }

    private void update() {
        view.setData(write());
    }
}
