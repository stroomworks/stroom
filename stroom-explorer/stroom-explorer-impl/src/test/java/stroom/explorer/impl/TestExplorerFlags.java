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

package stroom.explorer.impl;

import stroom.explorer.shared.NodeFlag;
import stroom.graphdb.shared.GraphDbDoc;
import stroom.planb.shared.PlanBDoc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task P5.2: {@link GraphDbDoc#TYPE} must be flagged as a data source in the explorer tree, exactly like every
 * other datasource doc type already is.
 */
class TestExplorerFlags {

    @Test
    void getStandardFlagByDocType_flagsGraphDbAsADataSource() {
        assertThat(ExplorerFlags.getStandardFlagByDocType(GraphDbDoc.TYPE)).contains(NodeFlag.DATA_SOURCE);
    }

    @Test
    void getStandardFlagByDocType_matchesAnExistingDataSourceDocType() {
        // Sanity check that the assertion above is exercising the real map, not a typo'd no-op.
        assertThat(ExplorerFlags.getStandardFlagByDocType(PlanBDoc.TYPE)).contains(NodeFlag.DATA_SOURCE);
    }

    @Test
    void getStandardFlagByDocType_returnsEmptyForAnUnknownType() {
        assertThat(ExplorerFlags.getStandardFlagByDocType("SomeUnknownType")).isEmpty();
    }
}
