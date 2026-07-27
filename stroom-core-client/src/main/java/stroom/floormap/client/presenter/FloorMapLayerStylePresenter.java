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
import stroom.docstore.shared.AbstractDoc;
import stroom.document.asset.client.presenter.DocumentAssetDropDownPresenter;
import stroom.document.asset.client.presenter.DocumentAssetQuickUploadPresenter;
import stroom.floormap.client.presenter.FloorMapLayerStylePresenter.FloorMapLayerStyleView;
import stroom.floormap.shared.TypeStyle;
import stroom.floormap.shared.TypeStyle.Shape;
import stroom.widget.popup.client.event.ShowPopupEvent;
import stroom.widget.popup.client.presenter.PopupType;

import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.function.Consumer;

/**
 * Modal dialog for editing a single layer's appearance — the graphic drawn for
 * facts of that type, held on its {@link TypeStyle}.
 *
 * <p>A layer draws either a <strong>shape</strong> ({@link TypeStyle#getShape()}
 * filled with {@link TypeStyle#getColour()}) or an <strong>image</strong>
 * ({@link TypeStyle#getGraphic()}) picked from the document's asset store — the
 * {@code Graphic} radio pair chooses which. The colour stays editable in image
 * mode because it is still used for areas of the type and for the glyph label.</p>
 *
 * <p>Opened from the Editor Layers panel's per-row swatch. On OK it calls back
 * with a replacement {@code TypeStyle}, which the panel persists via the
 * type-styles bridge.</p>
 */
public class FloorMapLayerStylePresenter extends MyPresenterWidget<FloorMapLayerStyleView> {

    private final DocumentAssetDropDownPresenter assetDropDownPresenter;
    private final Provider<DocumentAssetQuickUploadPresenter> quickUploadProvider;

    /** The document owning the asset store the graphic is picked from. */
    private AbstractDoc document;

    /**
     * Bumped every time the dialog is shown, so an upload that completes after the
     * user has moved on to a different layer is discarded rather than dropping its
     * image into whichever layer is now being edited.
     */
    private int showCount;

    @Inject
    public FloorMapLayerStylePresenter(final EventBus eventBus,
                                       final FloorMapLayerStyleView view,
                                       final DocumentAssetDropDownPresenter assetDropDownPresenter,
                                       final Provider<DocumentAssetQuickUploadPresenter> quickUploadProvider) {
        super(eventBus, view);
        this.assetDropDownPresenter = assetDropDownPresenter;
        this.quickUploadProvider = quickUploadProvider;

        view.setAssetPickerView(assetDropDownPresenter.getView().asWidget());
        view.setUploadHandler(this::uploadGraphic);
        // Mode / shape / colour changes all alter what the preview should show.
        view.setChangeHandler(this::refreshPreview);
        assetDropDownPresenter.addDataSelectionHandler(event -> refreshPreview());
    }

    /**
     * Sets the document whose asset store supplies layer graphics. Must be called
     * before the dialog is shown, otherwise the picker has nothing to browse.
     */
    public void setDocument(final AbstractDoc document) {
        this.document = document;
        assetDropDownPresenter.setDocument(document);
    }

    /**
     * Uploads a new image into the document's asset store and selects it, so the
     * user does not have to leave the dialog for the Assets tab.
     */
    private void uploadGraphic() {
        if (document == null) {
            return;
        }
        // The upload finishes asynchronously, so remember which showing of the
        // dialog asked for it and ignore the result if that has moved on.
        final int requestedFor = showCount;
        quickUploadProvider.get().upload(document, this, url -> {
            if (requestedFor != showCount) {
                return;
            }
            assetDropDownPresenter.setSelectedAssetPath(url);
            getView().setImageMode(true);
            refreshPreview();
        });
    }

    /**
     * Pushes the current dialog state into the preview and keeps the asset picker
     * browsable only in image mode.
     */
    private void refreshPreview() {
        final boolean imageMode = getView().isImageMode();
        assetDropDownPresenter.setEnabled(imageMode);
        getView().setPreview(imageMode
                ? new TypeStyle(null, null, getView().getColour(), selectedGraphic())
                : new TypeStyle(null, chosenShape(), getView().getColour()));
    }

    /** The picked asset URL, or {@code null} when nothing is selected. */
    private String selectedGraphic() {
        final String path = assetDropDownPresenter.getSelectedAssetPath();
        return path == null || path.isEmpty()
                ? null
                : path;
    }

    /** The chosen shape, or {@code null} for the default glyph. */
    private Shape chosenShape() {
        final String name = getView().getShape();
        return FloorMapLayerStyleView.DEFAULT_SHAPE_LABEL.equals(name)
                ? null
                : Shape.valueOf(name);
    }

    /**
     * Shows the dialog for the given layer.
     *
     * @param style the layer's current style; its {@code type} names the layer in
     *              the caption and is carried through to the replacement
     * @param onOk  called with the replacement style when the user confirms
     */
    public void show(final TypeStyle style,
                     final Consumer<TypeStyle> onOk) {
        showCount++;
        final String type = style.getType();
        getView().setShape(style.getShape() == null
                ? FloorMapLayerStyleView.DEFAULT_SHAPE_LABEL
                : style.getShape().name());
        getView().setColour(style.getColour());
        assetDropDownPresenter.setSelectedAssetPath(style.getGraphic());
        getView().setImageMode(style.hasGraphic());
        refreshPreview();

        ShowPopupEvent.builder(this)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .caption(type != null
                        ? "Appearance — " + type
                        : "Appearance")
                .onHideRequest(e -> {
                    if (e.isOk()) {
                        final boolean imageMode = getView().isImageMode();
                        if (imageMode && selectedGraphic() == null) {
                            // Confirming image mode with nothing picked would drop
                            // the layer's shape and put no image in its place,
                            // silently reducing it to the fallback glyph.
                            AlertEvent.fireWarn(FloorMapLayerStylePresenter.this,
                                    "Choose an image, or switch back to Shape.",
                                    e::reset);
                            return;
                        }
                        // Only one of shape / graphic is kept, so switching mode
                        // and confirming genuinely replaces the graphic rather
                        // than leaving a stale value behind to win later.
                        onOk.accept(new TypeStyle(
                                type,
                                imageMode ? null : chosenShape(),
                                getView().getColour(),
                                imageMode ? selectedGraphic() : null));
                    }
                    e.hide();
                })
                .fire();
    }

    /**
     * View contract: a shape-or-image mode toggle, a shape chooser, the asset
     * picker for the image, a colour chooser and a preview.
     */
    public interface FloorMapLayerStyleView extends View {

        /** Dropdown label for "no configured shape" (the default rectangle glyph). */
        String DEFAULT_SHAPE_LABEL = "(default)";

        void setShape(String shape);

        String getShape();

        void setColour(String colour);

        String getColour();

        /** {@code true} to draw an image for this layer, {@code false} for a shape. */
        void setImageMode(boolean imageMode);

        boolean isImageMode();

        /** Installs the asset picker widget the presenter owns. */
        void setAssetPickerView(Widget assetPickerView);

        /** Registers the handler run when the user clicks the upload button. */
        void setUploadHandler(Runnable handler);

        /**
         * Registers the handler run whenever the mode, shape or colour changes, so
         * the presenter can refresh the preview.
         */
        void setChangeHandler(Runnable handler);

        /** Renders a preview of the given style. */
        void setPreview(TypeStyle style);
    }
}
