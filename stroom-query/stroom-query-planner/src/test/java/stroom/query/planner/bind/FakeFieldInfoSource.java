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

package stroom.query.planner.bind;

import stroom.query.api.datasource.QueryField;
import stroom.query.planner.port.FieldInfoSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An in-memory {@link FieldInfoSource} for {@link TestBinder} - the port exists precisely so tests don't need a
 * real {@code DataSourceProviderRegistry}/{@code DocRef} setup (see {@code FieldInfoSourceAdapter} in
 * {@code stroom-query-common} for the real one, and its own test for proof the adapter wraps it correctly).
 */
final class FakeFieldInfoSource implements FieldInfoSource {

    private final Map<String, List<QueryField>> fieldsByDataSource;

    FakeFieldInfoSource(final Map<String, List<QueryField>> fieldsByDataSource) {
        this.fieldsByDataSource = fieldsByDataSource;
    }

    @Override
    public List<QueryField> getFields(final String dataSourceName) {
        return fieldsByDataSource.getOrDefault(dataSourceName, List.of());
    }

    @Override
    public Optional<QueryField> getTimeField(final String dataSourceName) {
        return getFields(dataSourceName).stream()
                .filter(f -> "EventTime".equals(f.getFldName()))
                .findFirst();
    }
}
