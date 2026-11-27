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

import stroom.learningflow.client.component.Readiness.Fix;
import stroom.learningflow.client.component.Readiness.Item;
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

/// A banner across the top of a Learning Flow document's Pipelines tab (`FeedsTab`) that explains why the
/// document will not learn anything yet ([Readiness]).
///
/// It lists every readiness check, passed or failed, and gives each failed one a button that fixes it. While
/// the document is read-only, the buttons that would change it are hidden; those that only show a setting
/// remain. The tab shows the banner only when a check has failed.
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

    /// Shows the results of the document's readiness checks.
    ///
    /// @param readiness The results, at least one of which has failed.
    /// @param changed   Whether the document has unsaved changes. The checks include those changes, so the
    ///                  banner says so.
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
            // The mark is hidden from screen readers, so this hidden text says the same thing for them
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
                button.setVisible(!isReadOnly() || fix == Fix.OPEN_APPROACH || fix == Fix.CHOOSE_MODEL);
                fixes.add(button);
                row.add(button);
            }
            items.add(row);
        }
        unsaved.setVisible(changed);
    }

    /// @param onFix Called with the fix whose button the user pressed.
    public void setOnFix(final Consumer<Fix> onFix) {
        this.onFix = onFix;
    }

    @Override
    protected void onReadOnlyChange(final boolean readOnly) {
        // Hide the fixes that would change the document; keep those that only show a setting
        for (final Button button : fixes) {
            final String text = button.getText();
            button.setVisible(!readOnly
                              || Fix.OPEN_APPROACH.getLabel().equals(text)
                              || Fix.CHOOSE_MODEL.getLabel().equals(text));
        }
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, ReadinessBanner> {

    }
}
