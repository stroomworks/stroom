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

package stroom.graphdb.client.presenter;

import stroom.docref.DocRef;
import stroom.graphdb.client.presenter.GraphDbSettingsPresenter.GraphDbSettingsView;
import stroom.graphdb.shared.GraphDbDoc;
import stroom.graphdb.shared.GraphNodeTypeMapping;
import stroom.planb.shared.RetentionSettings;
import stroom.planb.shared.TemporalPrecision;

import com.google.web.bindery.event.shared.SimpleEventBus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task B3: a round-trip test for
 * {@link GraphDbSettingsPresenter} - set each of the three Tier-1 fields via the view, save, reopen, and assert
 * the persisted values, while asserting that {@code description} is never touched by this presenter (see its
 * class javadoc, and {@link GraphDbPresenter}'s, for why that field must stay untouched).
 */
class TestGraphDbSettingsPresenter {

    private static final DocRef DOC_REF = GraphDbDoc.buildDocRef().uuid("test-uuid").name("MyGraph").build();

    private static GraphDbSettingsPresenter newPresenter(final GraphDbSettingsView view) {
        return new GraphDbSettingsPresenter(new SimpleEventBus(), view);
    }

    @Test
    void read_pushesTheThreeTierOneFieldsIntoTheView() {
        final GraphDbSettingsView view = mock(GraphDbSettingsView.class);
        final GraphDbSettingsPresenter presenter = newPresenter(view);

        final RetentionSettings retention = new RetentionSettings.Builder().enabled(true).build();
        final List<GraphNodeTypeMapping> mappings = List.of(new GraphNodeTypeMapping("User", "User.id"));
        final GraphDbDoc doc = GraphDbDoc.builder()
                .uuid("test-uuid")
                .name("MyGraph")
                .description("Original description")
                .temporalPrecision(TemporalPrecision.SECOND)
                .retention(retention)
                .nodeTypeMappings(mappings)
                .build();

        presenter.read(DOC_REF, doc, false);

        verify(view).setTemporalPrecision(TemporalPrecision.SECOND);
        verify(view).setRetention(retention);
        verify(view).setNodeTypeMappings(mappings);
    }

    @Test
    void write_updatesTheThreeFields_andLeavesDescriptionUntouched() {
        final GraphDbSettingsView view = mock(GraphDbSettingsView.class);
        final GraphDbSettingsPresenter presenter = newPresenter(view);

        final GraphDbDoc original = GraphDbDoc.builder()
                .uuid("test-uuid")
                .name("MyGraph")
                .description("Original description")
                .temporalPrecision(TemporalPrecision.DAY)
                .build();
        presenter.read(DOC_REF, original, false);

        final RetentionSettings newRetention = new RetentionSettings.Builder()
                .enabled(true)
                .useStateTime(true)
                .build();
        final List<GraphNodeTypeMapping> newMappings = List.of(
                new GraphNodeTypeMapping("User", "User.id"),
                new GraphNodeTypeMapping("Group", "Group.name"));

        when(view.getTemporalPrecision()).thenReturn(TemporalPrecision.NANOSECOND);
        when(view.getRetention()).thenReturn(newRetention);
        when(view.getNodeTypeMappings()).thenReturn(newMappings);

        final GraphDbDoc written = presenter.write(original);

        assertThat(written.getDescription()).isEqualTo("Original description");
        assertThat(written.getTemporalPrecision()).isEqualTo(TemporalPrecision.NANOSECOND);
        assertThat(written.getRetention()).isEqualTo(newRetention);
        assertThat(written.getNodeTypeMappings()).isEqualTo(newMappings);
    }

    @Test
    void roundTrip_setEachField_saveReopen_assertPersistedValues_descriptionUnaffected() {
        // Open a fresh doc.
        final GraphDbSettingsView view = mock(GraphDbSettingsView.class);
        final GraphDbSettingsPresenter presenter = newPresenter(view);

        final GraphDbDoc fresh = GraphDbDoc.builder()
                .uuid("test-uuid")
                .name("MyGraph")
                .description("Keep me")
                .build();
        presenter.read(DOC_REF, fresh, false);

        // The user sets each of the three Tier-1 fields via the view.
        final RetentionSettings retention = new RetentionSettings.Builder()
                .enabled(true)
                .useStateTime(true)
                .build();
        final List<GraphNodeTypeMapping> mappings = List.of(new GraphNodeTypeMapping("User", "User.id"));

        when(view.getTemporalPrecision()).thenReturn(TemporalPrecision.HOUR);
        when(view.getRetention()).thenReturn(retention);
        when(view.getNodeTypeMappings()).thenReturn(mappings);

        // Save.
        final GraphDbDoc saved = presenter.write(fresh);
        assertThat(saved.getDescription()).isEqualTo("Keep me");
        assertThat(saved.getTemporalPrecision()).isEqualTo(TemporalPrecision.HOUR);
        assertThat(saved.getRetention()).isEqualTo(retention);
        assertThat(saved.getNodeTypeMappings()).isEqualTo(mappings);

        // Reopen: a fresh presenter/view pair reads the persisted doc back.
        final GraphDbSettingsView reopenedView = mock(GraphDbSettingsView.class);
        final GraphDbSettingsPresenter reopenedPresenter = newPresenter(reopenedView);
        reopenedPresenter.read(DOC_REF, saved, false);

        verify(reopenedView).setTemporalPrecision(TemporalPrecision.HOUR);
        verify(reopenedView).setRetention(retention);
        verify(reopenedView).setNodeTypeMappings(mappings);

        // Saving again from the reopened view (which reflects exactly what was persisted, i.e. the user made
        // no further changes) must leave description untouched a second time.
        when(reopenedView.getTemporalPrecision()).thenReturn(TemporalPrecision.HOUR);
        when(reopenedView.getRetention()).thenReturn(retention);
        when(reopenedView.getNodeTypeMappings()).thenReturn(mappings);
        final GraphDbDoc resaved = reopenedPresenter.write(saved);
        assertThat(resaved.getDescription()).isEqualTo("Keep me");
        assertThat(resaved).isEqualTo(saved);
    }
}
