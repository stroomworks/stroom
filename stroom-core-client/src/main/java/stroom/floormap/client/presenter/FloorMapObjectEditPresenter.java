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
import stroom.alert.client.event.ConfirmEvent;
import stroom.document.asset.client.presenter.DocumentAssetDropDownPresenter;
import stroom.floormap.client.ValueAccessorFactory;
import stroom.floormap.client.presenter.FloorMapObjectEditPresenter.FloorMapObjectEditView;
import stroom.floormap.shared.FloorMapDoc;
import stroom.floormap.shared.FloorMapEntryParser;
import stroom.floormap.shared.FloorMapFieldMapping.Role;
import stroom.floormap.shared.FloorMapJsonKeys;
import stroom.floormap.shared.ParsedValue;
import stroom.floormap.shared.ValueAccessor;
import stroom.util.shared.TemporalEntry;
import stroom.widget.popup.client.event.ShowPopupEvent;
import stroom.widget.popup.client.presenter.PopupType;

import com.google.gwt.user.client.ui.Widget;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.inject.Inject;

/**
 * Presenter for the properties form used to edit individual floor-map object
 * entries (temporal versions of a fact).
 *
 * <p>This presenter is used in two contexts:</p>
 * <ul>
 *   <li><strong>Editor tab</strong> ({@link FloorMapEditorPresenter}) — displayed as a
 *       modal OK/Cancel dialog via {@link #show(String, TemporalEntry, Consumer)}.
 *       Selecting a row in the Time List calls {@link #loadEntry(TemporalEntry)}
 *       to populate the inline form.</li>
 *   <li><strong>Map tab</strong> ({@link FloorMapMapPresenter}) — embedded as an inline
 *       panel. </li>
 * </ul>
 *
 * <h3>Managed form fields</h3>
 * <ul>
 *   <li>Type, Name — free-text identification of the object.</li>
 *   <li>Image — selected from the document-asset dropdown.</li>
 *   <li>X / Y coordinates — position on the map canvas.</li>
 *   <li>Effective time — the timestamp of this temporal version.</li>
 *   <li>World-to-Map matrix — 6-element affine transform.</li>
 *   <li>Map-to-Screen matrix — 6-element affine transform (background objects only).</li>
 * </ul>
 *
 * <h3>Preconditions</h3>
 * <p>{@link #setMapName(String)} <strong>must</strong> be called before
 * {@link #buildEntry(long)} or {@link #show(String, TemporalEntry, Consumer)}
 * is invoked; otherwise an {@link IllegalStateException} is thrown.</p>
 */
public class FloorMapObjectEditPresenter extends MyPresenterWidget<FloorMapObjectEditView> {

    private final DocumentAssetDropDownPresenter documentAssetDropDownPresenter;
    private String objectId;
    private String mapName;
    private FloorMapDoc floorMapDoc;

    /**
     * Resolves the JSON path for the given {@link Role} using the current
     * document's value schema.
     *
     * <p>Requires that {@link #setFloorMapDoc(FloorMapDoc)} has been called
     * with a non-null document before any invocation.</p>
     *
     * @param role the field role to resolve
     * @return the dot-separated JSON path for the role, or {@code null}
     *         if the role is not present in the schema
     * @throws NullPointerException if no document has been set
     */
    private String pathForRole(final Role role) {
        return FloorMapEntryParser.findPath(floorMapDoc.getValueSchema(), role);
    }

    /**
     * Stores the map name for later use by {@link #buildEntry(long)}.
     *
     * <p>This method <strong>must</strong> be called at least once before
     * any call to {@link #buildEntry(long)} or
     * {@link #show(String, TemporalEntry, Consumer)}.</p>
     *
     * @param mapName the temporal-store map name; must not be {@code null}
     * @throws IllegalArgumentException if {@code mapName} is {@code null}
     */
    public void setMapName(final String mapName) {
        if (mapName == null) {
            throw new IllegalArgumentException("mapName must not be null");
        }
        this.mapName = mapName;
    }

