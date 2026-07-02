/*
 * Copyright 2016-2026 Crown Copyright
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

package stroom.floormap.client.presenter;

import stroom.alert.client.event.AlertEvent;
import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.document.client.DocInitialisationHandler;
import stroom.explorer.client.event.RefreshExplorerTreeEvent;
import stroom.explorer.client.presenter.DocSelectionBoxPresenter;
import stroom.explorer.shared.ExplorerResource;
import stroom.explorer.shared.ExplorerServiceDeleteRequest;
import stroom.floormap.shared.FloorMapDoc;
import stroom.floormap.shared.FloorMapResource;
import stroom.planb.shared.PlanBDoc;
import stroom.planb.shared.PlanBDocResource;
import stroom.planb.shared.StateType;
import stroom.security.shared.DocumentPermission;
import stroom.sqlstore.shared.SqlTemporalStoreDoc;
import stroom.task.client.TaskMonitorFactory;
import stroom.widget.popup.client.event.DisablePopupEvent;
import stroom.widget.popup.client.event.EnablePopupEvent;
import stroom.widget.popup.client.event.HidePopupRequestEvent;
import stroom.widget.popup.client.event.ShowPopupEvent;
import stroom.widget.popup.client.presenter.PopupSize;
import stroom.widget.popup.client.presenter.PopupType;
import stroom.widget.popup.client.view.DialogAction;

import com.google.gwt.core.client.GWT;
import com.google.gwt.user.client.ui.Focus;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Initialisation handler for new {@link FloorMapDoc} documents.
 *
 * <p>Displays a modal dialog requiring the user to select both a
 * <strong>Facts Store</strong> ({@link SqlTemporalStoreDoc}) and an
 * <strong>Events Store</strong> ({@link PlanBDoc} with
 * {@link StateType#TEMPORAL_STATE}). The OK button remains disabled
 * until both selections are valid.</p>
 *
 * <p>On OK: the new document is patched with the selected store
 * references and saved. On Cancel: the document is deleted from
 * the explorer.</p>
 *
 * @see DocInitialisationHandler
 */
