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

import stroom.data.client.presenter.MetaPresenter;
import stroom.docref.DocRef;
import stroom.entity.client.presenter.DocPresenter;
import stroom.floormap.client.presenter.FloorMapMapPresenter.FloorMapMapView;
import stroom.floormap.shared.FloorMapDoc;
import stroom.meta.shared.MetaExpressionUtil;
import stroom.widget.tab.client.presenter.LinkTabsPresenter;
import stroom.widget.tab.client.presenter.TabData;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.View;

import java.util.Objects;

public class FloorMapMapPresenter
        extends DocPresenter<FloorMapMapView, FloorMapDoc> {

    public static final Object MAP = new Object();
    public static final Object LOG_DATA = new Object();

    private final MetaPresenter metaPresenter;
    private final Provider<FloorMapCanvasPresenter> floorMapCanvasPresenterProvider;

    private DocRef currentFeed;

    @Inject
    public FloorMapMapPresenter(final EventBus eventBus,
                                final FloorMapMapView view,
                                final LinkTabsPresenter linkTabsPresenter,
                                final MetaPresenter metaPresenter,
                                final FloorMapTempPresenter floorMapTempPresenter,
                                final Provider<FloorMapCanvasPresenter> floorMapCanvasPresenterProvider) {
        super(eventBus, view);
        this.metaPresenter = metaPresenter;
        this.floorMapCanvasPresenterProvider = floorMapCanvasPresenterProvider;

        final TabData dataTab = linkTabsPresenter.addTab("Data", metaPresenter);
        linkTabsPresenter.addTab("Temp", floorMapTempPresenter);
        linkTabsPresenter.changeSelectedTab(dataTab);

        setInSlot(LOG_DATA, linkTabsPresenter);
        setInSlot(MAP, floorMapCanvasPresenterProvider.get());
    }

    @Override
    protected void onRead(final DocRef docRef, final FloorMapDoc document, final boolean readOnly) {
        if (!Objects.equals(currentFeed, document.getFeed())) {
            currentFeed = document.getFeed();
            if (currentFeed != null) {
                metaPresenter.getCriteria().setExpression(MetaExpressionUtil.createFeedExpression(currentFeed));
                metaPresenter.refresh();
            }
        }
    }

    @Override
    protected FloorMapDoc onWrite(final FloorMapDoc document) {
        return document;
    }

    public interface FloorMapMapView extends View {

    }
}