    private String requireMapName() {
        if (mapName == null) {
            throw new IllegalStateException(
                    "mapName has not been set — call setMapName() before using this presenter");
        }
        return mapName;
    }

    /**
     * Sets the floor-map document and configures the asset dropdown to list
     * assets belonging to that document.
     *
     * <p>If {@code floorMapDoc} is {@code null}, subsequent calls to
     * {@link #pathForRole(Role)} will fall back to the default value schema,
     * and the asset dropdown will show no assets.</p>
     *
     * @param floorMapDoc the floor-map document, or {@code null} to clear
     */
    public void setFloorMapDoc(final FloorMapDoc floorMapDoc) {
        this.floorMapDoc = floorMapDoc;
        documentAssetDropDownPresenter.setDocument(floorMapDoc);
    }

    @Inject
    public FloorMapObjectEditPresenter(final EventBus eventBus,
                                       final FloorMapObjectEditView view,
                                       final DocumentAssetDropDownPresenter documentAssetDropDownPresenter) {
        super(eventBus, view);
        this.documentAssetDropDownPresenter = documentAssetDropDownPresenter;
    }

    @Override
    protected void onBind() {
        super.onBind();
        getView().setChooseImgView(documentAssetDropDownPresenter.getView().asWidget());
    }

    /**
     * Shows the properties form as a modal OK/Cancel dialog for a
     * <em>new</em> (not yet persisted) entry.
     *
     * <p>The form is pre-populated from {@code entry}. When the user clicks OK
     * the current form state is built into a {@link TemporalEntry} and passed
     * to {@code onSave}. Clicking Cancel discards the changes. Editing the
     * effective time simply sets the new entry's time — no move/clone
     * question is asked, since there is no persisted version to move.</p>
     *
     * @param caption the dialog title — e.g. "Add Time Properties" or "Add Object"
     * @param entry   the entry to pre-populate the form with; may be {@code null} for a blank form
     * @param onSave  called with the built entry when the user clicks OK
     *
     * TODO 4092: Is this correct? When is it called?
     */
    public void show(final String caption,
                     final TemporalEntry entry,
                     final Consumer<TemporalEntry> onSave) {
        doShow(caption, entry, false, (built, clone) -> onSave.accept(built));
    }

    /**
     * Shows the properties form as a modal OK/Cancel dialog for an
     * <em>existing</em> entry.
     *
     * <p>Behaves like {@link #show(String, TemporalEntry, Consumer)}, except
     * that when the user changes the effective time they are asked whether to
     * <em>move</em> the version to the new time or <em>clone</em> it (keeping
     * the original version at the old time). The choice is reported via the
     * second argument of {@code onSave}.</p>
     *
     * @param caption the dialog title — e.g. "Edit Time Properties"
     * @param entry   the entry to pre-populate the form with; must not be {@code null}
     * @param onSave  called with the built entry when the user clicks OK; the
     *                boolean is {@code true} when the user chose to clone
     *                rather than move (only possible when the time changed)
     */
    public void showForEdit(final String caption,
                            final TemporalEntry entry,
                            final BiConsumer<TemporalEntry, Boolean> onSave) {
        doShow(caption, entry, true, onSave);
    }

    // TODO 4092: Is this correct? When is it called?
    private void doShow(final String caption,
                        final TemporalEntry entry,
                        final boolean askMoveOrClone,
                        final BiConsumer<TemporalEntry, Boolean> onSave) {
        loadEntry(entry);
        //noinspection unused e
        ShowPopupEvent.builder(this)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .caption(caption)
                .onShow(e -> getView().setEnabled(true))
                .onHideRequest(e -> {
                    if (e.isOk()) {
                        final String type = getView().getType();
                        if (type == null || type.trim().isEmpty()) {
                            AlertEvent.fireError(this,
                                    "Object type must not be empty. "
                                    + "Please enter a type (e.g. 'gate', 'camera', 'person').",
                                    null);
                            return;
                        }
                        final long time = getView().getEffectiveTime();
                        if (askMoveOrClone && entry != null && entry.getEffectiveTimeMs() != time) {
                            // Effective time changed — ask whether to move or clone.
                            ConfirmEvent.fire(this,
                                    "You have changed the effective time. "
                                    + "Do you want to move the version to the new time? "
                                    + "(OK to move, Cancel to create a new cloned version at the new time)",
                                    move -> {
                                        onSave.accept(buildEntry(time), !move);
                                        e.hide();
                                    });
                        } else {
                            onSave.accept(buildEntry(time), false);
                            e.hide();
                        }
                    } else {
                        e.hide();
                    }
                })
                .fire();
    }

