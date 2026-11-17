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

import stroom.widget.button.client.LinkButton;

import com.google.gwt.dom.client.Document;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.HasWidgets;
import com.google.gwt.user.client.ui.IsWidget;
import com.google.gwt.user.client.ui.Widget;

import java.util.Iterator;
import java.util.function.Consumer;

/// What is out of sight until someone asks for it (disclosure.css): a link with a chevron, e.g. "Advanced
/// options", that unfolds what a template puts inside it. Folded, its content is hidden; the link says which
/// it is (`aria-expanded`) and what it opens (`aria-controls`).
///
/// Whoever fills it decides when it opens of its own accord, e.g. where what it holds is not as a new
/// document has it, so that nothing changed is ever out of sight.
public class Disclosure extends Composite implements HasWidgets.ForIsWidget {

    private final LinkButton toggle = new LinkButton();
    private final FlowPanel body = new FlowPanel();
    private boolean open;
    private Consumer<Boolean> onToggle = opened -> {
    };

    /// Makes it folded, with no text.
    public Disclosure() {
        final FlowPanel root = new FlowPanel();
        root.setStyleName("disclosure");
        toggle.addStyleName("disclosure__toggle");
        body.setStyleName("disclosure__body");
        final String id = Document.get().createUniqueId();
        body.getElement().setId(id);
        toggle.getElement().setAttribute("aria-controls", id);
        root.add(toggle);
        root.add(body);
        initWidget(root);
        toggle.setOnClick(() -> {
            setOpen(!open);
            onToggle.accept(open);
        });
        setOpen(false);
    }

    /// @param text What the link says, e.g. "Advanced options".
    public void setText(final String text) {
        toggle.setText(text);
    }

    /// @param open Whether its content is shown.
    public void setOpen(final boolean open) {
        this.open = open;
        body.setVisible(open);
        toggle.setAriaExpanded(open);
    }

    /// @return Whether its content is shown.
    public boolean isOpen() {
        return open;
    }

    /// @param onToggle Told whether it is open, each time the user opens or folds it.
    public void setOnToggle(final Consumer<Boolean> onToggle) {
        this.onToggle = onToggle;
    }

    /// Puts the focus on its link.
    public void focus() {
        toggle.setFocus(true);
    }

    @Override
    public void add(final Widget widget) {
        body.add(widget);
    }

    @Override
    public void add(final IsWidget widget) {
        body.add(widget);
    }

    @Override
    public boolean remove(final Widget widget) {
        return body.remove(widget);
    }

    @Override
    public boolean remove(final IsWidget widget) {
        return body.remove(widget);
    }

    @Override
    public void clear() {
        body.clear();
    }

    @Override
    public Iterator<Widget> iterator() {
        return body.iterator();
    }
}
