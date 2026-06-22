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
import stroom.svg.shared.SvgImage;
import stroom.widget.button.client.Button;
import stroom.widget.datepicker.client.DateTimeBox;
import stroom.widget.datepicker.client.DateTimePopup;
import stroom.widget.form.client.FormGroup;

import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.event.shared.HandlerRegistration;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.gwtplatform.mvp.client.ViewImpl;

public class FloorMapObjectEditViewImpl extends ViewImpl implements FloorMapObjectEditView {

    private final Widget widget;

    @UiField
    SimplePanel toolbarContainer;
    @UiField
    SimplePanel gridContainer;
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

    @UiField
    Button saveBtn;
    @UiField
    Button cancelBtn;

    @Inject
    public FloorMapObjectEditViewImpl(final Binder binder,
                                      final Provider<DateTimePopup> dateTimePopupProvider) {
        widget = binder.createAndBindUi(this);
        effectiveTimeBox.setPopupProvider(dateTimePopupProvider);
        saveBtn.setIcon(SvgImage.OK);
        cancelBtn.setIcon(SvgImage.CANCEL);

        typeBox.addKeyUpHandler(e -> updateMatrixVisibility(typeBox.getText()));
        typeBox.addValueChangeHandler(e -> updateMatrixVisibility(typeBox.getText()));
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public void setToolbar(final Widget toolbarWidget) {
        toolbarContainer.setWidget(toolbarWidget);
    }

    @Override
    public void setGridView(final Widget gridWidget) {
        gridContainer.setWidget(gridWidget);
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

    private double[] parseMatrixFields(
            final TextBox tx, final TextBox ty,
            final TextBox sx, final TextBox sy,
            final TextBox rot) {
        final double tX = parseDouble(tx.getText(), 0.0);
        final double tY = parseDouble(ty.getText(), 0.0);
        final double sX = parseDouble(sx.getText(), 1.0);
        final double sY = parseDouble(sy.getText(), 1.0);
        final double rDeg = parseDouble(rot.getText(), 0.0);

        final double rRad = Math.toRadians(rDeg);
        final double[] m = new double[6];
        m[0] = sX * Math.cos(rRad);
        m[1] = sX * Math.sin(rRad);
        m[2] = -sY * Math.sin(rRad);
        m[3] = sY * Math.cos(rRad);
        m[4] = tX;
        m[5] = tY;
        return m;
    }

    private double parseDouble(final String val, final double defaultVal) {
        try {
            return Double.parseDouble(val.trim());
        } catch (final Exception ex) {
            return defaultVal;
        }
    }

    private void populateMatrixFields(
            final double[] m,
            final TextBox tx, final TextBox ty,
            final TextBox sx, final TextBox sy,
            final TextBox rot) {
        if (m != null && m.length >= 6) {
            final double a = m[0];
            final double b = m[1];
            final double c = m[2];
            final double d = m[3];
            final double e = m[4];
            final double f = m[5];

            final double tX = e;
            final double tY = f;
            final double sX = Math.sqrt(a * a + b * b);
            final double sY = Math.sqrt(c * c + d * d);
            double rotationDeg = Math.toDegrees(Math.atan2(b, a));
            rotationDeg = Math.round(rotationDeg * 100.0) / 100.0;

            tx.setText(String.valueOf(tX));
            ty.setText(String.valueOf(tY));
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
    public HandlerRegistration addSaveHandler(final ClickHandler handler) {
        return saveBtn.addClickHandler(handler);
    }

    @Override
    public HandlerRegistration addCancelHandler(final ClickHandler handler) {
        return cancelBtn.addClickHandler(handler);
    }

    @Override
    public void setEnabled(final boolean enabled) {
        effectiveTimeBox.setEnabled(enabled);
        xBox.setEnabled(enabled);
        yBox.setEnabled(enabled);
        nameBox.setEnabled(enabled);
        typeBox.setEnabled(enabled);
        w2mTx.setEnabled(enabled);
        w2mTy.setEnabled(enabled);
        w2mSx.setEnabled(enabled);
        w2mSy.setEnabled(enabled);
        w2mRot.setEnabled(enabled);
        m2sTx.setEnabled(enabled);
        m2sTy.setEnabled(enabled);
        m2sSx.setEnabled(enabled);
        m2sSy.setEnabled(enabled);
        m2sRot.setEnabled(enabled);
        saveBtn.setEnabled(enabled);
        cancelBtn.setEnabled(enabled);
    }

    private void updateMatrixVisibility(final String type) {
        final boolean isBackground = "background".equalsIgnoreCase(type == null ? "" : type.trim());
        if (w2mTranslationGroup != null) {
            w2mTranslationGroup.setVisible(!isBackground);
        }
        if (w2mScaleRotGroup != null) {
            w2mScaleRotGroup.setVisible(!isBackground);
        }
        if (m2sTranslationGroup != null) {
            m2sTranslationGroup.setVisible(isBackground);
        }
        if (m2sScaleRotGroup != null) {
            m2sScaleRotGroup.setVisible(isBackground);
        }
    }

    // --------------------------------------------------------------------------------

    public interface Binder extends UiBinder<Widget, FloorMapObjectEditViewImpl> {

    }
}
