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

import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.Widget;

/// A Learning Flow document's Pipelines tab. Across the top, a banner ([ReadinessBanner]) says what stops the
/// document learning anything, and is shown only when something does. Beneath it is the list of pipelines that
/// learn under the document ([FeedList]); each can be opened at its Learning Status tab ([PipelineAiTab]),
/// where its learning is followed and acted on. [FeedsTabController] fills it.
public class FeedsTab extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    SimplePanel readinessPanel;
    @UiField
    ReadinessBanner readiness;
    @UiField
    FeedList feedList;

    /// Creates the tab, empty.
    public FeedsTab() {
        initWidget(BINDER.createAndBindUi(this));
    }

    /// @return The banner that says what stops the document learning anything.
    public ReadinessBanner getReadiness() {
        return readiness;
    }

    /// Shows or hides the banner saying what stops the document learning. A document that will learn needs no
    /// banner, since the Setup tab's route map already shows how each part of it is set.
    ///
    /// @param shown Whether the banner is shown.
    public void setReadinessShown(final boolean shown) {
        readinessPanel.setVisible(shown);
    }

    /// @return The list of pipelines that learn under the document.
    public FeedList getFeedList() {
        return feedList;
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, FeedsTab> {

    }
}
