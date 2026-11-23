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

/// Which feeds a Learning Flow document's Feeds tab lists, in a dialog ([FeedFilterEditor]) opened by the
/// filter button of the tab's table, as Stroom's other lists are filtered: by where the feeds stand, and
/// whether those whose pipeline was deleted are listed too.
///
/// Not modal: the tab goes on refreshing behind it. What it was opened with is taken when it is opened, so
/// that the answer is what the dialog showed.
public class FeedFilterEditorController extends Controller {

    private final FeedFilterEditor view;

    /// Made by GIN, with its editor.
    ///
    /// @param eventBus The application's event bus.
    /// @param view     Its component.
    @Inject
    public FeedFilterEditorController(final EventBus eventBus,
                                      final FeedFilterEditor view) {
        super(eventBus);
        this.view = view;
        initWidget(view);
    }

    /// Shows the filter as a dialog, owned by `opener` until it is closed.
    ///
    /// @param opener      The tab whose feeds it filters.
    /// @param filter      Which feeds are listed now.
    /// @param showDeleted Whether feeds whose pipeline was deleted are listed now.
    /// @param counts      How many feeds each choice lists, as the tab last read them.
    /// @param onOk        Given which feeds to list and whether deleted ones too, when OK is pressed.
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
