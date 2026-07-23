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

import stroom.floormap.client.presenter.FloorMapLayerStylePresenter.FloorMapLayerStyleView;
import stroom.floormap.shared.TypeStyle.Shape;
import stroom.widget.popup.client.event.ShowPopupEvent;
import stroom.widget.popup.client.presenter.PopupType;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.MyPresenterWidget;
import com.gwtplatform.mvp.client.View;

import java.util.function.BiConsumer;

/**
 * Modal dialog for editing a single layer's appearance — the {@code shape} and
 * {@code colour} of its {@link stroom.floormap.shared.TypeStyle} (the same
 * settings the Settings tab's Type Styles grid exposes, scoped to one layer).
 *
 * <p>Opened from the Editor Layers panel's per-row colour swatch. On OK it calls
 * back with the chosen shape/colour; the panel builds the replacement
 * {@code TypeStyle} and persists it via the type-styles bridge.</p>
 */
public class FloorMapLayerStylePresenter extends MyPresenterWidget<FloorMapLayerStyleView> {

    @Inject
    public FloorMapLayerStylePresenter(final EventBus eventBus,
                                       final FloorMapLayerStyleView view) {
        super(eventBus, view);
    }

    /**
     * Shows the dialog for the given layer.
     *
     * @param type   the layer's type (for the caption)
     * @param shape  the current shape, or {@code null} for the default
     * @param colour the current hex colour, or {@code null} for none
     * @param onOk   called with the chosen shape (nullable) and hex colour when
     *               the user confirms
     */
    public void show(final String type,
                     final Shape shape,
                     final String colour,
                     final BiConsumer<Shape, String> onOk) {
        getView().setShape(shape == null
                ? FloorMapLayerStyleView.DEFAULT_SHAPE_LABEL
                : shape.name());
        getView().setColour(colour);

        ShowPopupEvent.builder(this)
                .popupType(PopupType.OK_CANCEL_DIALOG)
                .caption(type != null
                        ? "Appearance — " + type
                        : "Appearance")
                .onHideRequest(e -> {
                    if (e.isOk()) {
                        final String s = getView().getShape();
                        final Shape chosen = FloorMapLayerStyleView.DEFAULT_SHAPE_LABEL.equals(s)
                                ? null
                                : Shape.valueOf(s);
                        onOk.accept(chosen, getView().getColour());
                    }
                    e.hide();
                })
                .fire();
    }

    /**
     * View contract: a shape chooser and a colour chooser.
     */
    public interface FloorMapLayerStyleView extends View {

        /** Dropdown label for "no configured shape" (the default rectangle glyph). */
        String DEFAULT_SHAPE_LABEL = "(default)";

        void setShape(String shape);

        String getShape();

        void setColour(String colour);

        String getColour();
    }
}
