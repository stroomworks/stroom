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
import stroom.widget.selection.client.SelectionBox;
import stroom.widget.tickbox.client.TickBox;

import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.Widget;

import java.util.EnumMap;
import java.util.Map;

/// The content of the dialog that says which feeds a Learning Flow document's Feeds tab lists: by where
/// they stand ([FeedFilter]), each with how many feeds stand there, and whether those whose pipeline was
/// deleted are listed too. [FeedFilterEditorController] shows it.
public class FeedFilterEditor extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    SelectionBox<FeedFilter> filter;
    @UiField
    TickBox showDeleted;

    private final Map<FeedFilter, Integer> counts = new EnumMap<>(FeedFilter.class);

    /// Makes an editor listing every feed, without counts.
    public FeedFilterEditor() {
        initWidget(BINDER.createAndBindUi(this));
        filter.setDisplayValueFunction(value -> value == null
                ? ""
                : value.getDisplayValue() + (counts.containsKey(value)
                        ? " (" + counts.get(value) + ")"
                        : ""));
        filter.addItems(FeedFilter.values());
        filter.setValue(FeedFilter.ALL);
    }

    /// Shows how many feeds each choice lists, as the tab last read them.
    ///
    /// @param byFilter How many, by choice.
    public void setCounts(final Map<FeedFilter, Integer> byFilter) {
        counts.clear();
        counts.putAll(byFilter);
        // The items are drawn as they are added, so they are added again to show the new counts
        final FeedFilter chosen = filter.getValue();
        filter.clear();
        filter.addItems(FeedFilter.values());
        filter.setValue(chosen);
    }

    /// @return Which feeds to list, all of them where nothing is chosen.
    public FeedFilter getFilter() {
        final FeedFilter chosen = filter.getValue();
        return chosen == null
                ? FeedFilter.ALL
                : chosen;
    }

    /// @param value Which feeds to list.
    public void setFilter(final FeedFilter value) {
        filter.setValue(value);
    }

    /// @return Whether feeds whose pipeline was deleted are listed too.
    public boolean isShowDeleted() {
        return Boolean.TRUE.equals(showDeleted.getValue());
    }

    /// @param show Whether feeds whose pipeline was deleted are listed too.
    public void setShowDeleted(final boolean show) {
        showDeleted.setValue(show);
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, FeedFilterEditor> {

    }
}
