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
import stroom.widget.form.client.TextArea;
import stroom.widget.text.client.Label;

import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Widget;

import java.util.List;

/// The content of the dialog that shows the prompt that was sent to the model for one question of a learning
/// run.
///
/// Prompts are not stored. The server rebuilds the prompt from the templates and texts recorded for the run and
/// from the run's stream. The dialog shows notes about the rebuilt prompt above the prompt itself, or, if the
/// prompt cannot be shown, the reason why. [TurnPromptController] shows it.
public class TurnPrompt extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    Label said;
    @UiField
    TextArea prompt;

    /// Makes the dialog's content, initially showing that the prompt is being rebuilt.
    public TurnPrompt() {
        initWidget(BINDER.createAndBindUi(this));
        showRebuilding();
    }

    /// Shows that the prompt is being rebuilt. Rebuilding reads the stream and replays the run, so it can take
    /// a while.
    public void showRebuilding() {
        said.setText("Rebuilding the prompt from the run's own words and its stream…");
        prompt.setVisible(false);
    }

    /// Shows the rebuilt prompt, with notes about it above it.
    ///
    /// @param text  The prompt as it was sent to the model.
    /// @param notes Notes about the rebuilt prompt, e.g. that Stroom has been upgraded since the run.
    public void showPrompt(final String text, final List<String> notes) {
        said.setText(String.join(" ", notes));
        prompt.setValue(text);
        prompt.setVisible(true);
    }

    /// Shows the reason the prompt cannot be shown, in place of the prompt.
    ///
    /// @param reason The reason, in words for the user.
    public void showRefusal(final String reason) {
        said.setText(reason);
        prompt.setVisible(false);
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, TurnPrompt> {

    }
}
