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

package stroom.pathways.shared.pathway;

import stroom.docref.HasDisplayValue;
import stroom.util.shared.HasPrimitiveValue;
import stroom.util.shared.PrimitiveValueConverter;

/**
 * The kinds of change a trace can make to a learnt model. Stored with each mutation so a replay knows
 * what it is showing without having to work it out from the values.
 */
public enum MutationType implements HasDisplayValue, HasPrimitiveValue {
    /** First sight of this root operation. */
    PATHWAY_ADDED("Pathway Added", 0),
    /** A step the model had not seen beneath this parent. */
    NODE_ADDED("Node Added", 1),
    /** First value recorded for this constraint. */
    CONSTRAINT_ADDED("Constraint Added", 2),
    /** A constraint widened to admit something it did not before. */
    CONSTRAINT_CHANGED("Constraint Changed", 3),
    /** A required attribute was absent, so the constraint stopped being required. */
    CONSTRAINT_OPTIONAL("Constraint Optional", 4),
    /** The bottom of a range moved down to admit something smaller than anything seen before. */
    CONSTRAINT_MIN_EXPANDED("Min Expanded", 5),
    /** The top of a range moved up to admit something larger than anything seen before. */
    CONSTRAINT_MAX_EXPANDED("Max Expanded", 6),
    /** Another value joined the set of those already seen. */
    CONSTRAINT_SET_EXPANDED("Set Expanded", 7),
    /** Too many values to list, so the constraint stopped saying anything. One way. */
    CONSTRAINT_GENERALISED("Generalised", 8),
    /** Too many values to list, so they became the range they span, gaps included. */
    CONSTRAINT_RANGED("Ranged", 9),
    /** A value of a type the constraint had not held before, so it stopped checking the type. */
    CONSTRAINT_TYPE_CONFLICT("Type Conflict", 10),
    /** Named in the configuration as one not to learn, so recorded as admitting anything. */
    CONSTRAINT_IGNORED("Ignored", 11);

    public static final PrimitiveValueConverter<MutationType> PRIMITIVE_VALUE_CONVERTER =
            PrimitiveValueConverter.create(MutationType.class, MutationType.values());

    private final String displayValue;
    private final byte primitiveValue;

    MutationType(final String displayValue, final int primitiveValue) {
        this.displayValue = displayValue;
        this.primitiveValue = (byte) primitiveValue;
    }

    @Override
    public String getDisplayValue() {
        return displayValue;
    }

    @Override
    public byte getPrimitiveValue() {
        return primitiveValue;
    }
}
