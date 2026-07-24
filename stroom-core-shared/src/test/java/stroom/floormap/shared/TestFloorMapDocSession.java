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

import stroom.floormap.shared.FloorMapFieldMapping.Role;
import stroom.floormap.shared.TypeStyle.Shape;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestFloorMapDocSession {

    /** A pre-area schema (no Geometry/Fill/Opacity roles). */
    private static final List<FloorMapFieldMapping> PRE_AREA_SCHEMA = List.of(
            new FloorMapFieldMapping(".type", Role.TYPE, "Type", null),
            new FloorMapFieldMapping(".coords", Role.POSITION, "Coords", null),
            new FloorMapFieldMapping(".tm-world-to-map", Role.WORLD_TO_MAP, null, null));

    private static final TypeStyle GATE = new TypeStyle("gate", Shape.CIRCLE, "#ff0000");

    private FloorMapDocSession session;

    @BeforeEach
    void setUp() {
        session = new FloorMapDocSession();
    }

    private static FloorMapDoc doc(final List<FloorMapFieldMapping> schema,
                                   final List<TypeStyle> typeStyles) {
        return FloorMapDoc.builder()
                .uuid("test-uuid")
                .name("test-map")
                .valueFormat(ValueFormat.JSON)
                .valueSchema(schema)
                .typeStyles(typeStyles)
                .build();
    }

    // -----------------------------------------------------------------------

    /** With nothing staged, the effective lists are the entity's and write is a no-op. */
    @Test
    void testNoPendingEdits() {
        final FloorMapDoc d = doc(PRE_AREA_SCHEMA, List.of(GATE));
        assertThat(session.hasPendingDocEdits()).isFalse();
        assertThat(session.valueSchema(d.getValueSchema())).isEqualTo(PRE_AREA_SCHEMA);
        assertThat(session.typeStyles(d.getTypeStyles())).containsExactly(GATE);
        assertThat(session.applyToWrite(d)).isSameAs(d);
    }

    /** A Layers-panel edit becomes the effective + written type styles. */
    @Test
    void testStageTypeStyles() {
        final FloorMapDoc d = doc(PRE_AREA_SCHEMA, List.of(GATE));
        final TypeStyle door = new TypeStyle("door", Shape.SQUARE, "#00ff00");
        session.stageTypeStyles(List.of(GATE, door));

        assertThat(session.typeStyles(d.getTypeStyles())).containsExactly(GATE, door);
        assertThat(session.applyToWrite(d).getTypeStyles()).containsExactly(GATE, door);
    }

    /** Staging the area upgrade adds the area schema roles and the "area" style. */
    @Test
    void testStageAreaUpgrade() {
        final FloorMapDoc d = doc(PRE_AREA_SCHEMA, List.of(GATE));
        session.stageAreaUpgrade(d.getValueSchema(), d.getValueFormat(), d.getTypeStyles());

        assertThat(FloorMapDocSession.hasAreaSupport(session.valueSchema(d.getValueSchema()))).isTrue();
        assertThat(FloorMapDocSession.hasAreaStyle(session.typeStyles(d.getTypeStyles()))).isTrue();

        final FloorMapDoc written = session.applyToWrite(d);
        assertThat(FloorMapDocSession.hasAreaSupport(written.getValueSchema())).isTrue();
        assertThat(FloorMapDocSession.hasAreaStyle(written.getTypeStyles())).isTrue();
    }

    /**
     * A Layers edit made around a pending area upgrade must NOT drop the "area"
     * style — onWrite folds it in (the wedge-fix regression).
     */
    @Test
    void testLayersEditAroundAreaUpgradeKeepsAreaStyle() {
        final FloorMapDoc d = doc(PRE_AREA_SCHEMA, List.of(GATE));
        session.stageAreaUpgrade(d.getValueSchema(), d.getValueFormat(), d.getTypeStyles());
        // Layers panel then reorders/edits, producing a list WITHOUT the area style.
        session.stageTypeStyles(List.of(GATE));

        final FloorMapDoc written = session.applyToWrite(d);
        assertThat(FloorMapDocSession.hasAreaSupport(written.getValueSchema())).isTrue();
        assertThat(FloorMapDocSession.hasAreaStyle(written.getTypeStyles())).isTrue();
    }

    /** The area upgrade is dropped after read only once schema AND style are present. */
    @Test
    void testReconcileDropsAreaUpgradeWhenPersisted() {
        final FloorMapDoc d = doc(PRE_AREA_SCHEMA, List.of(GATE));
        session.stageAreaUpgrade(d.getValueSchema(), d.getValueFormat(), d.getTypeStyles());

        // Re-read of the still-old doc: upgrade stays pending.
        session.reconcileAfterRead(d);
        assertThat(session.hasPendingDocEdits()).isTrue();

        // Re-read of the saved (upgraded) doc: upgrade is dropped.
        session.reconcileAfterRead(session.applyToWrite(d));
        assertThat(session.hasPendingDocEdits()).isFalse();
    }

    /** sessionEntity applies pending edits to the returned document. */
    @Test
    void testSessionEntityAppliesPending() {
        final FloorMapDoc d = doc(PRE_AREA_SCHEMA, List.of(GATE));
        session.stageAreaUpgrade(d.getValueSchema(), d.getValueFormat(), d.getTypeStyles());

        final FloorMapDoc effective = session.sessionEntity(d);
        assertThat(FloorMapDocSession.hasAreaSupport(effective.getValueSchema())).isTrue();
        assertThat(FloorMapDocSession.hasAreaStyle(effective.getTypeStyles())).isTrue();
    }
}
