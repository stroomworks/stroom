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

import java.util.List;

/**
 * The Editor's pending document-level edits — the area-support upgrade and the
 * Layers-panel type-styles list — with the invariants that keep them consistent
 * across read/write.
 *
 * <p>The Editor doesn't normally write the {@link FloorMapDoc} itself (temporal
 * store edits flush separately), so these two edits are staged here and merged
 * into the document on save. The tricky rules — when a staged edit has been
 * persisted and can be dropped ({@link #reconcileAfterRead}), and how the two
 * combine on write so a Layers edit can't drop the area style
 * ({@link #applyToWrite}) — are extracted from the GWT presenter so they are
 * unit-testable on the JVM.</p>
 *
 * <p>Holds only the pending state; the loaded entity's own schema/type-styles
 * are passed in where needed (this class doesn't own the document).</p>
 */
public final class FloorMapDocSession {

    /**
     * The area-support schema upgrade staged for save, or {@code null}. When set,
     * it is the effective value schema for the session (older docs predate the
     * area roles). Its companion {@link #pendingAreaTypeStyles} seeds the "area"
     * z-order style.
     */
    private List<FloorMapFieldMapping> pendingAreaSchema;
    private List<TypeStyle> pendingAreaTypeStyles;

    /**
     * Type styles edited via the Layers panel (reorder / appearance / discovered
     * types) not yet saved. When non-null this is the authoritative ordered list.
     */
    private List<TypeStyle> pendingTypeStyles;

    /** {@code true} if any document-level edit is staged. */
    public boolean hasPendingDocEdits() {
        return pendingAreaSchema != null || pendingTypeStyles != null;
    }

    /** The value schema in effect this session: the pending upgrade, else the entity's. */
    public List<FloorMapFieldMapping> valueSchema(final List<FloorMapFieldMapping> entitySchema) {
        return pendingAreaSchema != null ? pendingAreaSchema : entitySchema;
    }

    /** The type styles in effect this session (Layers edit, else area upgrade, else entity's). */
    public List<TypeStyle> typeStyles(final List<TypeStyle> entityTypeStyles) {
        if (pendingTypeStyles != null) {
            return pendingTypeStyles;
        }
        return pendingAreaTypeStyles != null ? pendingAreaTypeStyles : entityTypeStyles;
    }

    /** Stages a Layers-panel type-styles edit. */
    public void stageTypeStyles(final List<TypeStyle> styles) {
        this.pendingTypeStyles = styles;
    }

    /**
     * Stages the area-support upgrade: the default Geometry/Fill/Opacity schema
     * mappings and an "area" type style, derived from the current effective
     * lists.
     *
     * @param baseSchema     the current effective value schema
     * @param format         the document's value format (for default paths)
     * @param baseTypeStyles the current effective type styles
     */
    public void stageAreaUpgrade(final List<FloorMapFieldMapping> baseSchema,
                                 final ValueFormat format,
                                 final List<TypeStyle> baseTypeStyles) {
        pendingAreaSchema = FloorMapFieldMapping.withAreaMappings(baseSchema, format);
        pendingAreaTypeStyles = TypeStyle.withAreaStyle(baseTypeStyles);
    }

    /**
     * Returns the document as this session sees it: the loaded entity with any
     * pending edits applied. Hand this to any child that resolves schema roles
     * itself, or fill/opacity/geometry would resolve against the pre-upgrade
     * schema until save.
     */
    public FloorMapDoc sessionEntity(final FloorMapDoc entity) {
        if (!hasPendingDocEdits()) {
            return entity;
        }
        return entity.copy()
                .valueSchema(valueSchema(entity.getValueSchema()))
                .typeStyles(typeStyles(entity.getTypeStyles()))
                .build();
    }

    /**
     * Merges the staged edits into {@code document} for save. The area upgrade
     * writes the upgraded schema; the type styles come from the Layers edit if
     * present (with the "area" style folded in when an area upgrade is also
     * pending, so a Layers edit around the upgrade can't drop it), else from the
     * area upgrade alone.
     */
    public FloorMapDoc applyToWrite(final FloorMapDoc document) {
        if (!hasPendingDocEdits()) {
            return document;
        }
        final FloorMapDoc.Builder builder = document.copy();
        if (pendingAreaSchema != null) {
            builder.valueSchema(FloorMapFieldMapping.withAreaMappings(
                    document.getValueSchema(), document.getValueFormat()));
        }
        if (pendingTypeStyles != null) {
            builder.typeStyles(pendingAreaSchema != null
                    ? TypeStyle.withAreaStyle(pendingTypeStyles)
                    : pendingTypeStyles);
        } else if (pendingAreaSchema != null) {
            builder.typeStyles(TypeStyle.withAreaStyle(document.getTypeStyles()));
        }
        return builder.build();
    }

    /**
     * Drops staged edits that the just-read document already carries (post-save
     * re-read). The area upgrade is dropped only once BOTH the schema roles and
     * the "area" style are present; the Layers edit once the doc's styles equal
     * it (accepting the area-folded form written by {@link #applyToWrite}).
     */
    public void reconcileAfterRead(final FloorMapDoc document) {
        if (pendingAreaSchema != null
                && hasAreaSupport(document.getValueSchema())
                && hasAreaStyle(document.getTypeStyles())) {
            pendingAreaSchema = null;
            pendingAreaTypeStyles = null;
        }
        if (pendingTypeStyles != null
                && (pendingTypeStyles.equals(document.getTypeStyles())
                    || TypeStyle.withAreaStyle(pendingTypeStyles)
                            .equals(document.getTypeStyles()))) {
            pendingTypeStyles = null;
        }
    }

    /** {@code true} when the schema maps every role areas need. */
    public static boolean hasAreaSupport(final List<FloorMapFieldMapping> schema) {
        return FloorMapEntryParser.findPath(schema, Role.GEOMETRY) != null
                && FloorMapEntryParser.findPath(schema, Role.FILL) != null
                && FloorMapEntryParser.findPath(schema, Role.OPACITY) != null;
    }

    /** {@code true} when the type styles contain an {@code "area"} entry. */
    public static boolean hasAreaStyle(final List<TypeStyle> typeStyles) {
        if (typeStyles != null) {
            for (final TypeStyle style : typeStyles) {
                if (style != null && FloorMapJsonKeys.AREA.equals(style.getType())) {
                    return true;
                }
            }
        }
        return false;
    }
}
