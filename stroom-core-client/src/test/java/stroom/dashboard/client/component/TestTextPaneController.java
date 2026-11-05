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

package stroom.dashboard.client.component;

import stroom.dashboard.client.main.DashboardPane;
import stroom.dashboard.client.main.PaneRegistry.ComponentType;
import stroom.dashboard.client.main.Panes;

import com.google.gwt.junit.GWTMockUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

class TestTextPaneController {

    private Panes panes;

    @BeforeEach
    void setUp() {
        // The pane types' classes make GWT resources as they load, which only works in a browser
        GWTMockUtilities.disarm();
        panes = Mockito.mock(Panes.class);
        pane("table-1", TableController.TYPE);
        pane("embedded-query-1", EmbeddedQueryController.TYPE);
        pane("stroomql-table-1", StroomQLTablePaneController.TYPE);
        pane("stroomql-query-1", StroomQLQueryPaneController.TYPE);
    }

    @AfterEach
    void tearDown() {
        GWTMockUtilities.restore();
    }

    @Test
    void linkTableId_table() {
        assertThat(TextPaneController.linkTableId(panes, "table-1"))
                .isEqualTo("table-1");
    }

    @Test
    void linkTableId_embeddedQuery() {
        // Regression test: only the Table type was accepted, so a Text pane showing an Embedded Query's rows
        // lost it each time the dashboard linked its panes
        assertThat(TextPaneController.linkTableId(panes, "embedded-query-1"))
                .isEqualTo("embedded-query-1");
    }

    @Test
    void linkTableId_stroomQLTable() {
        assertThat(TextPaneController.linkTableId(panes, "stroomql-table-1"))
                .isEqualTo("stroomql-table-1");
    }

    @Test
    void linkTableId_notATable() {
        // A pane with no rows to show, so the Text pane shows any table's
        assertThat(TextPaneController.linkTableId(panes, "stroomql-query-1"))
                .isNull();
    }

    @Test
    void linkTableId_removedTable() {
        assertThat(TextPaneController.linkTableId(panes, "gone-1"))
                .isNull();
    }

    @Test
    void linkTableId_noTable() {
        assertThat(TextPaneController.linkTableId(panes, null))
                .isNull();
    }

    private void pane(final String id, final ComponentType type) {
        final DashboardPane pane = Mockito.mock(DashboardPane.class);
        Mockito.when(pane.getId()).thenReturn(id);
        Mockito.when(pane.getComponentType()).thenReturn(type);
        Mockito.when(panes.get(id)).thenReturn(pane);
    }
}
