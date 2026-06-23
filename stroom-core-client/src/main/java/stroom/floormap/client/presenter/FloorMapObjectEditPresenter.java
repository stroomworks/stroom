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

import stroom.alert.client.event.ConfirmEvent;
import stroom.data.client.event.DataSelectionEvent;
import stroom.dispatch.client.RestFactory;
import stroom.document.asset.client.presenter.DocumentAssetDropDownPresenter;
import stroom.floormap.client.presenter.FloorMapObjectEditPresenter.FloorMapObjectEditView;
import stroom.floormap.shared.FloorMapDoc;
import stroom.sqlstore.shared.SqlTemporalStoreResource;
import stroom.util.client.JSONUtil;
import stroom.util.shared.TemporalEntry;
import stroom.util.shared.TemporalEntryId;

import com.google.gwt.core.client.GWT;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.json.client.JSONArray;
import com.google.gwt.json.client.JSONNumber;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONString;
import com.google.gwt.user.client.ui.Widget;
import com.google.web.bindery.event.shared.EventBus;
import com.google.web.bindery.event.shared.HandlerRegistration;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.function.Consumer;
import javax.inject.Inject;

public class FloorMapObjectEditPresenter extends MyPresenterWidget<FloorMapObjectEditView> {

    private static final SqlTemporalStoreResource SQL_TEMPORAL_STORE_RESOURCE =
            GWT.create(SqlTemporalStoreResource.class);

    public static final String JSON_KEY_COORDS = "coords";
    public static final String JSON_KEY_TYPE = "type";
    public static final String JSON_KEY_NAME = "name";
    public static final String JSON_KEY_IMG = "img";
    public static final String JSON_KEY_TM_WORLD_TO_MAP = "tm-world-to-map";
    public static final String JSON_KEY_TM_MAP_TO_SCREEN = "tm-map-to-screen";

    private Runnable changeEventConsumer;
    private Consumer<TemporalEntry> saveConsumer;
    private Consumer<TemporalEntryId> deleteConsumer;

    public void setChangeEventConsumer(final Runnable changeEventConsumer) {
        this.changeEventConsumer = changeEventConsumer;
    }

    /**
     * Sets a consumer called instead of REST-based save when the Save button
     * is clicked. When non-null the entry is built locally and passed to this
     * consumer (e.g. to record in the pending-changes buffer). When null the
     * default REST update behaviour is used.
     *
     * @param saveConsumer the consumer, or {@code null} to use REST
     */
    public void setSaveConsumer(final Consumer<TemporalEntry> saveConsumer) {
        this.saveConsumer = saveConsumer;
    }

    /**
     * Sets a consumer called instead of REST-based delete when the Delete
     * button is clicked. When non-null the entry id is passed to this consumer
     * (e.g. to record deletion in the pending-changes buffer). When null the
     * default REST delete behaviour is used.
     *
     * @param deleteConsumer the consumer, or {@code null} to use REST
     */
    public void setDeleteConsumer(final Consumer<TemporalEntryId> deleteConsumer) {
        this.deleteConsumer = deleteConsumer;
    }

    private final RestFactory restFactory;
    private final DocumentAssetDropDownPresenter documentAssetDropDownPresenter;
    private String objectId;
    private String mapName;

    /**
     * The entry currently loaded into the form; driven by
     * {@link #loadEntry(TemporalEntry)} which is called by
     * {@link stroom.floormap.client.presenter.FloorMapEditorPresenter}
     * whenever the Time List selection changes.
     */
    private TemporalEntry currentEntry;

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

    public void setFloorMapDoc(final FloorMapDoc floorMapDoc) {
        documentAssetDropDownPresenter.setDocument(floorMapDoc);
    }

    public void updateCoords(final double x, final double y) {
        getView().setX(x);
        getView().setY(y);
    }

    @Inject
    public FloorMapObjectEditPresenter(final EventBus eventBus,
                                       final FloorMapObjectEditView view,
                                       final RestFactory restFactory,
                                       final DocumentAssetDropDownPresenter documentAssetDropDownPresenter) {
        super(eventBus, view);
        this.restFactory = restFactory;
        this.documentAssetDropDownPresenter = documentAssetDropDownPresenter;
    }

