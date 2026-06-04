/*
 * Copyright 2016-2025 Crown Copyright
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

package stroom.sqlstore.client.presenter;

import stroom.alert.client.event.ConfirmEvent;
import stroom.dispatch.client.RestFactory;
import stroom.docref.DocRef;
import stroom.entity.client.presenter.DocPresenter;
import stroom.entity.client.presenter.ReadOnlyChangeHandler;
import stroom.sqlstore.shared.SqlStoreDoc;
import stroom.sqlstore.shared.SqlStoreResource;

import com.google.gwt.core.client.GWT;
import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;
import com.gwtplatform.mvp.client.HasUiHandlers;
import com.gwtplatform.mvp.client.View;

public class SqlStoreSettingsPresenter
        extends DocPresenter<SqlStoreSettingsPresenter.SqlStoreSettingsView, SqlStoreDoc>
        implements SqlStoreSettingsUiHandlers {

    private static final SqlStoreResource SQL_STORE_RESOURCE = GWT.create(SqlStoreResource.class);

    private final RestFactory restFactory;

    @Inject
    public SqlStoreSettingsPresenter(
            final EventBus eventBus,
            final SqlStoreSettingsView view,
            final RestFactory restFactory) {
        super(eventBus, view);
        this.restFactory = restFactory;
        view.setUiHandlers(this);
    }

    @Override
    protected void onRead(final DocRef docRef, final SqlStoreDoc doc, final boolean readOnly) {
        getView().onReadOnly(readOnly);
        updateCount(docRef);
    }

    @Override
    protected SqlStoreDoc onWrite(final SqlStoreDoc doc) {
        return doc;
    }

    @Override
    public void onReset() {
        if (getEntity() != null) {
            ConfirmEvent.fire(this,
                    "This will remove all entries from the store. Are you sure you want to do this?",
                    ok -> {
                        if (ok) {
                            restFactory
                                    .create(SQL_STORE_RESOURCE)
                                    .method(res -> res.clear(getEntity().asDocRef()))
                                    .onSuccess(r -> updateCount(getEntity().asDocRef()))
                                    .exec();
                        }
                    });
        }
    }

    private void updateCount(final DocRef docRef) {
        if (docRef != null) {
            restFactory
                    .create(SQL_STORE_RESOURCE)
                    .method(res -> res.count(docRef))
                    .onSuccess(count -> getView().setCount(count))
                    .exec();
        }
    }

    public interface SqlStoreSettingsView
            extends View, ReadOnlyChangeHandler, HasUiHandlers<SqlStoreSettingsUiHandlers> {

        void setCount(long count);
    }
}
