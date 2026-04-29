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

import stroom.floormap.client.presenter.FloorMapMapPresenter.FloorMapMapView;
import stroom.floormap.shared.FloorMapDoc;
import stroom.docref.DocRef;
import stroom.entity.client.presenter.DocPresenter;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.View;

public class FloorMapMapPresenter
        extends DocPresenter<FloorMapMapView, FloorMapDoc> {

    @Inject
    public FloorMapMapPresenter(final EventBus eventBus,
                                final FloorMapMapView view) {
        super(eventBus, view);
    }

    @Override
    protected void onRead(final DocRef docRef, final FloorMapDoc document, final boolean readOnly) {

    }

    @Override
    protected FloorMapDoc onWrite(final FloorMapDoc document) {
        return document;
    }

    public interface FloorMapMapView extends View {

    }
}
