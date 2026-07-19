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

import stroom.graphdb.client.presenter.GraphDbDataPresenter.GraphDbDataView;
import stroom.query.client.view.QueryDataViewImpl;

import com.google.inject.Inject;

public class GraphDbDataViewImpl extends QueryDataViewImpl implements GraphDbDataView {

    @Inject
    public GraphDbDataViewImpl(final Binder binder) {
        super(binder);
    }
}
