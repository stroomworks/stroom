/*
 * Copyright 2022 Crown Copyright
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
 *
 */

package stroom.analytics.client.presenter;

import stroom.analytics.shared.ReportDoc;
import stroom.query.client.presenter.QueryEditPresenter;

import com.google.web.bindery.event.shared.EventBus;

import javax.inject.Inject;

public class ReportQueryEditPresenter
        extends AbstractQueryEditPresenter<ReportDoc> {

    @Inject
    public ReportQueryEditPresenter(final EventBus eventBus,
                                    final QueryEditPresenter queryEditPresenter) {
        super(eventBus, queryEditPresenter);
    }

    @Override
    protected ReportDoc onWrite(final ReportDoc entity) {
        return entity
                .copy()
                .timeRange(queryEditPresenter.getTimeRange())
                .query(queryEditPresenter.getQuery())
                .build();
    }
}