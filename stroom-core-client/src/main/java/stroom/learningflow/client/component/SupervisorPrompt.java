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

/// The content of the dialog that asks a person for some text before an action on a learner is taken, such as
/// the reason for rejecting a draft, giving up a learner or withdrawing a binding, or guidance for a learner.
/// [SupervisorPromptController] shows it.
public class SupervisorPrompt extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    Label message;
    @UiField
    TextArea reply;

    /// Makes an empty prompt.
    public SupervisorPrompt() {
        initWidget(BINDER.createAndBindUi(this));
    }

    /// @param text The question being asked, and what the answer will be used for.
    public void setMessage(final String text) {
        message.setText(text);
    }

    /// @param text The text to pre-fill the answer with.
    public void setReply(final String text) {
        reply.setText(text);
    }

    /// @return The answer exactly as typed.
    public String getReply() {
        return reply.getText();
    }

    /// Puts the keyboard focus in the answer box.
    public void focus() {
        reply.setFocus(true);
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, SupervisorPrompt> {

    }
}
