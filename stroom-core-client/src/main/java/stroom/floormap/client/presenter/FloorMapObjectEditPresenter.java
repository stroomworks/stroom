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
import stroom.document.asset.client.presenter.DocumentAssetDropDownPresenter;
import stroom.floormap.client.presenter.FloorMapObjectEditPresenter.FloorMapObjectEditView;
import stroom.floormap.shared.FloorMapDoc;
import stroom.util.client.JSONUtil;
import stroom.util.shared.TemporalEntry;
import stroom.widget.popup.client.event.ShowPopupEvent;
import stroom.widget.popup.client.presenter.PopupType;

import com.google.gwt.json.client.JSONArray;
import com.google.gwt.json.client.JSONNumber;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONString;

import com.google.gwt.user.client.ui.Widget;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.function.Consumer;
import javax.inject.Inject;

import static stroom.floormap.client.FloorMapJsonKeys.*;

public class FloorMapObjectEditPresenter extends MyPresenterWidget<FloorMapObjectEditView> {

    private final DocumentAssetDropDownPresenter documentAssetDropDownPresenter;
    private String objectId;
    private String mapName;

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
     * Shows the properties form as a modal OK/Cancel dialog.
     *
     * <p>The form is pre-populated from {@code entry}. When the user clicks OK
     * the current form state is built into a {@link TemporalEntry} and passed
     * to {@code onSave}. Clicking Cancel discards the changes.</p>
     *
     * @param caption the dialog title — e.g. "Add Time Properties" or "Edit Time Properties"
     * @param entry   the entry to pre-populate the form with; may be {@code null} for a blank form
     * @param onSave  called with the built entry when the user clicks OK
     */
    public void show(final String caption,
                     final TemporalEntry entry,
                     final Consumer<TemporalEntry> onSave) {
        loadEntry(entry);
        //noinspection unused e
        ShowPopupEvent.builder(this)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .caption(caption)
                .onShow(e -> getView().setEnabled(true))
                .onHideRequest(e -> {
                    if (e.isOk()) {
                        final long time = getView().getEffectiveTime();
                        if (entry != null && entry.getEffectiveTimeMs() != time) {
                            // Effective time changed — ask whether to move or clone.
                            //noinspection unused move
                            ConfirmEvent.fire(this,
                                    "You have changed the effective time. "
                                    + "Do you want to move the version to the new time? "
                                    + "(OK to move, Cancel to create a new cloned version at the new time)",
                                    move -> {
                                        final TemporalEntry built = buildEntry(time);
                                        onSave.accept(built);
                                        e.hide();
                                    });
                        } else {
                            onSave.accept(buildEntry(time));
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
     * Builds a JSON object from the current view state.
     *
     * @return the constructed JSON; never {@code null}
     */
    private JSONObject buildJson() {
        final JSONObject json = new JSONObject();
        json.put(TYPE, new JSONString(getView().getType()));
        json.put(NAME, new JSONString(getView().getName()));
        json.put(IMG, new JSONString(
                documentAssetDropDownPresenter.getSelectedAssetPath() == null
                        ? ""
                        : documentAssetDropDownPresenter.getSelectedAssetPath()));

        final JSONArray coordsArr = new JSONArray();
        coordsArr.set(0, new JSONNumber(getView().getX()));
        coordsArr.set(1, new JSONNumber(getView().getY()));
        json.put(COORDS, coordsArr);

        final double[] w2m = getView().getWorldToMapMatrix();
        final JSONArray w2mArr = new JSONArray();
        for (int i = 0; i < 6; i++) {
            w2mArr.set(i, new JSONNumber(w2m[i]));
        }
        json.put(TM_WORLD_TO_MAP, w2mArr);

        if ("background".equalsIgnoreCase(getView().getType())) {
            final double[] m2s = getView().getMapToScreenMatrix();
            final JSONArray m2sArr = new JSONArray();
            for (int i = 0; i < 6; i++) {
                m2sArr.set(i, new JSONNumber(m2s[i]));
            }
            json.put(TM_MAP_TO_SCREEN, m2sArr);
        }
        return json;
    }

    /**
     * Builds a {@link TemporalEntry} from the current view state without
     * making a REST call.
     *
     * @param effectiveTimeMs the effective time for the new entry
     * @return the constructed entry; never {@code null}
     */
    private TemporalEntry buildEntry(final long effectiveTimeMs) {
        return new TemporalEntry(requireMapName(), objectId, effectiveTimeMs, buildJson().toString());
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
                        name = JSONUtil.getString(json.get(NAME));
                        type = JSONUtil.getString(json.get(TYPE));
                        img = JSONUtil.getString(json.get(IMG));

                        final JSONArray coordsArr = JSONUtil.getArray(json.get(COORDS));
                        if (coordsArr != null && coordsArr.size() >= 2) {
                            x = JSONUtil.getDouble(coordsArr.get(0));
                            y = JSONUtil.getDouble(coordsArr.get(1));
                        }

                        final JSONArray w2mArr = JSONUtil.getArray(json.get(TM_WORLD_TO_MAP));
                        if (w2mArr != null && w2mArr.size() >= 6) {
                            for (int i = 0; i < 6; i++) {
                                w2m[i] = JSONUtil.getDouble(w2mArr.get(i));
                            }
                        }

                        final JSONArray m2sArr = JSONUtil.getArray(json.get(TM_MAP_TO_SCREEN));
                        if (m2sArr != null && m2sArr.size() >= 6) {
                            for (int i = 0; i < 6; i++) {
                                m2s[i] = JSONUtil.getDouble(m2sArr.get(i));
                            }
                        }
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

        void setEnabled(final boolean enabled);
    }
}
