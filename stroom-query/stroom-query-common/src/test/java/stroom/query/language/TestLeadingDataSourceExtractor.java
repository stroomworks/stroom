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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Workstream A, Phase 1: {@link LeadingDataSourceExtractor}
 * tested in isolation - a pure function over query text, no grammar or resolver construction needed.
 */
class TestLeadingDataSourceExtractor {

    @Test
    void extractsADoubleQuotedName() {
        assertThat(LeadingDataSourceExtractor.extractLeadingDataSourceName("from \"My Graph\" select *"))
                .contains("My Graph");
    }

    @Test
    void extractsASingleQuotedName() {
        assertThat(LeadingDataSourceExtractor.extractLeadingDataSourceName("from 'My Graph' select *"))
                .contains("My Graph");
    }

    @Test
    void extractsABarewordName() {
        assertThat(LeadingDataSourceExtractor.extractLeadingDataSourceName("from MyIndex select *"))
                .contains("MyIndex");
    }

    @Test
    void skipsALeadingLineComment() {
        assertThat(LeadingDataSourceExtractor.extractLeadingDataSourceName(
                "// a comment\nfrom \"MyGraph\" select *"))
                .contains("MyGraph");
    }

    @Test
    void skipsALeadingBlockComment() {
        assertThat(LeadingDataSourceExtractor.extractLeadingDataSourceName(
                "/* a comment */ from \"MyGraph\" select *"))
                .contains("MyGraph");
    }

    @Test
    void returnsEmptyWhenThereIsNoLeadingFrom() {
        assertThat(LeadingDataSourceExtractor.extractLeadingDataSourceName("select 1")).isEmpty();
    }

    @Test
    void returnsEmptyForBlankText() {
        assertThat(LeadingDataSourceExtractor.extractLeadingDataSourceName("   ")).isEmpty();
    }

    @Test
    void extractsTheNameFromACypherBody() {
        final String cypher = "from \"Test Graph\"\n"
                              + "MATCH (d:Device {id: 'd-42'})-[:CONNECTED_TO]->(a:Account) RETURN a.id";
        assertThat(LeadingDataSourceExtractor.extractLeadingDataSourceName(cypher)).contains("Test Graph");
    }

    @Test
    void extractsTheNameFromACypherBodyWithAWhereAndAggregation() {
        final String cypher = "from \"Test Graph\"\n"
                              + "MATCH (a:Account) WHERE a.balance > 100 RETURN count(*) AS total";
        assertThat(LeadingDataSourceExtractor.extractLeadingDataSourceName(cypher)).contains("Test Graph");
    }

    @Test
    void extractsOnlyTheLeftSourceOfAJoin() {
        assertThat(LeadingDataSourceExtractor.extractLeadingDataSourceName(
                "from A join B on a.id = b.id select *"))
                .contains("A");
    }
}
