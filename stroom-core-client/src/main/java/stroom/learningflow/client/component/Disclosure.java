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

import stroom.widget.button.client.LinkButton;

import com.google.gwt.dom.client.Document;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.HasWidgets;
import com.google.gwt.user.client.ui.IsWidget;
import com.google.gwt.user.client.ui.Widget;

import java.util.Iterator;
import java.util.function.Consumer;

/// A section that is hidden until someone asks for it: a link with a chevron, e.g. "Advanced options", that
/// expands to show the widgets a template puts inside it (styled by `disclosure.css`). When collapsed, its
/// content is hidden; the link tells assistive technology whether it is expanded (`aria-expanded`) and what
/// it controls (`aria-controls`).
///
/// The screen that uses it decides when to expand it automatically, e.g. when its content holds a setting
/// that differs from a new document's, so that a changed setting is never hidden.
public class Disclosure extends Composite implements HasWidgets.ForIsWidget {

    private final LinkButton toggle = new LinkButton();
    private final FlowPanel body = new FlowPanel();
    private boolean open;
    private Consumer<Boolean> onToggle = opened -> {
    };

    /// Creates it collapsed, with no link text.
    public Disclosure() {
        final FlowPanel root = new FlowPanel();
        root.setStyleName("disclosure");
        toggle.addStyleName("disclosure__toggle");
        body.setStyleName("disclosure__body");
        final String id = Document.get().createUniqueId();
        body.getElement().setId(id);
        toggle.setAriaControls(id);
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

    /// @param open Whether to show its content.
    public void setOpen(final boolean open) {
        this.open = open;
        body.setVisible(open);
        toggle.setAriaExpanded(open);
    }

    /// @return Whether its content is shown.
    public boolean isOpen() {
        return open;
    }

    /// @param onToggle Called with whether it is now expanded, each time the user expands or collapses it.
    public void setOnToggle(final Consumer<Boolean> onToggle) {
        this.onToggle = onToggle;
    }

    /// Puts the focus on the link.
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
