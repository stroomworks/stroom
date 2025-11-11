/*
 * Copyright 2016 Crown Copyright
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
package stroom.visualisation.client.view;

import stroom.entity.client.presenter.ReadOnlyChangeHandler;
import stroom.visualisation.client.presenter.VisualisationAssetsPresenter.VisualisationAssetsView;

import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.user.client.ui.Widget;
import com.google.inject.Inject;
import com.gwtplatform.mvp.client.ViewImpl;

public class VisualisationAssetsViewImpl extends ViewImpl
        implements VisualisationAssetsView, ReadOnlyChangeHandler {

    private final Widget widget;

    @Inject
    public VisualisationAssetsViewImpl(final Binder binder) {
        widget = binder.createAndBindUi(this);
    }

    @Override
    public Widget asWidget() {
        return widget;
    }

    @Override
    public void onReadOnly(final boolean readOnly) {

    }

    // --------------------------------------------------------------------------------
    public interface Binder extends UiBinder<Widget, VisualisationAssetsViewImpl> {

    }
}
