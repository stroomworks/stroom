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
import stroom.floormap.shared.FloorMapEventsQuery;
import stroom.floormap.shared.FloorMapFieldMapping;
import stroom.floormap.shared.FloorMapResource;
import stroom.floormap.shared.ValueFormat;
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
 * <strong>Facts Store</strong> — a {@link SqlTemporalStoreDoc}, because the Editor
 * tab writes spatial data back to it — and an <strong>Events Store</strong> — a
 * {@link PlanBDoc}, which is only ever read. The OK button remains disabled until
 * both are selected.</p>
 *
 * <p>The floor map references each store by <em>name</em> only — the name is
 * substituted into the {@code param('FactStore')} / {@code param('EventStore')}
 * placeholders of the stored queries — so neither store is coupled to a
 * particular store implementation beyond what this picker allows.</p>
 *
 * <p>The default events query this dialog writes selects {@code EffectiveTime},
 * {@code Key} and {@code Value}, which of the Plan B state types only
 * {@link StateType#TEMPORAL_STATE} exposes. The picker can filter by document type
 * but not by state type, so that is checked explicitly on OK rather than left to
 * fail later as an opaque unknown-field error at query time.</p>
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
    private static final PlanBDocResource PLAN_B_DOC_RESOURCE =
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

        // Facts Store = SqlTemporalStore (the Editor tab writes to it)
        factsStorePresenter = docSelectionBoxPresenterProvider.get();
        factsStorePresenter.setCaption("Choose Facts Store");
        factsStorePresenter.setIncludedTypes(SqlTemporalStoreDoc.TYPE);
        factsStorePresenter.setRequiredPermissions(DocumentPermission.USE);
        view.setFactsStoreView(factsStorePresenter.getView());

        // Events Store = PlanB (read-only; state type checked on OK)
        eventsStorePresenter = docSelectionBoxPresenterProvider.get();
        eventsStorePresenter.setCaption("Choose Events Store");
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
        registerHandler(eventsStorePresenter.addDataSelectionHandler(e -> validate()));
    }

    // -- Validation --

    /**
     * Updates the OK button enabled state based on current validity.
     *
     * <p>The OK button is enabled only when both the Facts Store and the
     * Events Store have a non-null selection. The picker itself constrains
     * each selection to a valid store type, so no further check is needed.</p>
     */
    private void validate() {
        final boolean factsOk =
                factsStorePresenter.getSelectedEntityReference() != null;
        final boolean eventsOk =
                eventsStorePresenter.getSelectedEntityReference() != null;
        final boolean valid = factsOk && eventsOk;
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
     * Checks the selected events store is usable, then hands off to
     * {@link #saveInitialisation}.
     *
     * <p>The check is a fetch of the Plan B document to read its
     * {@link StateType}: only {@link StateType#TEMPORAL_STATE} carries the effective
     * time the default events query selects. A wrong choice warns and leaves the
     * dialog open (via {@link HidePopupRequestEvent#reset()}) so it can be corrected,
     * rather than saving a document whose events query cannot run.</p>
     *
     * <p>Postcondition: either the dialog has been reset for another attempt, or
     * {@link #saveInitialisation} has taken over.</p>
     *
     * @param e   the hide-popup event to control dialog dismissal;
     *            never null
     * @param tmf task monitor factory for REST calls; never null
     */
    private void applyInitialisation(final HidePopupRequestEvent e,
                                     final TaskMonitorFactory tmf) {
        final DocRef factsDocRef = factsStorePresenter.getSelectedEntityReference();
        final DocRef eventsDocRef = eventsStorePresenter.getSelectedEntityReference();

        // The events store's state type decides whether the default query below can
        // work at all, so settle that before writing anything.
        //noinspection unused error
        restFactory
                .create(PLAN_B_DOC_RESOURCE)
                .method(res -> res.fetch(eventsDocRef.getUuid()))
                .onSuccess(planBDoc -> {
                    if (StateType.TEMPORAL_STATE != planBDoc.getStateType()) {
                        AlertEvent.fireWarn(FloorMapInitPresenter.this,
                                "The events store '" + eventsDocRef.getName() + "' is a "
                                + describe(planBDoc.getStateType()) + " store. A floor map's "
                                + "events store must be a Temporal State store, as that is the "
                                + "only kind that records an effective time per entry.",
                                e::reset);
                    } else {
                        saveInitialisation(e, tmf, factsDocRef, eventsDocRef);
                    }
                })
                .onFailure(error -> e.reset())
                .taskMonitorFactory(tmf)
                .exec();
    }

    /**
     * Renders a {@link StateType} for an error message, tolerating a {@code null}.
     *
     * <p>A Plan B document with no state type set is possible — the field is nullable —
     * and is just as unusable as one of the wrong type, so it needs wording too rather
     * than an NPE or a bare "null".</p>
     *
     * @param stateType the state type; may be {@code null}
     * @return a human-readable description; never null
     */
    private static String describe(final StateType stateType) {
        return stateType == null
                ? "store with no state type set"
                : stateType.getDisplayValue();
    }

    /**
     * Patches the new document with the chosen store references and the default
     * queries, then saves it.
     *
     * <p>Only called once the events store has been confirmed to be a
     * {@link StateType#TEMPORAL_STATE} Plan B store, because the events query written
     * here selects {@code EffectiveTime}.</p>
     *
     * <p>Postcondition: on success, the document has been updated and
     * {@code completionCallback} receives {@code true}.</p>
     *
     * @param e             the hide-popup event to control dialog dismissal; never null
     * @param tmf           task monitor factory for REST calls; never null
     * @param factsDocRef   the chosen facts store; never null
     * @param eventsDocRef  the chosen events store; never null
     */
    private void saveInitialisation(final HidePopupRequestEvent e,
                                    final TaskMonitorFactory tmf,
                                    final DocRef factsDocRef,
                                    final DocRef eventsDocRef) {
        // Fetch the doc, patch it, and save
        //noinspection unused error
        restFactory
                .create(FLOOR_MAP_RESOURCE)
                .method(res -> res.fetch(docRef.getUuid()))
                .onSuccess(doc -> {
                    final FloorMapDoc updated = doc.copy()
                            .factsStoreRef(factsDocRef)
                            .eventsStoreRef(eventsDocRef)
                            .eventsQuery(FloorMapEventsQuery.defaultQuery())
                            // The query above aliases these two columns; without them the
                            // parse matches nothing and no entity ever reaches the canvas.
                            .entityIdColumn(FloorMapEventsQuery.ENTITY_ID_COLUMN)
                            .locationIdColumn(FloorMapEventsQuery.LOCATION_ID_COLUMN)
                            .valueFormat(ValueFormat.JSON)
                            .valueSchema(FloorMapFieldMapping.initialValueSchema())
                            .build();

                    //noinspection unused savedDoc, error
                    restFactory
                            .create(FLOOR_MAP_RESOURCE)
                            .method(res2 -> res2.update(updated.getUuid(), updated))
                            .onSuccess(savedDoc -> {
                                e.hide();
                                completionCallback.accept(true);
                            })
                            .onFailure(error -> e.reset())
                            .taskMonitorFactory(tmf)
                            .exec();
                })
                .onFailure(error -> e.reset())
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
