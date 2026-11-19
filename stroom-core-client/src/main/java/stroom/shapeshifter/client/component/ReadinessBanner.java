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

import stroom.shapeshifter.client.component.Readiness.Fix;
import stroom.shapeshifter.client.component.Readiness.Item;
import stroom.widget.button.client.Button;
import stroom.widget.component.client.Component;
import stroom.widget.text.client.InlineLabel;
import stroom.widget.text.client.Label;

import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Widget;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/// What stops a Shapeshifter AI document learning anything ([Readiness]), across the top of its Feeds tab: a
/// banner listing what is right and what isn't, each wrong with a button that puts it right. The buttons that
/// change the document are hidden while it is read only. The tab shows it only where something is wrong.
public class ReadinessBanner extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    Label title;
    @UiField
    FlowPanel items;
    @UiField
    Label unsaved;

    private final List<Button> fixes = new ArrayList<>();
    private Consumer<Fix> onFix = fix -> {
    };

    /// Makes an empty banner.
    public ReadinessBanner() {
        initWidget(BINDER.createAndBindUi(this));
    }

    /// Shows what is right and wrong with the document.
    ///
    /// @param readiness What is right and wrong with it, where something is wrong.
    /// @param changed   Whether it has changes that aren't saved, which the checks are of.
    public void setReadiness(final Readiness readiness, final boolean changed) {
        items.clear();
        fixes.clear();
        title.setText("This document won't learn anything yet");
        for (final Item item : readiness.getItems()) {
            final FlowPanel row = new FlowPanel();
            row.addStyleName("readiness-banner__row");
            final InlineLabel mark = new InlineLabel(item.isOk()
                    ? "✓"
                    : "✕");
            mark.addStyleName(item.isOk()
                    ? "readiness-banner__mark readiness-banner__mark--ok"
                    : "readiness-banner__mark readiness-banner__mark--not");
            mark.getElement().setAttribute("aria-hidden", "true");
            row.add(mark);
            // What the mark says, for a screen reader, which is not shown the mark
            final InlineLabel said = new InlineLabel(item.isOk()
                    ? "Right: "
                    : "To put right: ");
            said.addStyleName("sr-only");
            row.add(said);
            final InlineLabel text = new InlineLabel(item.getText());
            text.addStyleName("readiness-banner__text");
            row.add(text);
            if (item.getFix() != null) {
                final Fix fix = item.getFix();
                final Button button = new Button(fix.getLabel());
                button.addStyleName("readiness-banner__fix");
                button.setOnClick(() -> onFix.accept(fix));
                button.setVisible(!isReadOnly() || showsOnly(fix));
                fixes.add(button);
                row.add(button);
            }
            items.add(row);
        }
        unsaved.setVisible(changed);
    }

    /// @param onFix Told the fix the user asks for.
    public void setOnFix(final Consumer<Fix> onFix) {
        this.onFix = onFix;
    }

    @Override
    protected void onReadOnlyChange(final boolean readOnly) {
        // What changes the document is hidden; what only shows a tab stays
        for (final Button button : fixes) {
            final String text = button.getText();
            button.setVisible(!readOnly
                              || Fix.OPEN_APPROACH.getLabel().equals(text)
                              || Fix.CHOOSE_MODEL.getLabel().equals(text)
                              || Fix.CHOOSE_TARGET_SCHEMA.getLabel().equals(text));
        }
    }

    // Whether a fix only shows where the change is made, rather than making it, so is offered read-only too
    private static boolean showsOnly(final Fix fix) {
        return fix == Fix.OPEN_APPROACH || fix == Fix.CHOOSE_MODEL || fix == Fix.CHOOSE_TARGET_SCHEMA;
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, ReadinessBanner> {

    }
}
