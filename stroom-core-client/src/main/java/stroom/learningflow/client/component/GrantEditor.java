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

import stroom.widget.component.client.Component;
import stroom.widget.text.client.Label;
import stroom.widget.valuespinner.client.ValueSpinner;

import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Widget;

/// The content of the dialog that gives a parked session more to spend. It shows what the session has spent
/// and been granted compared with its document's budgets, then fields for more tokens, which apply over the
/// session's whole life, and more minutes, which apply to each run. [GrantEditorController] shows it.
public class GrantEditor extends Component {

    // A grant is limited only to stop a typing mistake granting a session a huge amount: at most a billion
    // tokens, and a day for each run.
    private static final long MAX_TOKENS = 1_000_000_000L;
    private static final long MAX_MINUTES = 24L * 60L;

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    Label soFar;
    @UiField
    ValueSpinner tokens;
    @UiField
    ValueSpinner minutes;

    /// Creates an empty editor, granting nothing.
    public GrantEditor() {
        initWidget(BINDER.createAndBindUi(this));
        tokens.setMin(0L);
        tokens.setMax(MAX_TOKENS);
        tokens.setDelta(1_000);
        minutes.setMin(0L);
        minutes.setMax(MAX_MINUTES);
        minutes.setDelta(1);
    }

    /// @param text What the session has spent and been granted, compared with its document's budgets.
    public void setSoFar(final String text) {
        soFar.setText(text);
    }

    /// @return The extra tokens to grant, or zero for none.
    public long getTokens() {
        return value(tokens);
    }

    /// @param value The extra tokens to grant.
    public void setTokens(final long value) {
        tokens.setValue(value);
    }

    /// @return The extra minutes to grant each run, or zero for none.
    public long getMinutes() {
        return value(minutes);
    }

    /// @param value The extra minutes to grant each run.
    public void setMinutes(final long value) {
        minutes.setValue(value);
    }

    /// Puts the focus on the first field, the tokens.
    public void focus() {
        tokens.focus();
    }

    private static long value(final ValueSpinner spinner) {
        final Long value = spinner.getValue();
        return value == null
                ? 0L
                : value;
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, GrantEditor> {

    }
}
