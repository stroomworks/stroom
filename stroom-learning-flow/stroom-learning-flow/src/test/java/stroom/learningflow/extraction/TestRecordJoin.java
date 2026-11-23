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

package stroom.learningflow.extraction;

import stroom.learningflow.scoring.OutputRecords;
import stroom.learningflow.scoring.Records;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// What a transform wrote for each record, back as one document (§12 item 25).
class TestRecordJoin {

    @Test
    void theRecordsOfEachDocumentBecomeTheRecordsOfOne() {
        final String joined = RecordJoin.join(List.of(
                "<Events xmlns=\"event-logging:3\"><Event><Id>1</Id></Event></Events>",
                "<Events xmlns=\"event-logging:3\"><Event><Id>2</Id></Event></Events>"));

        assertThat(Records.parsed(joined)).isEqualTo(2);
        assertThat(OutputRecords.parse(joined).orElseThrow().root().getNodeName().getNamespaceURI())
                .isEqualTo("event-logging:3");
    }

    @Test
    void aRecordThatDeclaresANamespaceOfItsOwnKeepsIt() {
        // Only the first document's namespaces used to be passed on, so an element a later record
        // declared its own namespace for — a declared skip is the case that found this — was written
        // with no binding at all and read back in whatever namespace was in force around it. The marker
        // then counted as an event, failed the Schema-valid gate, and reached the writer.
        final String joined = RecordJoin.join(List.of(
                "<Events xmlns=\"event-logging:3\"><Event><Id>1</Id></Event></Events>",
                "<Events xmlns=\"event-logging:3\">"
                + "<Skipped xmlns=\"learningflow:skips\" reason=\"header line\"/></Events>",
                "<Events xmlns=\"event-logging:3\"><Event><Id>2</Id></Event></Events>"));

        assertThat(Records.parsed(joined)).describedAs("the two events, and not the marker").isEqualTo(2);
        assertThat(Records.skips(joined)).isEqualTo(1);
        assertThat(Records.skipReasons(joined)).containsExactly("header line");
    }

    @Test
    void theRecordsAfterOneThatDeclaredItsOwnAreStillInTheirOwnNamespace() {
        // The other half of the same fault: a declaration passed on without its end left the joined
        // document's namespace stack one deep in the wrong place
        final String joined = RecordJoin.join(List.of(
                "<Events xmlns=\"event-logging:3\">"
                + "<Skipped xmlns=\"learningflow:skips\" reason=\"header line\"/></Events>",
                "<Events xmlns=\"event-logging:3\"><Event><Id>1</Id></Event></Events>"));

        assertThat(OutputRecords.parse(joined).orElseThrow().records())
                .allMatch(record -> "event-logging:3".equals(record.getNodeName().getNamespaceURI()));
    }

    @Test
    void nothingToJoinIsNoDocument() {
        assertThat(RecordJoin.join(List.of())).isNull();
        assertThat(RecordJoin.join(List.of("not markup"))).isNull();
    }
}
