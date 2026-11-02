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

import stroom.shapeshifter.shared.LearnerState;
import stroom.widget.component.client.Component;
import stroom.widget.dropdowntree.client.DropDownBox;
import stroom.widget.tickbox.client.TickBox;

import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.FlowPanel;
import com.google.gwt.user.client.ui.Widget;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/// The content of the dialog that narrows which learning pipelines the Supervisor lists (design 06 A142):
/// a document, the states a pipeline's learner is in (a tick box each), and whether deleted pipelines are
/// shown (A137). [LearnerFilterEditorController] shows it and fills its document box.
public class LearnerFilterEditor extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    private final Map<LearnerState, TickBox> stateBoxes = new EnumMap<>(LearnerState.class);

    @UiField
    DropDownBox document;
    @UiField
    FlowPanel states;
    @UiField
    TickBox showDeletedPipelines;

    /// Makes an editor with every state unticked.
    public LearnerFilterEditor() {
        initWidget(BINDER.createAndBindUi(this));
        for (final LearnerState state : LearnerState.values()) {
            final TickBox box = new TickBox();
            box.addStyleName("allow-focus");
            box.setLabel(state.getDisplayValue());
            stateBoxes.put(state, box);
            states.add(box);
        }
    }

    /// @return The document box, which its controller attaches a document picker to.
    public DropDownBox getDocumentBox() {
        return document;
    }

    /// Empty for every state, which is what "not narrowed by state" means to the query.
    ///
    /// @return The states ticked.
    public List<LearnerState> getStates() {
        final List<LearnerState> ticked = new ArrayList<>();
        stateBoxes.forEach((state, box) -> {
            if (Boolean.TRUE.equals(box.getValue())) {
                ticked.add(state);
            }
        });
        // Every state ticked is the same question as none ticked, and the query reads an empty list as
        // "not narrowed by state": said as the smaller of the two, so that the screen does not report
        // itself as filtered when it is showing everything.
        return ticked.size() == stateBoxes.size()
                ? List.of()
                : ticked;
    }

    /// @param ticked The states to tick, or null for none.
    public void setStates(final List<LearnerState> ticked) {
        stateBoxes.forEach((state, box) -> box.setValue(ticked != null && ticked.contains(state)));
    }

    /// @return Whether a deleted pipeline's learning is shown too (design 06 A137).
    public boolean isShowDeletedPipelines() {
        return Boolean.TRUE.equals(showDeletedPipelines.getValue());
    }

    /// @param show Whether a deleted pipeline's learning is shown too.
    public void setShowDeletedPipelines(final boolean show) {
        showDeletedPipelines.setValue(show);
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, LearnerFilterEditor> {

    }
}
