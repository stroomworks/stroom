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

import stroom.shapeshifter.shared.AssessmentVerdict;
import stroom.widget.component.client.Component;
import stroom.widget.form.client.FormGroup;
import stroom.widget.form.client.RadioGroup;
import stroom.widget.form.client.TextArea;

import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Widget;

/// A QA person's assessment of a Smart Pipeline's translated output, in a dialog: whether it looks right or has
/// an issue for the Data Provider, and what they found, which an issue needs. [AssessmentEditorController]
/// shows it.
public class AssessmentEditor extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    RadioGroup<AssessmentVerdict> verdict;
    @UiField
    FormGroup commentGroup;
    @UiField
    TextArea comment;

    /// Makes it, saying it looks right, with nothing written.
    public AssessmentEditor() {
        initWidget(BINDER.createAndBindUi(this));
        verdict.addItem(AssessmentVerdict.LOOKS_RIGHT, "Looks right");
        verdict.addItem(AssessmentVerdict.ISSUE, "Has an issue for the Data Provider to fix");
        verdict.setOnChange(value -> commentGroup.setInvalidReason(null));
        comment.addValueChangeHandler(event -> commentGroup.setInvalidReason(null));
        clear();
    }

    /// Empties it: looks right, nothing written, nothing said to be wrong.
    public void clear() {
        verdict.setValue(AssessmentVerdict.LOOKS_RIGHT, false);
        comment.setValue("");
        commentGroup.setInvalidReason(null);
    }

    /// @return What is said of the output.
    public AssessmentVerdict getVerdict() {
        return verdict.getValue();
    }

    /// @return What the person wrote, trimmed; empty for nothing.
    public String getComment() {
        return comment.getValue() == null
                ? ""
                : comment.getValue().trim();
    }

    /// Says an issue needs saying what it is, beneath the comment, and puts the focus there.
    public void showCommentNeeded() {
        commentGroup.setInvalidReason("Say what the issue is, so that the Data Provider can fix it.");
        comment.setFocus(true);
    }

    /// Puts the focus on the choice.
    public void focus() {
        verdict.focus();
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, AssessmentEditor> {

    }
}
