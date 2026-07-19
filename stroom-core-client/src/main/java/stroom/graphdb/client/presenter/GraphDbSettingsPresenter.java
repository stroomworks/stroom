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

package stroom.graphdb.client.presenter;

import stroom.docref.DocRef;
import stroom.entity.client.presenter.DocPresenter;
import stroom.entity.client.presenter.ReadOnlyChangeHandler;
import stroom.graphdb.shared.GraphDbDoc;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.View;

/**
 * Task P6.2: {@code GraphDbDoc}'s Settings tab - deliberately scoped down to just the {@code description} field
 * (implementation plan Task P6.2 Files section, "D8" framing). {@code temporalPrecision}/{@code retention}/
 * {@code nodeTypeMappings} are not editable here yet - each is a separate, real UI-design effort (in particular
 * {@code nodeTypeMappings} has no existing widget to mirror), and every one of these fields is already documented
 * as nullable/optional on {@link GraphDbDoc} itself (null means "use the internal default"), so a graph remains
 * fully functional with only a description ever set via this tab.
 */
public class GraphDbSettingsPresenter
        extends DocPresenter<GraphDbSettingsPresenter.GraphDbSettingsView, GraphDbDoc> {

    @Inject
    public GraphDbSettingsPresenter(final EventBus eventBus, final GraphDbSettingsView view) {
        super(eventBus, view);
    }

    @Override
    protected void onRead(final DocRef docRef, final GraphDbDoc doc, final boolean readOnly) {
        getView().onReadOnly(readOnly);
        getView().setDescription(doc.getDescription());
    }

    @Override
    protected GraphDbDoc onWrite(final GraphDbDoc doc) {
        return doc.copy().description(getView().getDescription()).build();
    }

    public interface GraphDbSettingsView extends View, ReadOnlyChangeHandler {

        String getDescription();

        void setDescription(String description);
    }
}
