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

package stroom.floormap.shared;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class TestFloorMapAreaCellText {

    // -----------------------------------------------------------------------
    // joinNames — the one form of words for every row
    // -----------------------------------------------------------------------

    /** A single area is just its name, with no separator. */
    @Test
    void testJoinSingle() {
        assertThat(FloorMapAreaCellText.joinNames(Collections.singletonList("Loading Bay")))
                .isEqualTo("Loading Bay");
    }

    /**
     * Every containing area is named — this replaced a "Loading Bay +2" summary
     * that hid the other names.
     */
    @Test
    void testJoinListsEveryName() {
        assertThat(FloorMapAreaCellText.joinNames(
                Arrays.asList("Loading Bay", "Warehouse", "Site B")))
                .isEqualTo("Loading Bay, Warehouse, Site B");
    }

    /** Order is preserved, so innermost-first stays innermost-first. */
    @Test
    void testJoinPreservesOrder() {
        assertThat(FloorMapAreaCellText.joinNames(Arrays.asList("Inner", "Outer")))
                .isEqualTo("Inner, Outer");
        assertThat(FloorMapAreaCellText.joinNames(Arrays.asList("Outer", "Inner")))
                .isEqualTo("Outer, Inner");
    }

    /** Null/blank names are skipped without leaving a dangling separator. */
    @Test
    void testJoinSkipsBlanks() {
        assertThat(FloorMapAreaCellText.joinNames(
                Arrays.asList("Loading Bay", null, "", "Warehouse")))
                .isEqualTo("Loading Bay, Warehouse");
        assertThat(FloorMapAreaCellText.joinNames(Arrays.asList(null, "Warehouse")))
                .isEqualTo("Warehouse");
        assertThat(FloorMapAreaCellText.joinNames(Arrays.asList("Warehouse", null)))
                .isEqualTo("Warehouse");
    }

    @Test
    void testJoinEmptyAndNull() {
        assertThat(FloorMapAreaCellText.joinNames(Collections.emptyList())).isEmpty();
        assertThat(FloorMapAreaCellText.joinNames(null)).isEmpty();
        assertThat(FloorMapAreaCellText.joinNames(Arrays.asList(null, null))).isEmpty();
    }
}
