/*
 * Copyright 2016 Crown Copyright
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

package stroom.core.client.component;

import stroom.widget.component.client.Component;

import com.google.gwt.core.client.GWT;
import com.google.gwt.dom.client.Document;
import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.Style.Display;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.IsWidget;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.Widget;

/// The page's root: the screen shown (the main screen or a full screen view), with the host page's
/// loading message (its `#loading` element) over it while Stroom starts.
public class AppPage extends Component {

    private static final Binder BINDER = GWT.create(Binder.class);
    // Hidden, not just transparent, so screen readers stop reading "Loading..."
    private static final String LOADING_HIDDEN = "app-page__loading--hidden";

    @UiField
    SimplePanel content;

    /// Makes the root, showing nothing until it is given a screen.
    public AppPage() {
        initWidget(BINDER.createAndBindUi(this));
    }

    /// Shows a screen in the page, in place of the one before, and hides the host page's logo.
    ///
    /// @param view The screen.
    public void setContent(final IsWidget view) {
        content.setWidget(view.asWidget());
        final Element logo = Document.get().getElementById("logo");
        if (logo != null) {
            logo.getStyle().setDisplay(Display.NONE);
            logo.setAttribute("aria-hidden", "true");
        }
    }

    /// Shows the host page's loading message.
    ///
    /// @param message What is being done, or null to keep the message shown.
    public void showWorking(final String message) {
        // Plain DOM lookups, not RootPanel.get(id): #loadingText is inside #loading, and once
        // #loading is a RootPanel, making another RootPanel inside it fails GWT's assertion
        final Document document = Document.get();
        final Element loading = document.getElementById("loading");
        if (loading != null) {
            loading.removeClassName(LOADING_HIDDEN);
        }
        final Element loadingText = document.getElementById("loadingText");
        if (message != null && loadingText != null) {
            loadingText.setInnerText(message);
        }
    }

    /// Hides the host page's loading message.
    public void hideWorking() {
        final Element loading = Document.get().getElementById("loading");
        if (loading != null) {
            loading.addClassName(LOADING_HIDDEN);
        }
    }


    // --------------------------------------------------------------------------------


    interface Binder extends UiBinder<Widget, AppPage> {

    }
}