public class FloorMapInitPresenter
        extends MyPresenterWidget<FloorMapInitPresenter.FloorMapInitView>
        implements DocInitialisationHandler {

    private static final FloorMapResource FLOOR_MAP_RESOURCE =
            GWT.create(FloorMapResource.class);
    private static final PlanBDocResource PLAN_B_RESOURCE =
            GWT.create(PlanBDocResource.class);
    private static final ExplorerResource EXPLORER_RESOURCE =
            GWT.create(ExplorerResource.class);

    /** Default dialog width in pixels. */
    private static final int DIALOG_WIDTH = 320;
    /** Default dialog height in pixels. */
    private static final int DIALOG_HEIGHT = 300;

    private final DocSelectionBoxPresenter factsStorePresenter;
    private final DocSelectionBoxPresenter eventsStorePresenter;
    private final RestFactory restFactory;

    /**
     * Tracks whether the currently selected PlanB doc has been
     * validated as a {@link StateType#TEMPORAL_STATE} store.
     */
    private boolean eventsStoreValid = false;

    /** The DocRef of the newly created document being initialised. May be null when dialog is not showing. */
    private DocRef docRef;

    /** Callback to signal the outcome to the caller. Never null while the dialog is showing. */
    private Consumer<Boolean> completionCallback;

    /**
     * Creates a new {@code FloorMapInitPresenter}.
     *
     * @param eventBus                        the event bus; never null
     * @param view                            the view implementation; never null
     * @param docSelectionBoxPresenterProvider provider for doc selection widgets;
     *                                        never null
     * @param restFactory                     factory for REST calls; never null
     */
    @Inject
    public FloorMapInitPresenter(
            final EventBus eventBus,
            final FloorMapInitView view,
            final Provider<DocSelectionBoxPresenter> docSelectionBoxPresenterProvider,
            final RestFactory restFactory) {
        super(eventBus, view);
        this.restFactory = restFactory;

        // Facts Store = SqlTemporalStore
        factsStorePresenter = docSelectionBoxPresenterProvider.get();
        factsStorePresenter.setIncludedTypes(SqlTemporalStoreDoc.TYPE);
        factsStorePresenter.setRequiredPermissions(DocumentPermission.USE);
        view.setFactsStoreView(factsStorePresenter.getView());

        // Events Store = PlanB (validated as TEMPORAL_STATE)
        eventsStorePresenter = docSelectionBoxPresenterProvider.get();
        eventsStorePresenter.setIncludedTypes(PlanBDoc.TYPE);
        eventsStorePresenter.setRequiredPermissions(DocumentPermission.USE);
        view.setEventsStoreView(eventsStorePresenter.getView());
    }

    @Override
    protected void onBind() {
        super.onBind();
        //noinspection unused e
        registerHandler(factsStorePresenter.addDataSelectionHandler(e -> validate()));
        //noinspection unused e
        registerHandler(eventsStorePresenter.addDataSelectionHandler(e -> {
            eventsStoreValid = false;
            validateEventsStore();
        }));
    }

    // -- Validation --

    /**
     * Asynchronously validates that the selected Events Store is a
     * PlanB document with {@link StateType#TEMPORAL_STATE}.
     *
     * <p>Fetches the {@link PlanBDoc} via REST and inspects its
     * {@code stateType}. If the type is wrong, a warning is shown
     * and the OK button remains disabled.</p>
     *
     * <p>Precondition: called only when the Events Store picker
     * selection changes.</p>
     *
     * <p>Postcondition: {@link #eventsStoreValid} is set, and
     * {@link #validate()} is called to update the OK button state.</p>
     */
    private void validateEventsStore() {
        final DocRef selected = eventsStorePresenter.getSelectedEntityReference();
        if (selected == null) {
            eventsStoreValid = false;
            validate();
            return;
        }
        // Async fetch the PlanBDoc to check stateType
        //noinspection unused error
        restFactory
                .create(PLAN_B_RESOURCE)
                .method(res -> res.fetch(selected.getUuid()))
                .onSuccess(planBDoc -> {
                    if (planBDoc.getStateType() == StateType.TEMPORAL_STATE) {
                        eventsStoreValid = true;
                    } else {
                        eventsStoreValid = false;
                        AlertEvent.fireWarn(this,
                                "The selected Plan B store '"
                                        + selected.getName()
                                        + "' is a "
                                        + planBDoc.getStateType().getDisplayValue()
                                        + ", not a Temporal State store. "
                                        + "Please select a Temporal State store.",
                                null);
                    }
                    validate();
                })
                .onFailure(error -> {
                    eventsStoreValid = false;
                    validate();
                })
                .taskMonitorFactory(this)
                .exec();
    }

    /**
     * Updates the OK button enabled state based on current validity.
     *
     * <p>The OK button is enabled only when both the Facts Store has
     * a non-null selection and the Events Store has been validated as
     * a {@link StateType#TEMPORAL_STATE} PlanB store.</p>
     */
    private void validate() {
        final boolean factsOk =
                factsStorePresenter.getSelectedEntityReference() != null;
        final boolean valid = factsOk && eventsStoreValid;
        if (valid) {
            EnablePopupEvent.builder(this)
                    .action(DialogAction.OK).fire();
        } else {
            DisablePopupEvent.builder(this)
                    .action(DialogAction.OK).fire();
        }
    }

    // -- DocInitialisationHandler --

    /**
     * {@inheritDoc}
     *
     * <p>Shows a modal dialog with Facts Store and Events Store pickers.
     * The OK button is disabled until both are validly selected.</p>
     *
     * <p>Preconditions:</p>
     * <ul>
     *   <li>{@code docRef} must be non-null and refer to an existing
     *       FloorMapDoc on the server.</li>
     *   <li>{@code onComplete} must be non-null.</li>
     *   <li>{@code taskMonitorFactory} must be non-null.</li>
     * </ul>
     *
     * <p>Postconditions:</p>
     * <ul>
     *   <li>If OK is clicked: the FloorMapDoc has been patched with
     *       the selected store refs and saved;
     *       {@code onComplete.accept(true)} is called.</li>
     *   <li>If Cancel is clicked: the FloorMapDoc has been deleted
     *       from the explorer; {@code onComplete.accept(false)} is
     *       called.</li>
     * </ul>
     */
    @Override
    public void showInitialisationDialog(final DocRef docRef,
                                         final Consumer<Boolean> onComplete,
                                         final TaskMonitorFactory taskMonitorFactory) {
        Objects.requireNonNull(docRef, "docRef must not be null");
        Objects.requireNonNull(onComplete, "onComplete must not be null");
        Objects.requireNonNull(taskMonitorFactory, "taskMonitorFactory must not be null");

        this.docRef = docRef;
        this.completionCallback = onComplete;
        this.eventsStoreValid = false;

        factsStorePresenter.setSelectedEntityReference(null, false);
        eventsStorePresenter.setSelectedEntityReference(null, false);

        final PopupSize popupSize =
                PopupSize.resizable(DIALOG_WIDTH, DIALOG_HEIGHT);
        //noinspection unused e
        ShowPopupEvent.builder(this)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .popupSize(popupSize)
                .caption("Initialise New Floor Map")
                .onShow(e -> {
                    getView().focus();
                    validate();
                })
                .onHideRequest(e -> {
                    if (e.isOk()) {
                        applyInitialisation(e, taskMonitorFactory);
                    } else {
                        deleteAndAbort(e, taskMonitorFactory);
                    }
                })
                .fire();
    }

    /**
     * Loads the new FloorMapDoc, patches it with the selected store
     * references, saves it, and signals completion.
     *
     * <p>Postcondition: on success, the document has been updated
     * with both store references and {@code completionCallback}
     * receives {@code true}.</p>
     *
     * @param e   the hide-popup event to control dialog dismissal;
     *            never null
     * @param tmf task monitor factory for REST calls; never null
     */
    private void applyInitialisation(final HidePopupRequestEvent e,
                                     final TaskMonitorFactory tmf) {
        final DocRef factsDocRef = factsStorePresenter.getSelectedEntityReference();
        final DocRef eventsDocRef = eventsStorePresenter.getSelectedEntityReference();

        // Fetch the doc, patch it, and save
        //noinspection unused error
        restFactory
                .create(FLOOR_MAP_RESOURCE)
                .method(res -> res.fetch(docRef.getUuid()))
                .onSuccess(doc -> {
                    final FloorMapDoc updated = doc.copy()
                            .factsStoreRef(factsDocRef)
                            .eventsStoreRef(eventsDocRef)
                            .factsQuery("from param('FactStore')\n"
                                    + "select \n"
                                    + "  Key, \n"
                                    + "  EffectiveTime, \n"
                                    + "  jq(Value, \".type\") as type, \n"
                                    + "  jq(Value, \".name\") as name, \n"
                                    + "  jq(Value, \".maps\") as maps, \n"
                                    + "  jq(Value, \".coords\") as coords, \n"
                                    + "  jq(Value, \".img\") as img, \n"
                                    + "  jq(Value, \"\\\"tm-world-to-map\\\"\") as tm_world_to_map, \n"
                                    + "  jq(Value, \"\\\"tm-map-to-screen\\\"\") as tm_map_to_screen")
                            .eventsQuery("from param('EventStore')\n"
                                    + "select EffectiveTime as \"Effective Time\",\n"
                                    + "  Key as \"Entity ID\",\n"
                                    + "  jq(Value, '.location') as \"Location ID\",\n"
                                    + "  jq(Value, '.type') as \"Event Type\",\n"
                                    + "  jq(Value, '.status') as \"Status\",\n"
                                    + "  jq(Value, '.message') as \"Message\"")
                            .build();

                    //noinspection unused savedDoc, error
                    restFactory
                            .create(FLOOR_MAP_RESOURCE)
                            .method(res2 -> res2.update(updated.getUuid(), updated))
                            .onSuccess(savedDoc -> {
                                e.hide();
                                completionCallback.accept(true);
                            })
                            .onFailure(error -> {
                                e.reset();
                            })
                            .taskMonitorFactory(tmf)
                            .exec();
                })
                .onFailure(error -> {
                    e.reset();
                })
                .taskMonitorFactory(tmf)
                .exec();
    }

    /**
     * Deletes the freshly-created document and signals cancellation.
     *
     * <p>The explorer tree is refreshed after deletion so the
     * now-deleted node disappears from the UI.</p>
     *
     * <p>Postcondition: the document has been deleted (or a
     * best-effort attempt was made) and {@code completionCallback}
     * receives {@code false}.</p>
     *
     * @param e   the hide-popup event to control dialog dismissal;
     *            never null
     * @param tmf task monitor factory for REST calls; never null
     */
    private void deleteAndAbort(final HidePopupRequestEvent e,
                                final TaskMonitorFactory tmf) {
        e.hide();
        //noinspection unused result, error
        restFactory
                .create(EXPLORER_RESOURCE)
                .method(res -> res.delete(
                        new ExplorerServiceDeleteRequest(List.of(docRef))))
                .onSuccess(result -> {
                    RefreshExplorerTreeEvent.fire(
                            FloorMapInitPresenter.this);
                    completionCallback.accept(false);
                })
                .onFailure(error -> {
                    // Best effort — still abort
                    completionCallback.accept(false);
                })
                .taskMonitorFactory(tmf)
                .exec();
    }

    /**
     * View interface for the FloorMap initialisation dialog.
     *
     * <p>Implementations must provide labelled slots for the
     * Facts Store and Events Store selection widgets.</p>
     */
    public interface FloorMapInitView extends View, Focus {

        /**
         * Sets the view for the Facts Store selector.
         *
         * @param view the DocSelectionBox view for selecting a
         *             {@link SqlTemporalStoreDoc}; never null
         */
        void setFactsStoreView(View view);

        /**
         * Sets the view for the Events Store selector.
         *
         * @param view the DocSelectionBox view for selecting a
         *             {@link PlanBDoc}; never null
         */
        void setEventsStoreView(View view);
    }
}
