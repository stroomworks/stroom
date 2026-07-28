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
import stroom.docstore.api.DocFinder;
import stroom.query.api.datasource.DataSourceProvider;
import stroom.query.api.datasource.FieldType;
import stroom.query.api.datasource.QueryField;
import stroom.query.common.v2.DataSourceProviderRegistry;
import stroom.util.shared.ResultPage;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves {@link FieldInfoSourceAdapter} correctly wraps the real {@link DataSourceResolver}/
 * {@link DataSourceProviderRegistry} seam, Task 2.2
 * (the adapter half of the port/adapter split; {@link TestBinder} in {@code stroom-query-planner} covers the
 * port's consumer, the {@code Binder}, against a fake).
 */
class TestFieldInfoSourceAdapter {

    private static final DocRef EVENTS_DOC_REF = new DocRef("Index", "events-uuid", "Events");
    private static final QueryField STREAM_ID_FIELD =
            QueryField.builder().fldName("StreamId").fldType(FieldType.LONG).build();

    private DataSourceProvider mockProvider() {
        final DataSourceProvider provider = mock(DataSourceProvider.class);
        when(provider.getDataSourceType()).thenReturn("Index");
        when(provider.findDataSourceByUuid("Events")).thenReturn(Optional.empty());
        when(provider.findDataSourceByName("Events")).thenReturn(List.of(EVENTS_DOC_REF));
        when(provider.getFieldInfo(any())).thenReturn(ResultPage.createUnboundedList(List.of(STREAM_ID_FIELD)));
        return provider;
    }

    private FieldInfoSourceAdapter adapter(final DataSourceProvider provider) {
        final DataSourceProviderRegistry registry = new DataSourceProviderRegistry(() -> Set.of(provider));
        final DataSourceResolver resolver = new DataSourceResolver(() -> mock(DocFinder.class), () -> registry);
        return new FieldInfoSourceAdapter(resolver, registry);
    }

    @Test
    void getFields_resolvesDataSourceNameAndReturnsItsFieldInfo() {
        final FieldInfoSourceAdapter adapter = adapter(mockProvider());

        assertThat(adapter.getFields("Events")).containsExactly(STREAM_ID_FIELD);
    }

    @Test
    void getFields_unknownDataSourceName_returnsEmptyRatherThanThrowing() {
        final FieldInfoSourceAdapter adapter = adapter(mockProvider());

        assertThat(adapter.getFields("Bogus")).isEmpty();
    }

    @Test
    void getTimeField_delegatesToTheResolvedProvider() {
        final QueryField timeField = QueryField.builder().fldName("EventTime").fldType(FieldType.DATE).build();
        final DataSourceProvider provider = mockProvider();
        when(provider.getTimeField(EVENTS_DOC_REF)).thenReturn(Optional.of(timeField));
        final FieldInfoSourceAdapter adapter = adapter(provider);

        assertThat(adapter.getTimeField("Events")).contains(timeField);
    }

    @Test
    void getTimeField_unknownDataSourceName_returnsEmpty() {
        final FieldInfoSourceAdapter adapter = adapter(mockProvider());

        assertThat(adapter.getTimeField("Bogus")).isEmpty();
    }

    @Test
    void constructorRejectsNullArguments() {
        final DataSourceProviderRegistry registry = new DataSourceProviderRegistry(() -> Set.of(mockProvider()));
        final DataSourceResolver resolver = new DataSourceResolver(() -> mock(DocFinder.class), () -> registry);

        assertThatThrownBy(() -> new FieldInfoSourceAdapter(null, registry))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FieldInfoSourceAdapter(resolver, null))
                .isInstanceOf(NullPointerException.class);
    }
}
