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

import stroom.floormap.client.presenter.FloorMapObjectEditPresenter.FloorMapObjectEditView;
import stroom.floormap.shared.FloorMapJsonKeys;
import stroom.widget.datepicker.client.DateTimeBox;
import stroom.widget.datepicker.client.DateTimePopup;
import stroom.widget.form.client.FormGroup;

import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.gwtplatform.mvp.client.ViewImpl;

/**
 * View implementation for the floor map object (fact) edit dialog.
 *
 * <p>Renders form fields for the object's name, type, effective time, position (x/y),
 * an image chooser, and two sets of affine-transform matrix fields (world→map and
 * map→screen). The matrix fields are conditionally visible based on the object type:
 * "background" objects show the map→screen matrix, all others show world→map.</p>
 */
public class FloorMapObjectEditViewImpl extends ViewImpl implements FloorMapObjectEditView {

    private final Widget widget;

    @UiField
    DateTimeBox effectiveTimeBox;
    @UiField
    TextBox xBox;
    @UiField
    TextBox yBox;
    @UiField
    TextBox nameBox;
    @UiField
    TextBox typeBox;
    @UiField
    SimplePanel chooseImgContainer;

    @UiField
    FormGroup w2mTranslationGroup;
    @UiField
    FormGroup w2mScaleRotGroup;
    @UiField
    FormGroup m2sTranslationGroup;
    @UiField
    FormGroup m2sScaleRotGroup;

    @UiField
    TextBox w2mTx;
    @UiField
    TextBox w2mTy;
    @UiField
    TextBox w2mSx;
    @UiField
    TextBox w2mSy;
    @UiField
    TextBox w2mRot;

    @UiField
    TextBox m2sTx;
    @UiField
    TextBox m2sTy;
    @UiField
    TextBox m2sSx;
    @UiField
    TextBox m2sSy;
    @UiField
    TextBox m2sRot;

