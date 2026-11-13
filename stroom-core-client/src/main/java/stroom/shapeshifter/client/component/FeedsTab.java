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

import com.google.gwt.core.client.GWT;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.Widget;

/// A Shapeshifter AI document's Feeds tab (design 07), the Supervisor brought into the document: what stops
/// the document learning anything ([ReadinessBanner]) across the top, only where something does; its feeds
/// ([FeedList]) on the left; and the feed chosen ([FeedDetail]) on the right. [FeedsTabController] fills it.
public class FeedsTab extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);

    @UiField
    SimplePanel readinessPanel;
    @UiField
    ReadinessBanner readiness;
    @UiField
    FeedList feedList;
    @UiField
    FeedDetail feedDetail;

    /// Makes the tab, empty.
    public FeedsTab() {
        initWidget(BINDER.createAndBindUi(this));
    }

    /// @return Whether the document will learn anything.
    public ReadinessBanner getReadiness() {
        return readiness;
    }

    /// Shows or hides what stops the document learning: a document that will learn needs no banner, since
    /// the Setup tab's route map says how each part of it is set.
    ///
    /// @param shown Whether the banner is shown.
    public void setReadinessShown(final boolean shown) {
        readinessPanel.setVisible(shown);
    }

    /// @return The document's feeds.
    public FeedList getFeedList() {
        return feedList;
    }

    /// @return The feed chosen, which its controller attaches to.
    public FeedDetail getFeedDetail() {
        return feedDetail;
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, FeedsTab> {

    }
}
