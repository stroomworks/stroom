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

import stroom.widget.component.client.Controller;
import stroom.widget.component.client.ControllerDialog;
import stroom.widget.component.client.HasControllerNode;
import stroom.widget.popup.client.PopupSize;
import stroom.widget.popup.client.PopupType;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import java.util.Map;
import java.util.function.BiConsumer;

/// Shows the dialog ([FeedFilterEditor]) that filters a list of Smart Pipelines, opened by the filter button
/// above the list's table, as Stroom's other lists are filtered. It filters by the state of each pipeline's
/// learner, and by whether pipelines that have been deleted are listed too.
///
/// The dialog is not modal, so the list keeps refreshing behind it. The counts it shows are those when it was
/// opened and are not updated while it is open, so that OK applies the choice as the dialog showed it.
public class FeedFilterEditorController extends Controller {

    private final FeedFilterEditor view;

    /// Created by GIN.
    ///
    /// @param eventBus The application's event bus.
    /// @param view     The dialog's content.
    @Inject
    public FeedFilterEditorController(final EventBus eventBus,
                                      final FeedFilterEditor view) {
        super(eventBus);
        this.view = view;
        initWidget(view);
    }

    /// Shows the filter as a dialog, owned by `opener` until it is closed.
    ///
    /// @param opener      The list it filters.
    /// @param filter      Which feeds are listed now.
    /// @param showDeleted Whether feeds whose pipeline was deleted are listed now.
    /// @param counts      The number of pipelines each choice would list, as of the list's last refresh.
    /// @param onOk        Called, when OK is pressed, with which feeds to list and whether to list feeds
    ///                    whose pipeline was deleted.
    public void edit(final HasControllerNode opener,
                     final FeedFilter filter,
                     final boolean showDeleted,
                     final Map<FeedFilter, Integer> counts,
                     final BiConsumer<FeedFilter, Boolean> onOk) {
        view.setCounts(counts);
        view.setFilter(filter);
        view.setShowDeleted(showDeleted);
        ControllerDialog.showOnce(opener, this)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(PopupSize.resizableX(440))
                .modeless()
                .caption("Which pipelines to show")
                .onHideRequest(e -> {
                    if (e.isOk()) {
                        onOk.accept(view.getFilter(), view.isShowDeleted());
                    }
                    e.hide();
                })
                .fire();
    }
}
