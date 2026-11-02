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
import stroom.alert.client.event.ConfirmEvent;
import stroom.shapeshifter.shared.XPathAssertion;
import stroom.widget.component.client.Contexts;
import stroom.widget.component.client.Controller;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// The assertions of a business rules scorer as an editable list ([XPathAssertionList]): name and
/// XPath, with add, edit and remove, unless read only ([Contexts#READ_ONLY]). An assertion's editor
/// isn't modal, so an assertion edited is found again when its OK is pressed.
public class XPathAssertionListController extends Controller {

    private final Provider<XPathAssertionEditorController> editorProvider;
    private final List<XPathAssertion> assertions = new ArrayList<>();
    private XPathAssertionList view;
    private boolean readOnly = true;

    /// Made by GIN; [#attach(XPathAssertionList)] gives it its list.
    ///
    /// @param eventBus       The application's event bus.
    /// @param editorProvider Makes an assertion's editor.
    @Inject
    public XPathAssertionListController(final EventBus eventBus,
                                        final Provider<XPathAssertionEditorController> editorProvider) {
        super(eventBus);
        this.editorProvider = editorProvider;
    }

    /// Attaches this to a list, e.g. one a template declares. The list is then this controller's
    /// widget.
    ///
    /// @param view The list.
    /// @return This, for its owner to keep.
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

    /// Shows assertions, none selected.
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

    /// @return A copy of the assertions shown, in their order.
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
