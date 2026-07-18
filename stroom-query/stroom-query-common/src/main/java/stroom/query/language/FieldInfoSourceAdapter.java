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

package stroom.query.language;

import stroom.docref.DocRef;
import stroom.query.api.datasource.DataSourceProvider;
import stroom.query.api.datasource.FindFieldCriteria;
import stroom.query.api.datasource.QueryField;
import stroom.query.common.v2.DataSourceProviderRegistry;
import stroom.query.planner.port.FieldInfoSource;
import stroom.util.shared.PageRequest;

import jakarta.inject.Inject;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The real {@link FieldInfoSource}, wrapping {@link DataSourceResolver} (name/UUID -> {@link DocRef}) and
 * {@link DataSourceProviderRegistry} (field metadata) - see {@code docs/query-optimiser-implementation-plan.md},
 * Task 2.2. This is the adapter half of the port/adapter split: {@code stroom-query-planner}'s {@code Binder}
 * depends only on the {@link FieldInfoSource} interface, never on this class or the types it wraps.
 */
public class FieldInfoSourceAdapter implements FieldInfoSource {

    private final DataSourceResolver dataSourceResolver;
    private final DataSourceProviderRegistry dataSourceProviderRegistry;

    @Inject
    public FieldInfoSourceAdapter(final DataSourceResolver dataSourceResolver,
                                  final DataSourceProviderRegistry dataSourceProviderRegistry) {
        this.dataSourceResolver = Objects.requireNonNull(dataSourceResolver, "dataSourceResolver");
        this.dataSourceProviderRegistry = Objects.requireNonNull(
                dataSourceProviderRegistry, "dataSourceProviderRegistry");
    }

    /**
     * @param dataSourceName never null.
     * @return never null; every field the resolved datasource exposes, or empty if {@code dataSourceName} does
     *         not resolve to a known datasource (name/UUID resolution failure is not this method's error to
     *         raise - the {@code Binder} turns an empty result into a clear "unknown field"/"unknown datasource"
     *         {@code BindException} at the call site).
     */
    @Override
    public List<QueryField> getFields(final String dataSourceName) {
        Objects.requireNonNull(dataSourceName, "dataSourceName");
        final Optional<DocRef> docRef = resolve(dataSourceName);
        if (docRef.isEmpty()) {
            return List.of();
        }
        final FindFieldCriteria criteria = new FindFieldCriteria(PageRequest.unlimited(), null, docRef.get());
        return dataSourceProviderRegistry.getFieldInfo(criteria).getValues();
    }

    @Override
    public Optional<QueryField> getTimeField(final String dataSourceName) {
        Objects.requireNonNull(dataSourceName, "dataSourceName");
        final Optional<DocRef> docRef = resolve(dataSourceName);
        if (docRef.isEmpty()) {
            return Optional.empty();
        }
        final Optional<DataSourceProvider> provider =
                dataSourceProviderRegistry.getDataSourceProvider(docRef.get().getType());
        return provider.flatMap(dataSourceProvider -> dataSourceProvider.getTimeField(docRef.get()));
    }

    private Optional<DocRef> resolve(final String dataSourceName) {
        try {
            return Optional.of(dataSourceResolver.resolveDataSourceRef(dataSourceName));
        } catch (final RuntimeException e) {
            return Optional.empty();
        }
    }
}
