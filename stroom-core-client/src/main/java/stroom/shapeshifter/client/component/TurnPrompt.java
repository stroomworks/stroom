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

import stroom.widget.component.client.Component;
import stroom.widget.form.client.TextArea;
import stroom.widget.text.client.Label;

import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Widget;

import java.util.List;

/// The content of the dialog that shows what a learning run's question was put to the model with
/// (decision N9, Track 2 step 4): what a reader should know about it, then the prompt, rebuilt from the
/// run's own words over its stream; or, where it can't be shown, why. [TurnPromptController] shows it.
public class TurnPrompt extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    Label said;
    @UiField
    TextArea prompt;

    /// Makes the dialog's content, saying the prompt is being rebuilt.
    public TurnPrompt() {
        initWidget(BINDER.createAndBindUi(this));
        showRebuilding();
    }

    /// Says the prompt is being rebuilt, which reads the stream and walks the run again.
    public void showRebuilding() {
        said.setText("Rebuilding the prompt from the run's own words and its stream…");
        prompt.setVisible(false);
    }

    /// Shows the prompt, with what a reader should know about it above it.
    ///
    /// @param text  The prompt.
    /// @param notes What to know about it.
    public void showPrompt(final String text, final List<String> notes) {
        said.setText(String.join(" ", notes));
        prompt.setValue(text);
        prompt.setVisible(true);
    }

    /// Says why the prompt can't be shown.
    ///
    /// @param reason Why.
    public void showRefusal(final String reason) {
        said.setText(reason);
        prompt.setVisible(false);
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, TurnPrompt> {

    }
}
