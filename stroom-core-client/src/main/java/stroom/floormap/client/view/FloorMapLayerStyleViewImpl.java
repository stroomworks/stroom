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

package stroom.floormap.client.view;

import stroom.floormap.client.presenter.FloorMapLayerStylePresenter.FloorMapLayerStyleView;
import stroom.floormap.shared.TypeStyle.Shape;
import stroom.item.client.SelectionBox;
import stroom.widget.colour.client.ColourBox;

import com.google.gwt.user.client.ui.Grid;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewImpl;

/**
 * View for the layer appearance dialog — a shape dropdown and a colour picker.
 */
public class FloorMapLayerStyleViewImpl extends ViewImpl implements FloorMapLayerStyleView {

    /** Default hex handed to the colour input when a layer has no stored colour. */
    private static final String DEFAULT_COLOUR = "#000000";

    private final Grid grid;
    private final SelectionBox<String> shapeBox = new SelectionBox<>();
    private final ColourBox colourBox = new ColourBox();

    @Inject
    public FloorMapLayerStyleViewImpl() {
        shapeBox.addItem(FloorMapLayerStyleView.DEFAULT_SHAPE_LABEL);
        for (final Shape shape : Shape.values()) {
            shapeBox.addItem(shape.name());
        }

        grid = new Grid(2, 2);
        grid.addStyleName("floormap-layer-style-dialog");
        grid.setText(0, 0, "Shape");
        grid.setWidget(0, 1, shapeBox);
        grid.setText(1, 0, "Colour");
        grid.setWidget(1, 1, colourBox);
    }

    @Override
    public Widget asWidget() {
        return grid;
    }

    @Override
    public void setShape(final String shape) {
        shapeBox.setValue(shape);
    }

    @Override
    public String getShape() {
        return shapeBox.getValue();
    }

    @Override
    public void setColour(final String colour) {
        colourBox.setValue(colour == null
                ? DEFAULT_COLOUR
                : colour);
    }

    @Override
    public String getColour() {
        return colourBox.getValue();
    }
}
