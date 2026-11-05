/*
 * Copyright 2026 Crown Copyright
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

package stroom.query.common.v2;

import stroom.docref.DocRef;
import stroom.docstore.api.DependencyRemapper;
import stroom.query.language.SearchRequestFactory;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;
import stroom.util.shared.NullSafe;

import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/// Remaps the data source a StroomQL query reads (its `from` clause), for a document that holds the
/// query's text, e.g. a query document or a dashboard's StroomQL Query pane. The data source is recorded as
/// a dependency of the document, and if it is remapped (e.g. on import) the query's text is changed to
/// name the new one.
public class QueryDataSourceRemapper {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(QueryDataSourceRemapper.class);

    private final SearchRequestFactory searchRequestFactory;
    private final Provider<DataSourceProviderRegistry> dataSourceProviderRegistryProvider;

    /// @param searchRequestFactory               Finds the data source the query names.
    /// @param dataSourceProviderRegistryProvider Gives the data sources there are.
    @Inject
    public QueryDataSourceRemapper(final SearchRequestFactory searchRequestFactory,
                                   final Provider<DataSourceProviderRegistry> dataSourceProviderRegistryProvider) {
        this.searchRequestFactory = searchRequestFactory;
        this.dataSourceProviderRegistryProvider = dataSourceProviderRegistryProvider;
    }

    /// Records the query's data source as a dependency and gives the query naming its remapped data source.
    /// A query that can't be read, or that names no known data source, is returned as it is.
    ///
    /// @param query              The query's text, or null.
    /// @param dependencyRemapper Records the dependency and gives the data source's remapping.
    /// @return The query, naming the remapped data source if it changed.
    public String remap(final String query,
                        final DependencyRemapper dependencyRemapper) {
        if (NullSafe.isBlankString(query)) {
            return query;
        }
        final AtomicReference<String> result = new AtomicReference<>(query);
        try {
            searchRequestFactory.extractDataSourceOnly(query, docRef -> {
                try {
                    findDataSource(docRef).ifPresent(dataSourceRef -> {
                        final DocRef remapped = dependencyRemapper.remap(dataSourceRef);
                        if (remapped != null) {
                            result.set(rename(query, docRef, remapped));
                        }
                    });
                } catch (final RuntimeException e) {
                    LOGGER.debug(e::getMessage, e);
                }
            });
        } catch (final RuntimeException e) {
            LOGGER.debug(e::getMessage, e);
        }
        return result.get();
    }

    // The known data source the query names
    private Optional<DocRef> findDataSource(final DocRef docRef) {
        if (docRef == null) {
            return Optional.empty();
        }
        return dataSourceProviderRegistryProvider.get()
                .getDataSourceDocRefs()
                .stream()
                .filter(dataSourceRef -> dataSourceRef.equals(docRef))
                .findAny();
    }

    // The query naming the remapped data source in place of the one it names, by name or by UUID
    private static String rename(final String query,
                                 final DocRef docRef,
                                 final DocRef remapped) {
        String renamed = query;
        if (NullSafe.isNonBlankString(remapped.getName()) &&
            !Objects.equals(remapped.getName(), docRef.getName())) {
            renamed = replaceFirst(renamed, docRef.getName(), remapped.getName());
        }
        if (NullSafe.isNonBlankString(remapped.getUuid()) &&
            !Objects.equals(remapped.getUuid(), docRef.getUuid())) {
            renamed = replaceFirst(renamed, docRef.getUuid(), remapped.getUuid());
        }
        return renamed;
    }

    // Replaces the first occurrence of the text as it is, not as a regular expression, as a name may hold
    // characters that have a meaning in one, e.g. "Events (2024)"
    private static String replaceFirst(final String text,
                                       final String target,
                                       final String replacement) {
        if (NullSafe.isEmptyString(target)) {
            return text;
        }
        final int index = text.indexOf(target);
        if (index < 0) {
            return text;
        }
        return text.substring(0, index) + replacement + text.substring(index + target.length());
    }
}
