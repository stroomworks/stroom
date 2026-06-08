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

package stroom.sqlstore.impl.pipeline;

import stroom.meta.shared.Meta;
import stroom.pipeline.LocationFactoryProxy;
import stroom.pipeline.errorhandler.ErrorReceiver;
import stroom.pipeline.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.state.MetaHolder;
import stroom.pipeline.util.ProcessorUtil;
import stroom.sqlstore.api.UpdatableTemporalStore;
import stroom.sqlstore.impl.UpdatableTemporalStoreProvider;
import stroom.sqlstore.shared.UnknownStoreException;
import stroom.util.shared.Severity;
import stroom.util.shared.TemporalEntry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class TestSqlStoreFilter {

    @Mock
    private UpdatableTemporalStoreProvider storeProvider;

    @Mock
    private UpdatableTemporalStore updatableTemporalStore;

    private List<Severity> loggedSeverities;
    private List<String> loggedMessages;
    private ErrorReceiverProxy errorReceiverProxy;

    @BeforeEach
    void setUp() {
        loggedSeverities = new ArrayList<>();
        loggedMessages = new ArrayList<>();

        final ErrorReceiver errorReceiver = new ErrorReceiver() {
            @Override
            public void log(final Severity severity, final stroom.util.shared.Location location,
                            final stroom.util.shared.ElementId elementId, final String message,
                            final stroom.util.shared.ErrorType errorType, final Throwable e) {
                loggedSeverities.add(severity);
                loggedMessages.add(message);
            }
        };
        errorReceiverProxy = new ErrorReceiverProxy(errorReceiver);
    }

    private void processXml(final String xml) {
        final MetaHolder metaHolder = new MetaHolder();
        final Meta meta = new Meta();
        metaHolder.setMeta(meta);

        final SqlStoreFilter sqlStoreFilter = new SqlStoreFilter(
                errorReceiverProxy,
                new LocationFactoryProxy(),
                metaHolder,
                storeProvider
        );

        final ByteArrayInputStream input = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
        ProcessorUtil.processXml(input, errorReceiverProxy, sqlStoreFilter, new LocationFactoryProxy());
    }

    @Test
    void testPlainValueParsing() {
        Mockito.when(storeProvider.get("test-map")).thenReturn(updatableTemporalStore);

        final String xml = """
                <referenceData xmlns="reference-data:2">
                    <reference>
                        <map>test-map</map>
                        <key>k1</key>
                        <value>simple-string-val</value>
                    </reference>
                </referenceData>
                """;

        processXml(xml);

        assertThat(loggedSeverities).isEmpty();

        final ArgumentCaptor<TemporalEntry> entryCaptor = ArgumentCaptor.forClass(TemporalEntry.class);
        Mockito.verify(updatableTemporalStore, Mockito.times(1)).create(entryCaptor.capture());

        final TemporalEntry captured = entryCaptor.getValue();
        assertThat(captured.getMap()).isEqualTo("test-map");
        assertThat(captured.getKey()).isEqualTo("k1");
        assertThat(captured.getValue()).isEqualTo("simple-string-val");

        // TODO Check effective date
    }

    @Test
    void testXmlValueParsing() {
        Mockito.when(storeProvider.get("test-map")).thenReturn(updatableTemporalStore);

        final String xml = """
                <referenceData xmlns="reference-data:2">
                    <reference>
                        <map>test-map</map>
                        <key>k1</key>
                        <value><Location><Country>UK</Country></Location></value>
                    </reference>
                </referenceData>
                """;

        processXml(xml);

        assertThat(loggedSeverities).isEmpty();

        final ArgumentCaptor<TemporalEntry> entryCaptor = ArgumentCaptor.forClass(TemporalEntry.class);
        Mockito.verify(updatableTemporalStore, Mockito.times(1)).create(entryCaptor.capture());

        final TemporalEntry captured = entryCaptor.getValue();
        assertThat(captured.getMap()).isEqualTo("test-map");
        assertThat(captured.getKey()).isEqualTo("k1");
        assertThat(captured.getValue()).contains("<Location><Country>UK</Country></Location>");
    }

    @Test
    void testCustomTimeHandling() {
        Mockito.when(storeProvider.get("test-map")).thenReturn(updatableTemporalStore);

        final String xml = """
                <referenceData xmlns="reference-data:2">
                    <reference>
                        <map>test-map</map>
                        <key>k1</key>
                        <time>2026-06-03T12:00:00.000Z</time>
                        <value>val</value>
                    </reference>
                </referenceData>
                """;

        processXml(xml);

        assertThat(loggedSeverities).isEmpty();

        final ArgumentCaptor<TemporalEntry> entryCaptor = ArgumentCaptor.forClass(TemporalEntry.class);
        Mockito.verify(updatableTemporalStore, Mockito.times(1)).create(entryCaptor.capture());

        final TemporalEntry captured = entryCaptor.getValue();
        assertThat(captured.getEffectiveTimeMs())
                .isEqualTo(Instant.parse("2026-06-03T12:00:00Z").toEpochMilli());
    }

    @Test
    void testUnknownStoreExceptionHandling() {
        Mockito.when(storeProvider.get("unknown-map"))
                .thenThrow(new UnknownStoreException("Unknown store: unknown-map"));

        final String xml = """
                <referenceData xmlns="reference-data:2">
                    <reference>
                        <map>unknown-map</map>
                        <key>k1</key>
                        <value>val</value>
                    </reference>
                </referenceData>
                """;

        processXml(xml);

        assertThat(loggedSeverities).contains(Severity.ERROR);
        assertThat(loggedMessages.getFirst()).contains("Unknown SQL store map 'unknown-map'");
    }

    @Test
    void testPlanBStateParsing() {
        final String xml = """
                <plan-b xmlns="plan-b:1" version="1.0">
                    <state>
                        <map>test-map</map>
                        <key>k1</key>
                        <value>plan-b-state-val</value>
                    </state>
                </plan-b>
                """;

        processXml(xml);

        assertThat(loggedSeverities).contains(Severity.ERROR);
        assertThat(loggedMessages.getFirst()).contains("SQL Store Filter can only process '<temporal-state>' "
                + "elements from the plan-b schema. Element 'state' is not supported.");
        Mockito.verify(updatableTemporalStore, Mockito.never()).create(Mockito.any());
    }

    @Test
    void testPlanBTemporalStateParsing() {
        Mockito.when(storeProvider.get("test-map")).thenReturn(updatableTemporalStore);

        final String xml = """
                <plan-b xmlns="plan-b:1" version="1.0">
                    <temporal-state>
                        <map>test-map</map>
                        <key>k1</key>
                        <time>2026-06-03T15:30:00.000Z</time>
                        <value>plan-b-temporal-state-val</value>
                    </temporal-state>
                </plan-b>
                """;

        processXml(xml);

        assertThat(loggedSeverities).isEmpty();

        final ArgumentCaptor<TemporalEntry> entryCaptor = ArgumentCaptor.forClass(TemporalEntry.class);
        Mockito.verify(updatableTemporalStore, Mockito.times(1)).create(entryCaptor.capture());

        final TemporalEntry captured = entryCaptor.getValue();
        assertThat(captured.getMap()).isEqualTo("test-map");
        assertThat(captured.getKey()).isEqualTo("k1");
        assertThat(captured.getEffectiveTimeMs())
                .isEqualTo(Instant.parse("2026-06-03T15:30:00Z").toEpochMilli());
        assertThat(captured.getValue()).isEqualTo("plan-b-temporal-state-val");
    }

    @Test
    void testPlanBRangeStateParsing() {
        final String xml = """
                <plan-b xmlns="plan-b:1" version="1.0">
                    <range-state>
                        <map>test-map</map>
                        <range>
                            <from>1000</from>
                            <to>2000</to>
                        </range>
                        <value>plan-b-range-state-val</value>
                    </range-state>
                </plan-b>
                """;

        processXml(xml);

        assertThat(loggedSeverities).contains(Severity.ERROR);
        assertThat(loggedMessages.getFirst()).contains("SQL Store Filter can only process '<temporal-state>' "
                + "elements from the plan-b schema. Element 'range-state' is not supported.");
        Mockito.verify(updatableTemporalStore, Mockito.never()).create(Mockito.any());
    }
}
