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

import stroom.docref.DocRef;
import stroom.learningflow.client.component.LearnedParts.Part;
import stroom.widget.button.client.LinkButton;
import stroom.widget.component.client.AriaPanel;
import stroom.widget.component.client.Component;
import stroom.widget.text.client.InlineLabel;
import stroom.widget.text.client.Label;

import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Widget;

import java.util.List;
import java.util.function.Consumer;

/// Shows what a Smart Pipeline element has learned, on its pipeline's Structure tab, only while such an element
/// is selected. It is collapsed until asked for ([Disclosure], "What it learned"), because most people think of
/// the pipeline and its learned translation as one thing and don't need to see the learned parts.
///
/// When expanded it shows the learned translation the element uses, as a link that opens it; the translation's
/// elements in the order records pass through them ([LearnedParts]), each with its type and links to the
/// documents it uses; and a link to the pipeline's Learning Status tab, where its learning is followed. If
/// nothing has been learned, a sentence says so. [LearnedTranslationController] fills it.
public class LearnedTranslation extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    Disclosure disclosure;
    @UiField
    Label message;
    @UiField
    FlowPanel translationLine;
    @UiField
    LinkButton fragment;
    @UiField
    AriaPanel parts;
    @UiField
    LinkButton openStatus;

    private Consumer<DocRef> onOpenDocument = document -> {
    };

    /// Creates it hidden and collapsed.
    public LearnedTranslation() {
        initWidget(BINDER.createAndBindUi(this));
        showMessage("");
    }

    /// @param shown Whether it is shown, which it should be only while a Smart Pipeline element is selected.
    public void setShown(final boolean shown) {
        asWidget().setVisible(shown);
    }

    /// @return Whether it is expanded.
    public boolean isOpen() {
        return disclosure.isOpen();
    }

    /// @param onToggle Called with whether it is now expanded, each time the user expands or collapses it.
    public void setOnToggle(final Consumer<Boolean> onToggle) {
        disclosure.setOnToggle(onToggle);
    }

    /// Shows a message instead of what was learned, e.g. that nothing has been learned yet.
    ///
    /// @param text The message, or empty for none.
    public void showMessage(final String text) {
        message.setText(text);
        message.setVisible(!text.isEmpty());
        translationLine.setVisible(false);
        parts.clear();
        parts.setVisible(false);
    }

    /// Shows what was learned.
    ///
    /// @param name    The learned translation's name.
    /// @param learned The translation's elements, in order; empty if they can't be fetched, in which case a
    ///                message says so.
    public void showLearned(final String name, final List<Part> learned) {
        translationLine.setVisible(true);
        fragment.setText(name);
        fragment.setTitle("Open the learned translation " + name);
        message.setText(learned.isEmpty()
                ? "Its elements can't be read here. Open it to see them."
                : "");
        message.setVisible(learned.isEmpty());
        parts.clear();
        for (final Part part : learned) {
            parts.add(row(part));
        }
        parts.setVisible(!learned.isEmpty());
    }

    /// @param onOpenFragment Run when the learned translation's link is pressed.
    public void setOnOpenFragment(final Runnable onOpenFragment) {
        fragment.setOnClick(onOpenFragment);
    }

    /// @param onOpenDocument Called with the document whose link is pressed, e.g. an element's XSLT.
    public void setOnOpenDocument(final Consumer<DocRef> onOpenDocument) {
        this.onOpenDocument = onOpenDocument;
    }

    /// @param onOpenStatus Run when the link to the pipeline's Learning Status is pressed.
    public void setOnOpenStatus(final Runnable onOpenStatus) {
        openStatus.setOnClick(onOpenStatus);
    }

    // Builds one element's row: its type, its name, and a link to each document it uses
    private AriaPanel row(final Part part) {
        final AriaPanel row = new AriaPanel();
        row.setRole("listitem");
        row.addStyleName("learned-translation__part");
        final InlineLabel type = new InlineLabel(part.getType());
        type.addStyleName("learned-translation__type");
        row.add(type);
        final InlineLabel name = new InlineLabel(part.getName());
        name.addStyleName("learned-translation__name");
        row.add(name);
        for (final DocRef document : part.getDocuments()) {
            final String documentName = document.getName() == null
                    ? document.getUuid()
                    : document.getName();
            final LinkButton link = new LinkButton(documentName);
            link.addStyleName("learned-translation__link");
            link.setTitle("Open " + document.getType() + " " + documentName);
            link.setOnClick(() -> onOpenDocument.accept(document));
            row.add(link);
        }
        return row;
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, LearnedTranslation> {

    }
}
