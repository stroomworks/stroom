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

package stroom.dashboard.client.component;

import stroom.query.client.component.RunQueryButton;
import stroom.query.client.component.StroomQLEditor;
import stroom.svg.shared.SvgImage;
import stroom.util.shared.Severity;
import stroom.widget.button.client.ToolbarButton;
import stroom.widget.component.client.Component;

import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Widget;

import java.util.Objects;
import java.util.function.Consumer;

/// A dashboard's StroomQL Query pane: the query's text (its help beside its editor, shown here by
/// its controller) below buttons that make an analytic rule or a report from it, a button for the
/// query's messages, shown when there are some, and Run.
public class StroomQLQueryPane extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);
    private static final String INVISIBLE = "toolbar-button--invisible";

    @UiField
    ToolbarButton createRule;
    @UiField
    ToolbarButton createReport;
    @UiField
    ToolbarButton messages;
    @UiField
    RunQueryButton queryButtons;
    @UiField
    StroomQLEditor editor;

    private Consumer<StroomQLQueryPaneAction> onAction = action -> {
    };

    /// Makes the pane, with no query.
    public StroomQLQueryPane() {
        initWidget(BINDER.createAndBindUi(this));
        createRule.setOnClick(() -> onAction.accept(StroomQLQueryPaneAction.CREATE_RULE));
        createReport.setOnClick(() -> onAction.accept(StroomQLQueryPaneAction.CREATE_REPORT));
        messages.setOnClick(() -> onAction.accept(StroomQLQueryPaneAction.SHOW_MESSAGES));
        queryButtons.setOnStart(() -> onAction.accept(StroomQLQueryPaneAction.RUN));
        queryButtons.setEnabled(true);
    }

    /// @return The query's text, which its controller attaches the text's controller to.
    public StroomQLEditor getEditor() {
        return editor;
    }

    /// Shows the messages button when the query has messages, as their most severe kind.
    ///
    /// @param shown    Whether there are messages.
    /// @param severity The most severe of them, if there are any.
    public void setMessages(final boolean shown, final Severity severity) {
        messages.asWidget().setStyleName(INVISIBLE, !shown);
        if (shown) {
            if (Severity.FATAL_ERROR.equals(severity) || Severity.ERROR.equals(severity)) {
                messages.setSvg(SvgImage.ERROR);
                messages.setTitle("Show Errors");
            } else if (Severity.WARNING.equals(severity)) {
                messages.setSvg(SvgImage.ALERT);
                messages.setTitle("Show Warnings");
            } else if (Severity.INFO.equals(severity)) {
                messages.setSvg(SvgImage.INFO);
                messages.setTitle("Show Messages");
            }
        }
    }

    /// @param searching Whether the query is running, which Run shows (it stops the query while it
    ///                  runs).
    public void setSearching(final boolean searching) {
        queryButtons.setSearching(searching);
    }

    /// @param onAction Given each action the user takes.
    public void setOnAction(final Consumer<StroomQLQueryPaneAction> onAction) {
        this.onAction = Objects.requireNonNull(onAction, "onAction");
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, StroomQLQueryPane> {

    }


    // --------------------------------------------------------------------------------


    /// An action on the StroomQL Query pane.
    public enum StroomQLQueryPaneAction {
        /// Makes an analytic rule from the query.
        CREATE_RULE,
        /// Makes a report from the query.
        CREATE_REPORT,
        /// Shows the query's messages.
        SHOW_MESSAGES,
        /// Runs (or stops) the query.
        RUN
    }
}