    /**
     * Stores the object ID. Called by both the Editor tab and the Map tab.
     * Does not trigger a server fetch — form population is driven by
     * {@link #loadEntry(TemporalEntry)} on the Editor tab.
     *
     * @param objectId the fact key for the object being edited
     */
    public void setObject(final String objectId) {
        this.objectId = objectId;
    }

    /**
     * Loads a temporal entry into the form.
     *
     * <p>Called by {@link stroom.floormap.client.presenter.FloorMapEditorPresenter}
     * whenever the Time List selection changes. Populates all form fields and
     * enables or disables the form based on whether {@code entry} is non-null.</p>
     *
     * @param entry the entry to display, or {@code null} to clear and disable the form
     */
    public void loadEntry(final TemporalEntry entry) {
        getView().setEnabled(entry != null);
        documentAssetDropDownPresenter.setEnabled(entry != null);
        resetInputs(entry);
    }

    /**
     * Builds a serialised value string from the current view state using
     * the format-independent {@link ValueAccessor} abstraction.
     *
     * @return the serialised value (JSON or XML); never {@code null}
     */
    private String buildValue() {
        final ValueAccessor accessor = ValueAccessorFactory.forFormat(floorMapDoc.getValueFormat());
        final ParsedValue newValue = accessor.createEmpty("entry");

        accessor.setString(newValue, pathForRole(Role.TYPE), getView().getType());
        accessor.setString(newValue, pathForRole(Role.LABEL), getView().getName());
        accessor.setString(newValue, pathForRole(Role.IMAGE),
                documentAssetDropDownPresenter.getSelectedAssetPath() == null
                        ? ""
                        : documentAssetDropDownPresenter.getSelectedAssetPath());

        accessor.setArray(newValue, pathForRole(Role.POSITION),
                new double[]{getView().getX(), getView().getY()});

        // Every fact — background included — is placed by its WORLD_TO_MAP matrix.
        accessor.setArray(newValue, pathForRole(Role.WORLD_TO_MAP),
                getView().getWorldToMapMatrix());

        return accessor.serialize(newValue);
    }

    /**
     * Builds a {@link TemporalEntry} from the current view state without
     * making a REST call.
     *
     * @param effectiveTimeMs the effective time for the new entry
     * @return the constructed entry; never {@code null}
     */
    private TemporalEntry buildEntry(final long effectiveTimeMs) {
        return new TemporalEntry(requireMapName(), objectId, effectiveTimeMs, buildValue());
    }

