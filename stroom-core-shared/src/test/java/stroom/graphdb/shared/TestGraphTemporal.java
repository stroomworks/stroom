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

package stroom.graphdb.shared;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestGraphTemporal {

    // 2026-07-01T09:00:00Z (epoch millis).
    private static final long INSTANT = 1_782_896_400_000L;
    private static final String INSTANT_ISO = "2026-07-01T09:00:00Z";

    // ---- ISO <-> millis ----

    @Test
    void formatsInstantAsIsoUtc() {
        assertThat(GraphTemporal.formatIsoUtc(INSTANT)).isEqualTo(INSTANT_ISO);
    }

    @Test
    void formatsEpochAsIsoUtc() {
        assertThat(GraphTemporal.formatIsoUtc(0L)).isEqualTo("1970-01-01T00:00:00Z");
    }

    @Test
    void parsesIsoUtcRoundTrip() {
        assertThat(GraphTemporal.parseIsoUtc(INSTANT_ISO)).isEqualTo(INSTANT);
    }

    @Test
    void formatParseRoundTripsAcrossManyInstants() {
        for (long millis = 0; millis < 40L * 365 * 86_400_000L; millis += 987_654_321L) {
            final long truncated = (millis / 1000L) * 1000L;
            assertThat(GraphTemporal.parseIsoUtc(GraphTemporal.formatIsoUtc(truncated)))
                    .as("round trip at millis=%d", truncated)
                    .isEqualTo(truncated);
        }
    }

    @Test
    void parsesDateOnly() {
        assertThat(GraphTemporal.parseIsoUtc("1970-01-02")).isEqualTo(86_400_000L);
    }

    @Test
    void parsesWithoutTrailingZ() {
        assertThat(GraphTemporal.parseIsoUtc("2026-07-01T09:00:00")).isEqualTo(INSTANT);
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> GraphTemporal.parseIsoUtc("not a date"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOutOfRangeMonth() {
        assertThatThrownBy(() -> GraphTemporal.parseIsoUtc("2026-13-01T00:00:00Z"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- query rewriting ----

    @Test
    void insertsAsOfBeforeReturn() {
        assertThat(GraphTemporal.withAsOf("MATCH (n) RETURN GRAPH LIMIT 100", INSTANT))
                .isEqualTo("MATCH (n) AS OF datetime('" + INSTANT_ISO + "') RETURN GRAPH LIMIT 100");
    }

    @Test
    void insertsAsOfBeforeWhere() {
        assertThat(GraphTemporal.withAsOf("MATCH (n:Host) WHERE n.name = 'x' RETURN GRAPH", INSTANT))
                .isEqualTo("MATCH (n:Host) AS OF datetime('" + INSTANT_ISO + "') WHERE n.name = 'x' RETURN GRAPH");
    }

    @Test
    void doesNotMistakeKeywordInsideStringLiteral() {
        // 'RETURN' and 'WHERE' inside the quoted value must not be treated as clause boundaries.
        final String query = "MATCH (n) WHERE n.msg = 'please RETURN home' RETURN GRAPH";
        assertThat(GraphTemporal.withAsOf(query, INSTANT))
                .isEqualTo("MATCH (n) AS OF datetime('" + INSTANT_ISO
                        + "') WHERE n.msg = 'please RETURN home' RETURN GRAPH");
    }

    @Test
    void replacesExistingAsOf() {
        final String query = "MATCH (n) AS OF datetime('2020-01-01T00:00:00Z') RETURN GRAPH LIMIT 100";
        assertThat(GraphTemporal.withAsOf(query, INSTANT))
                .isEqualTo("MATCH (n) AS OF datetime('" + INSTANT_ISO + "') RETURN GRAPH LIMIT 100");
    }

    @Test
    void replacesExistingAround() {
        final String query =
                "MATCH (n) AROUND datetime('2020-01-01T00:00:00Z') +/- duration('PT1H') RETURN GRAPH";
        assertThat(GraphTemporal.withAsOf(query, INSTANT))
                .isEqualTo("MATCH (n) AS OF datetime('" + INSTANT_ISO + "') RETURN GRAPH");
    }

    @Test
    void replacesExistingBetween() {
        final String query =
                "MATCH (n) BETWEEN datetime('2020-01-01T00:00:00Z') AND datetime('2021-01-01T00:00:00Z') RETURN GRAPH";
        assertThat(GraphTemporal.withAsOf(query, INSTANT))
                .isEqualTo("MATCH (n) AS OF datetime('" + INSTANT_ISO + "') RETURN GRAPH");
    }

    @Test
    void replacesExistingDiff() {
        final String query =
                "MATCH (n) DIFF FROM datetime('2020-01-01T00:00:00Z') TO datetime('2021-01-01T00:00:00Z') RETURN GRAPH";
        assertThat(GraphTemporal.withAsOf(query, INSTANT))
                .isEqualTo("MATCH (n) AS OF datetime('" + INSTANT_ISO + "') RETURN GRAPH");
    }

    @Test
    void leavesQueryWithoutReturnUnchanged() {
        assertThat(GraphTemporal.withAsOf("MATCH (n)", INSTANT)).isEqualTo("MATCH (n)");
    }

    @Test
    void doesNotTreatReturnItemAliasAsTemporalAsOf() {
        // The `AS deg` alias comes after RETURN, so the scan must have stopped and not seen it as `AS OF`.
        final String query = "MATCH (n) RETURN n.id, degree(n) AS deg";
        assertThat(GraphTemporal.withAsOf(query, INSTANT))
                .isEqualTo("MATCH (n) AS OF datetime('" + INSTANT_ISO + "') RETURN n.id, degree(n) AS deg");
    }

    @Test
    void handlesNullQuery() {
        assertThat(GraphTemporal.withAsOf(null, INSTANT)).isNull();
    }

    // ---- DIFF-compare rewriting ----

    @Test
    void insertsDiffClause() {
        final long from = GraphTemporal.parseIsoUtc("2026-01-01T00:00:00Z");
        assertThat(GraphTemporal.withDiff("MATCH (n) RETURN GRAPH LIMIT 100", from, INSTANT))
                .isEqualTo("MATCH (n) DIFF FROM datetime('2026-01-01T00:00:00Z') TO datetime('"
                        + INSTANT_ISO + "') RETURN GRAPH LIMIT 100");
    }

    @Test
    void diffReplacesExistingTemporalClause() {
        final long from = GraphTemporal.parseIsoUtc("2026-01-01T00:00:00Z");
        final String query = "MATCH (n) AS OF datetime('2020-01-01T00:00:00Z') RETURN GRAPH";
        assertThat(GraphTemporal.withDiff(query, from, INSTANT))
                .isEqualTo("MATCH (n) DIFF FROM datetime('2026-01-01T00:00:00Z') TO datetime('"
                        + INSTANT_ISO + "') RETURN GRAPH");
    }

    @Test
    void diffLeavesQueryWithoutReturnUnchanged() {
        assertThat(GraphTemporal.withDiff("MATCH (n)", INSTANT, INSTANT)).isEqualTo("MATCH (n)");
    }
}
