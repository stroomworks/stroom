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

import stroom.docref.DocRef;
import stroom.explorer.client.component.DocSelectionController;
import stroom.security.shared.DocumentPermission;
import stroom.shapeshifter.shared.LearnerCriteria;
import stroom.shapeshifter.shared.ShapeshifterAiDoc;
import stroom.util.shared.NullSafe;
import stroom.widget.component.client.Controller;
import stroom.widget.component.client.ControllerDialog;
import stroom.widget.component.client.HasControllerNode;
import stroom.widget.popup.client.PopupSize;
import stroom.widget.popup.client.PopupType;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;

import java.util.function.Consumer;

/// Which learning pipelines the Supervisor lists (design 06 A142), in a dialog ([LearnerFilterEditor]):
/// every one of every document the person may read until somebody narrows it, by document, by the states
/// a pipeline's learner is in, and to those whose pipeline still exists unless deleted ones are asked for
/// (A137).
///
/// Every choice goes into the query rather than filtering its answer, as the page's total already does.
///
/// Not modal: a filter whose value comes from the page, which may be read while it is open. What it was
/// opened with is taken when it is opened, so that one dialog's answer is not written over another's.
public class LearnerFilterEditorController extends Controller {

    private final LearnerFilterEditor view;
    private final DocSelectionController documentSelection;

    /// Made by GIN, with its editor and its document picker.
    ///
    /// @param eventBus                  The application's event bus.
    /// @param view                      Its component.
    /// @param documentSelectionProvider Makes the document picker's controller.
    @Inject
    public LearnerFilterEditorController(final EventBus eventBus,
                                         final LearnerFilterEditor view,
                                         final Provider<DocSelectionController> documentSelectionProvider) {
        super(eventBus);
        this.view = view;
        initWidget(view);
        documentSelection = make(documentSelectionProvider);
        documentSelection.attach(view.getDocumentBox());
        documentSelection.setIncludedTypes(ShapeshifterAiDoc.TYPE);
        documentSelection.setRequiredPermissions(DocumentPermission.VIEW);
    }

    /// Shows the filter as a dialog, owned by `opener` until it is closed.
    ///
    /// @param opener The list it filters.
    /// @param opened What is being asked for now.
    /// @param onOk   Given what is asked for as the form has it, when OK is pressed.
    public void edit(final HasControllerNode opener,
                     final LearnerCriteria opened,
                     final Consumer<LearnerCriteria> onOk) {
        read(opened);
        ControllerDialog.showOnce(opener, this)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(PopupSize.resizable(600, 520))
                .modeless()
                .caption("Which learning pipelines to show")
                .onHideRequest(e -> {
                    if (e.isOk()) {
                        onOk.accept(write(opened));
                    }
                    e.hide();
                })
                .fire();
    }

    /// Whether anything is being narrowed, or widened to deleted pipelines, so that the screen can say a
    /// filter is on rather than leaving a person wondering where the rest went.
    ///
    /// @param criteria What is being asked for.
    /// @return Whether it is anything but everything.
    public static boolean isFiltering(final LearnerCriteria criteria) {
        return criteria.getDocUuid() != null
               || !NullSafe.list(criteria.getStates()).isEmpty()
               || criteria.isShowDeletedPipelines();
    }

    private void read(final LearnerCriteria criteria) {
        documentSelection.setSelectedEntityReference(criteria.getDocUuid() == null
                ? null
                : new DocRef(ShapeshifterAiDoc.TYPE, criteria.getDocUuid()), true);
        view.setStates(criteria.getStates());
        view.setShowDeletedPipelines(criteria.isShowDeletedPipelines());
    }

    // The criteria as the form has it, keeping the page request it was opened with: a filter changes what
    // is asked for, not how much of it.
    private LearnerCriteria write(final LearnerCriteria opened) {
        final DocRef document = documentSelection.getSelectedEntityReference();
        return new LearnerCriteria(
                opened.getPageRequest(),
                opened.getSortList(),
                NullSafe.get(document, DocRef::getUuid),
                view.getStates(),
                view.isShowDeletedPipelines());
    }
}