    /**
     * Populates the form fields from the given temporal entry, or resets all
     * fields to sensible defaults if {@code selected} is {@code null}.
     *
     * <p>When an entry is provided its JSON value is parsed to extract type,
     * name, image path, coordinates, and the affine transformation matrices.
     * Any parse errors are silently ignored and the affected fields retain
     * their zero/identity defaults.</p>
     *
     * <p>When {@code selected} is {@code null} and the current
     * {@link #objectId} is the background sentinel, the name and type fields
     * are pre-filled with the background defaults; otherwise they are left
     * blank.</p>
     *
     * @param selected the temporal entry to populate from, or {@code null}
     *                 to reset
     */
    private void resetInputs(final TemporalEntry selected) {
        if (selected != null) {
            getView().setEffectiveTime(selected.getEffectiveTimeMs());
            double x = 0.0;
            double y = 0.0;
            String name = "";
            String type = "";
            String img = "";
            final double[] w2m = new double[]{1.0, 0.0, 0.0, 1.0, 0.0, 0.0};

            try {
                final ValueAccessor accessor = ValueAccessorFactory.forFormat(floorMapDoc.getValueFormat());
                final ParsedValue parsed = accessor.parse(selected.getValue());
                if (parsed != null) {
                    final String parsedName = accessor.getString(parsed, pathForRole(Role.LABEL));
                    if (parsedName != null) {
                        name = parsedName;
                    }
                    final String parsedType = accessor.getString(parsed, pathForRole(Role.TYPE));
                    if (parsedType != null) {
                        type = parsedType;
                    }
                    final String parsedImg = accessor.getString(parsed, pathForRole(Role.IMAGE));
                    if (parsedImg != null) {
                        img = parsedImg;
                    }

                    final double[] coords = accessor.getArray(parsed, pathForRole(Role.POSITION));
                    if (coords != null && coords.length >= 2) {
                        x = coords[0];
                        y = coords[1];
                    }

                    final double[] parsedW2m = accessor.getArray(parsed, pathForRole(Role.WORLD_TO_MAP));
                    if (parsedW2m != null && parsedW2m.length >= 6) {
                        System.arraycopy(parsedW2m, 0, w2m, 0, 6);
                    }
                }
            } catch (final Exception ex) {
                // Ignore
            }
            getView().setX(x);
            getView().setY(y);
            getView().setName(name);
            getView().setType(type);
            documentAssetDropDownPresenter.setSelectedAssetPath(img);
            getView().setWorldToMapMatrix(w2m);
        } else {
            getView().setEffectiveTime(0L);
            getView().setX(0.0);
            getView().setY(0.0);
            if (FloorMapJsonKeys.BACKGROUND.equals(objectId)) {
                getView().setName(FloorMapJsonKeys.BACKGROUND_DISPLAY_NAME);
                getView().setType(FloorMapJsonKeys.BACKGROUND);
            } else {
                getView().setName("");
                getView().setType("");
            }
            documentAssetDropDownPresenter.setSelectedAssetPath("");
            getView().setWorldToMapMatrix(new double[]{1.0, 0.0, 0.0, 1.0, 0.0, 0.0});
        }
    }

    // --------------------------------------------------------------------------------

    /**
     * View contract for the object-edit properties form.
     *
     * <p>Implementations provide the UI widgets for all editable fields:
     * effective time, coordinates, name, type, image chooser, and the two
     * affine-transform matrices.</p>
     */
    public interface FloorMapObjectEditView extends View {

        /** Returns the effective-time value entered by the user, in epoch milliseconds. */
        long getEffectiveTime();

        /** Sets the effective-time field to the given epoch-millisecond value. */
        void setEffectiveTime(long timeMS);

        /** Returns the current X-coordinate value from the form. */
        double getX();

        /** Sets the X-coordinate display field. */
        void setX(double x);

        /** Returns the current Y-coordinate value from the form. */
        double getY();

        /** Sets the Y-coordinate display field. */
        void setY(double y);

        /** Returns the object display name entered by the user. */
        String getName();

        /** Sets the object display name field. */
        void setName(String name);

        /** Returns the object type entered by the user. */
        String getType();

        /** Sets the object type field. */
        void setType(String type);

        /**
         * Installs the image-chooser (asset dropdown) widget into the form.
         *
         * @param widget the dropdown widget; must not be {@code null}
         */
        void setChooseImgView(Widget widget);

        /** Returns the 6-element world-to-map affine transformation matrix. */
        double[] getWorldToMapMatrix();

        /** Sets the 6-element world-to-map affine transformation matrix. */
        void setWorldToMapMatrix(double[] matrix);

        /**
         * Enables or disables all form fields.
         *
         * @param enabled {@code true} to enable editing, {@code false} to
         *                disable (grey-out) all inputs
         */
        void setEnabled(final boolean enabled);
    }
}
