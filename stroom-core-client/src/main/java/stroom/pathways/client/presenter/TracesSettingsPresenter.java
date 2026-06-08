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

package stroom.pathways.client.presenter;

import stroom.docref.DocRef;
import stroom.entity.client.presenter.DocPresenter;
import stroom.entity.client.presenter.ReadOnlyChangeHandler;
import stroom.pathways.client.presenter.TracesSettingsPresenter.TracesSettingsView;
import stroom.pathways.shared.TracesDoc;
import stroom.planb.client.presenter.TraceSettingsPresenter;
import stroom.planb.shared.StateType;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.View;

public class TracesSettingsPresenter
        extends DocPresenter<TracesSettingsView, TracesDoc> {

    private final TraceSettingsPresenter traceSettingsPresenter;

    @Inject
    public TracesSettingsPresenter(final EventBus eventBus,
                                   final TracesSettingsView view,
                                   final TraceSettingsPresenter traceSettingsPresenter) {
        super(eventBus, view);
        this.traceSettingsPresenter = traceSettingsPresenter;
        view.setSettingsView(traceSettingsPresenter.getView());
    }

    @Override
    protected void onBind() {
        super.onBind();
        registerHandler(traceSettingsPresenter.addChangeHandler(() -> onChange()));
    }

    @Override
    protected void onRead(final DocRef docRef, final TracesDoc doc, final boolean readOnly) {
        traceSettingsPresenter.read(doc.getSettings(), readOnly);
    }

    @Override
    protected TracesDoc onWrite(final TracesDoc doc) {
        return doc.copyTraces()
                .stateType(StateType.TRACE)
                .settings(traceSettingsPresenter.write())
                .build();
    }

    public interface TracesSettingsView extends View, ReadOnlyChangeHandler {

        void setSettingsView(View view);
    }
}
