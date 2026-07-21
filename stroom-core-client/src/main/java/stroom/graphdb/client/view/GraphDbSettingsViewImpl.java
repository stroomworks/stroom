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

package stroom.graphdb.client.view;

import stroom.document.client.event.ChangeUiHandlers;
import stroom.graphdb.client.presenter.GraphDbSettingsPresenter.GraphDbSettingsView;
import stroom.graphdb.shared.GraphNodeTypeMapping;
import stroom.item.client.SelectionBox;
import stroom.planb.client.view.RetentionSettingsWidget;
import stroom.planb.client.view.SettingsGroup;
import stroom.planb.shared.RetentionSettings;
import stroom.planb.shared.TemporalPrecision;

import com.google.gwt.event.logical.shared.ValueChangeEvent;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewWithUiHandlers;

import java.util.List;

/**
 * Task B1 (docs/graphdb-features-implementation-plan.md, Workstream B): the view for
 * {@link stroom.graphdb.client.presenter.GraphDbSettingsPresenter}, mirroring
 * {@code stroom.planb.client.presenter.TemporalStateSettingsPresenter}'s composition of reusable
 * {@code stroom.planb.client.view} widgets. {@code temporalPrecision} is a single field specific to
 * {@code GraphDbDoc} so it is declared directly here (as {@code stroom.planb.client.view.RetentionSettingsWidget}
 * does for its own single fields) rather than in a separate one-field widget class.
 */
public class GraphDbSettingsViewImpl
        extends ViewWithUiHandlers<ChangeUiHandlers>
        implements GraphDbSettingsView {

    private static final TemporalPrecision DEFAULT_TEMPORAL_PRECISION = TemporalPrecision.MILLISECOND;

    private final Widget widget;
    private final RetentionSettingsWidget retentionSettingsWidget;
    private final GraphNodeTypeMappingsWidget graphNodeTypeMappingsWidget;

    @UiField
    SelectionBox<TemporalPrecision> temporalPrecision;
    @UiField
    SettingsGroup retentionPanel;
    @UiField
    SettingsGroup nodeTypeMappingsPanel;

    @Inject
    public GraphDbSettingsViewImpl(final Binder binder,
                                   final RetentionSettingsWidget retentionSettingsWidget,
                                   final GraphNodeTypeMappingsWidget graphNodeTypeMappingsWidget) {
        widget = binder.createAndBindUi(this);
        this.retentionSettingsWidget = retentionSettingsWidget;
        this.graphNodeTypeMappingsWidget = graphNodeTypeMappingsWidget;

        temporalPrecision.addItems(TemporalPrecision.ORDERED_LIST);
        temporalPrecision.setValue(DEFAULT_TEMPORAL_PRECISION);

        retentionPanel.add(retentionSettingsWidget.asWidget());
        nodeTypeMappingsPanel.add(graphNodeTypeMappingsWidget.asWidget());
    }

    @Override
    public void setUiHandlers(final ChangeUiHandlers uiHandlers) {
        super.setUiHandlers(uiHandlers);
        retentionSettingsWidget.setUiHandlers(uiHandlers);
        graphNodeTypeMappingsWidget.setUiHandlers(uiHandlers);
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public TemporalPrecision getTemporalPrecision() {
        return temporalPrecision.getValue();
    }

    @Override
    public void setTemporalPrecision(final TemporalPrecision temporalPrecision) {
        this.temporalPrecision.setValue(temporalPrecision == null
                ? DEFAULT_TEMPORAL_PRECISION
                : temporalPrecision);
    }

    @Override
    public RetentionSettings getRetention() {
        return retentionSettingsWidget.getRetention();
    }

    @Override
    public void setRetention(final RetentionSettings retention) {
        retentionSettingsWidget.setRetention(retention);
    }

    @Override
    public List<GraphNodeTypeMapping> getNodeTypeMappings() {
        return graphNodeTypeMappingsWidget.getNodeTypeMappings();
    }

    @Override
    public void setNodeTypeMappings(final List<GraphNodeTypeMapping> nodeTypeMappings) {
        graphNodeTypeMappingsWidget.setNodeTypeMappings(nodeTypeMappings);
    }

    @Override
    public void onReadOnly(final boolean readOnly) {
        temporalPrecision.setEnabled(!readOnly);
        retentionSettingsWidget.onReadOnly(readOnly);
        graphNodeTypeMappingsWidget.onReadOnly(readOnly);
    }

    @UiHandler("temporalPrecision")
    @SuppressWarnings("unused")
    public void onTemporalPrecision(final ValueChangeEvent<TemporalPrecision> event) {
        getUiHandlers().onChange();
    }

    public interface Binder extends UiBinder<Widget, GraphDbSettingsViewImpl> {

    }
}