    @Override
    protected void onBind() {
        super.onBind();

        getView().setChooseImgView(documentAssetDropDownPresenter.getView().asWidget());

        // Action Form: Save/Update temporal record
        //noinspection unused e
        registerHandler(getView().addSaveHandler(e -> {
            final long time = getView().getEffectiveTime();
            final double x = getView().getX();
            final double y = getView().getY();

            if (saveConsumer != null) {
                // Editor tab: route through pending-changes buffer instead of REST.
                final TemporalEntry entry = buildEntry(time);
                if (currentEntry != null && currentEntry.getEffectiveTimeMs() != time) {
                    // Effective time changed: stage a deletion of the old key first.
                    if (deleteConsumer != null) {
                        deleteConsumer.accept(new TemporalEntryId(
                                requireMapName(), objectId, currentEntry.getEffectiveTimeMs()));
                    }
                }
                saveConsumer.accept(entry);
                if (changeEventConsumer != null) {
                    changeEventConsumer.run();
                }
            } else if (currentEntry != null && currentEntry.getEffectiveTimeMs() != time) {
                ConfirmEvent.fire(this,
                        "You have changed the effective time. "
                        + "Do you want to move the version to the new time? "
                        + "(Click OK to move, or Cancel to create a new cloned version instead)",
                        move -> {
                            if (move) {
                                deleteEntry(currentEntry.getEffectiveTimeMs(),
                                        () -> updateEntry(time, x, y, () -> {
                                            if (changeEventConsumer != null) {
                                                changeEventConsumer.run();
                                            }
                                        }));
                            } else {
                                updateEntry(time, x, y, () -> {
                                    if (changeEventConsumer != null) {
                                        changeEventConsumer.run();
                                    }
                                });
                            }
                        });
            } else {
                updateEntry(time, x, y, () -> {
                    if (changeEventConsumer != null) {
                        changeEventConsumer.run();
                    }
                });
            }
        }));

        // Action Form: Revert/Cancel changes
        //noinspection unused e
        registerHandler(getView().addCancelHandler(e -> resetInputs(currentEntry)));
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
        this.currentEntry = entry;
        getView().setEnabled(entry != null);
        documentAssetDropDownPresenter.setEnabled(entry != null);
        resetInputs(entry);
    }

    /**
     * Updates an existing temporal entry (or inserts if not exists).
     */
    public void updateEntry(final long effectiveTimeMs,
                            final double x,
                            final double y,
                            final Runnable onSuccess) {
        if (objectId == null) {
            return;
        }

        final JSONObject json = new JSONObject();
        json.put(JSON_KEY_TYPE, new JSONString(getView().getType()));
        json.put(JSON_KEY_NAME, new JSONString(getView().getName()));
        json.put(JSON_KEY_IMG, new JSONString(documentAssetDropDownPresenter.getSelectedAssetPath() == null
                ? ""
                : documentAssetDropDownPresenter.getSelectedAssetPath()));

        final JSONArray coordsArr = new JSONArray();
        coordsArr.set(0, new JSONNumber(getView().getX()));
        coordsArr.set(1, new JSONNumber(getView().getY()));
        json.put(JSON_KEY_COORDS, coordsArr);

        final double[] w2m = getView().getWorldToMapMatrix();
        final JSONArray w2mArr = new JSONArray();
        for (int i = 0; i < 6; i++) {
            w2mArr.set(i, new JSONNumber(w2m[i]));
        }
        json.put(JSON_KEY_TM_WORLD_TO_MAP, w2mArr);

        if ("background".equalsIgnoreCase(getView().getType())) {
            final double[] m2s = getView().getMapToScreenMatrix();
            final JSONArray m2sArr = new JSONArray();
            for (int i = 0; i < 6; i++) {
                m2sArr.set(i, new JSONNumber(m2s[i]));
            }
            json.put(JSON_KEY_TM_MAP_TO_SCREEN, m2sArr);
        }

        final TemporalEntry entry = new TemporalEntry(
                requireMapName(),
                objectId,
                effectiveTimeMs,
                json.toString()
        );

        //noinspection unused result
        restFactory.create(SQL_TEMPORAL_STORE_RESOURCE)
                .method(res -> res.update(entry))
                .onSuccess(result -> onSuccess.run())
                .exec();
    }

    /**
     * Builds a {@link TemporalEntry} from the current view state without
     * making a REST call. Used by the staged-save path when
     * {@link #saveConsumer} is non-null.
     *
     * @param effectiveTimeMs the effective time for the new entry
     * @return the constructed entry; never {@code null}
     */
    private TemporalEntry buildEntry(final long effectiveTimeMs) {
        final JSONObject json = new JSONObject();
        json.put(JSON_KEY_TYPE, new JSONString(getView().getType()));
        json.put(JSON_KEY_NAME, new JSONString(getView().getName()));
        json.put(JSON_KEY_IMG, new JSONString(
                documentAssetDropDownPresenter.getSelectedAssetPath() == null
                        ? ""
                        : documentAssetDropDownPresenter.getSelectedAssetPath()));

        final JSONArray coordsArr = new JSONArray();
        coordsArr.set(0, new JSONNumber(getView().getX()));
        coordsArr.set(1, new JSONNumber(getView().getY()));
        json.put(JSON_KEY_COORDS, coordsArr);

        final double[] w2m = getView().getWorldToMapMatrix();
        final JSONArray w2mArr = new JSONArray();
        for (int i = 0; i < 6; i++) {
            w2mArr.set(i, new JSONNumber(w2m[i]));
        }
        json.put(JSON_KEY_TM_WORLD_TO_MAP, w2mArr);

        if ("background".equalsIgnoreCase(getView().getType())) {
            final double[] m2s = getView().getMapToScreenMatrix();
            final JSONArray m2sArr = new JSONArray();
            for (int i = 0; i < 6; i++) {
                m2sArr.set(i, new JSONNumber(m2s[i]));
            }
            json.put(JSON_KEY_TM_MAP_TO_SCREEN, m2sArr);
        }

        return new TemporalEntry(requireMapName(), objectId, effectiveTimeMs, json.toString());
    }