    @Inject
    public FloorMapObjectEditViewImpl(final Binder binder,
                                      final Provider<DateTimePopup> dateTimePopupProvider) {
        widget = binder.createAndBindUi(this);
        effectiveTimeBox.setPopupProvider(dateTimePopupProvider);

        //noinspection unused e
        typeBox.addKeyUpHandler(e -> updateMatrixVisibility(typeBox.getText()));
        //noinspection unused e
        typeBox.addValueChangeHandler(e -> updateMatrixVisibility(typeBox.getText()));
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public long getEffectiveTime() {
        return effectiveTimeBox.getValue();
    }

    @Override
    public void setEffectiveTime(final long timeMs) {
        effectiveTimeBox.setValue(timeMs);
    }

    @Override
    public double getX() {
        try {
            return Double.parseDouble(xBox.getText());
        } catch (final NumberFormatException e) {
            return 0.0;
        }
    }

    @Override
    public void setX(final double x) {
        xBox.setText(String.valueOf(x));
    }

    @Override
    public double getY() {
        try {
            return Double.parseDouble(yBox.getText());
        } catch (final NumberFormatException e) {
            return 0.0;
        }
    }

    @Override
    public void setY(final double y) {
        yBox.setText(String.valueOf(y));
    }

    @Override
    public String getName() {
        return nameBox.getText();
    }

    @Override
    public void setName(final String name) {
        nameBox.setText(name == null ? "" : name);
    }

    @Override
    public String getType() {
        return typeBox.getText();
    }

    @Override
    public void setType(final String type) {
        typeBox.setText(type == null ? "" : type);
        updateMatrixVisibility(type);
    }

    @Override
    public void setChooseImgView(final Widget widget) {
        chooseImgContainer.setWidget(widget);
    }

    @Override
    public double[] getWorldToMapMatrix() {
        return parseMatrixFields(w2mTx, w2mTy, w2mSx, w2mSy, w2mRot);
    }

    @Override
    public void setWorldToMapMatrix(final double[] m) {
        populateMatrixFields(m, w2mTx, w2mTy, w2mSx, w2mSy, w2mRot);
    }

    @Override
    public double[] getMapToScreenMatrix() {
        return parseMatrixFields(m2sTx, m2sTy, m2sSx, m2sSy, m2sRot);
    }

    @Override
    public void setMapToScreenMatrix(final double[] m) {
        populateMatrixFields(m, m2sTx, m2sTy, m2sSx, m2sSy, m2sRot);
    }

    /**
     * Reads the five decomposed transform fields (translate-X/Y, scale-X/Y, rotation)
     * and recomposes them into a 6-element affine matrix {@code [a, b, c, d, tx, ty]}.
     */
    private double[] parseMatrixFields(final TextBox tx,
                                       final TextBox ty,
                                       final TextBox sx,
                                       final TextBox sy,
                                       final TextBox rot) {
        final double tX = parseDouble(tx.getText(), 0.0);
        final double tY = parseDouble(ty.getText(), 0.0);
        final double sX = parseDouble(sx.getText(), 1.0);
        final double sY = parseDouble(sy.getText(), 1.0);
        final double rRad = Math.toRadians(parseDouble(rot.getText(), 0.0));

        final double cos = Math.cos(rRad);
        final double sin = Math.sin(rRad);
        return new double[]{
                sX * cos, sX * sin,
                -sY * sin, sY * cos,
                tX, tY
        };
    }

    /**
     * Parses a double from the given string, returning {@code defaultVal} on failure.
     */
    private double parseDouble(final String val, final double defaultVal) {
        try {
            return Double.parseDouble(val.trim());
        } catch (final Exception ex) {
            return defaultVal;
        }
    }

    /**
     * Decomposes a 6-element affine matrix {@code [a, b, c, d, tx, ty]} into translation,
     * scale, and rotation fields. Falls back to identity values if the matrix is null or
     * too short.
     */
    private void populateMatrixFields(final double[] m,
                                      final TextBox tx,
                                      final TextBox ty,
                                      final TextBox sx,
                                      final TextBox sy,
                                      final TextBox rot) {
        if (m != null && m.length >= 6) {
            final double sX = Math.sqrt(m[0] * m[0] + m[1] * m[1]);
            // The determinant of the 2×2 rotation-scale sub-matrix (a*d - b*c)
            // encodes the sign of scaleY relative to scaleX.  Using Math.sqrt
            // alone always produces a positive value, which silently loses a
            // negative scaleY (used for Y-axis flipping) on every round-trip.
            final double det = m[0] * m[3] - m[1] * m[2];
            final double sY = (det >= 0 ? 1.0 : -1.0) * Math.sqrt(m[2] * m[2] + m[3] * m[3]);
            final double rotationDeg = Math.round(Math.toDegrees(Math.atan2(m[1], m[0])) * 100.0) / 100.0;

            tx.setText(String.valueOf(m[4]));
            ty.setText(String.valueOf(m[5]));
            sx.setText(String.valueOf(Math.round(sX * 100.0) / 100.0));
            sy.setText(String.valueOf(Math.round(sY * 100.0) / 100.0));
            rot.setText(String.valueOf(rotationDeg));
        } else {
            tx.setText("0.0");
            ty.setText("0.0");
            sx.setText("1.0");
            sy.setText("1.0");
            rot.setText("0.0");
        }
    }

    @Override
    public void setEnabled(final boolean enabled) {
        effectiveTimeBox.setEnabled(enabled);
        setTextBoxesEnabled(enabled,
                xBox, yBox, nameBox, typeBox,
                w2mTx, w2mTy, w2mSx, w2mSy, w2mRot,
                m2sTx, m2sTy, m2sSx, m2sSy, m2sRot);
    }

    private static void setTextBoxesEnabled(final boolean enabled, final TextBox... boxes) {
        for (final TextBox box : boxes) {
            box.setEnabled(enabled);
        }
    }

    /**
     * Toggles which matrix fields are visible based on the object type. "background"
     * objects show the map→screen matrix; all other types show world→map.
     */
    private void updateMatrixVisibility(final String type) {
        final boolean isBackground = FloorMapJsonKeys.BACKGROUND.equalsIgnoreCase(type == null ? "" : type.trim());
        setVisibleIfPresent(w2mTranslationGroup, !isBackground);
        setVisibleIfPresent(w2mScaleRotGroup, !isBackground);
        setVisibleIfPresent(m2sTranslationGroup, isBackground);
        setVisibleIfPresent(m2sScaleRotGroup, isBackground);
    }

    private static void setVisibleIfPresent(final Widget widget, final boolean visible) {
        if (widget != null) {
            widget.setVisible(visible);
        }
    }

    // --------------------------------------------------------------------------------

    public interface Binder extends UiBinder<Widget, FloorMapObjectEditViewImpl> {

    }
}