    /**
     * Deletes a specific temporal entry by its effective time key.
     */
    public void deleteEntry(final long effectiveTimeMs, final Runnable onSuccess) {
        if (objectId == null) {
            return;
        }

        final TemporalEntryId id = new TemporalEntryId(
                requireMapName(),
                objectId,
                effectiveTimeMs
        );

        restFactory.create(SQL_TEMPORAL_STORE_RESOURCE)
                .method(res -> res.delete(id))
                .onSuccess(result -> {
                    if (result) {
                        onSuccess.run();
                    }
                })
                .exec();
    }

    private void resetInputs(final TemporalEntry selected) {
        if (selected != null) {
            getView().setEffectiveTime(selected.getEffectiveTimeMs());
            double x = 0.0;
            double y = 0.0;
            String name = "";
            String type = "";
            String img = "";
            final double[] w2m = new double[]{1.0, 0.0, 0.0, 1.0, 0.0, 0.0};
            final double[] m2s = new double[]{1.0, 0.0, 0.0, 1.0, 0.0, 0.0};

            try {
                if (selected.getValue() != null && selected.getValue().trim().startsWith("{")) {
                    final JSONObject json = JSONUtil.getObject(JSONUtil.parse(selected.getValue()));
                    if (json != null) {
                        name = JSONUtil.getString(json.get(JSON_KEY_NAME));
                        type = JSONUtil.getString(json.get(JSON_KEY_TYPE));
                        img = JSONUtil.getString(json.get(JSON_KEY_IMG));

                        final JSONArray coordsArr = JSONUtil.getArray(json.get(JSON_KEY_COORDS));
                        if (coordsArr != null && coordsArr.size() >= 2) {
                            x = JSONUtil.getDouble(coordsArr.get(0));
                            y = JSONUtil.getDouble(coordsArr.get(1));
                        }

                        final JSONArray w2mArr = JSONUtil.getArray(json.get(JSON_KEY_TM_WORLD_TO_MAP));
                        if (w2mArr != null && w2mArr.size() >= 6) {
                            for (int i = 0; i < 6; i++) {
                                w2m[i] = JSONUtil.getDouble(w2mArr.get(i));
                            }
                        }

                        final JSONArray m2sArr = JSONUtil.getArray(json.get(JSON_KEY_TM_MAP_TO_SCREEN));
                        if (m2sArr != null && m2sArr.size() >= 6) {
                            for (int i = 0; i < 6; i++) {
                                m2s[i] = JSONUtil.getDouble(m2sArr.get(i));
                            }
                        }
                    }
                } else if (selected.getValue() != null) {
                    final String[] coords = selected.getValue().split(",");
                    x = Double.parseDouble(coords[1].trim());
                    y = Double.parseDouble(coords[2].trim());
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
            getView().setMapToScreenMatrix(m2s);
        } else {
            getView().setEffectiveTime(0L);
            getView().setX(0.0);
            getView().setY(0.0);
            if ("background".equals(objectId)) {
                getView().setName("Background");
                getView().setType("background");
            } else {
                getView().setName("");
                getView().setType("");
            }
            documentAssetDropDownPresenter.setSelectedAssetPath("");
            getView().setWorldToMapMatrix(new double[]{1.0, 0.0, 0.0, 1.0, 0.0, 0.0});
            getView().setMapToScreenMatrix(new double[]{1.0, 0.0, 0.0, 1.0, 0.0, 0.0});
        }
    }

    public void addAssetSelectionHandler(final DataSelectionEvent.DataSelectionHandler<String> handler) {
        registerHandler(documentAssetDropDownPresenter.addDataSelectionHandler(handler));
    }

    // --------------------------------------------------------------------------------

    public interface FloorMapObjectEditView extends View {
        long getEffectiveTime();

        void setEffectiveTime(long timeMS);

        double getX();

        void setX(double x);

        double getY();

        void setY(double y);

        String getName();

        void setName(String name);

        String getType();

        void setType(String type);

        void setChooseImgView(Widget widget);

        double[] getWorldToMapMatrix();

        void setWorldToMapMatrix(double[] matrix);

        double[] getMapToScreenMatrix();

        void setMapToScreenMatrix(double[] matrix);

        HandlerRegistration addSaveHandler(ClickHandler handler);

        HandlerRegistration addCancelHandler(ClickHandler handler);

        void setEnabled(final boolean enabled);
    }
}
