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

package stroom.graphdb.impl;

import stroom.query.api.datasource.FieldType;
import stroom.query.common.v2.ExpressionPredicateFactory.ValueFunctionFactory;
import stroom.query.language.functions.Type;
import stroom.query.language.functions.Val;
import stroom.util.date.DateUtil;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * A {@link ValueFunctionFactory} over a traversal row (a {@code "variable.property"} &rarr; {@link Val} map -
 * see {@link GraphTraversalEngine}), mirroring {@code stroom.query.common.v2.ValuesFunctionFactory}'s extractor
 * bodies exactly so a graph's {@code WHERE}/anchor-property predicates get the same numeric/date/text comparison
 * semantics as any other Stroom row predicate (Task PoC.5).
 */
final class GraphRowValueFunctionFactory implements ValueFunctionFactory<Map<String, Val>> {

    private final String key;

    GraphRowValueFunctionFactory(final String key) {
        this.key = Objects.requireNonNull(key, "key");
    }

    @Override
    public Function<Map<String, Val>, Boolean> createNullCheck() {
        return row -> {
            final Val val = row.get(key);
            return val == null || Type.NULL.equals(val.type());
        };
    }

    @Override
    public Function<Map<String, Val>, String> createStringExtractor() {
        return row -> {
            final Val val = row.get(key);
            return val == null ? null : val.toString();
        };
    }

    @Override
    public Function<Map<String, Val>, Long> createDateExtractor() {
        return row -> {
            final Val val = row.get(key);
            if (val == null) {
                return null;
            }
            if (Type.LONG.equals(val.type()) || Type.DATE.equals(val.type())) {
                return val.toLong();
            }
            final String string = val.toString();
            if (string != null) {
                try {
                    return DateUtil.parseNormalDateTimeString(string);
                } catch (final IllegalArgumentException e) {
                    // Code-review fix: DateUtil.parseNormalDateTimeString throws IllegalArgumentException (not
                    // NumberFormatException) for a malformed ISO string, so catching only NumberFormatException
                    // let a bad date propagate and abort predicate evaluation instead of yielding "no match".
                    return null;
                }
            }
            return null;
        };
    }

    @Override
    public Function<Map<String, Val>, Double> createNumberExtractor() {
        return row -> {
            final Val val = row.get(key);
            if (val == null) {
                return null;
            }
            try {
                return val.toDouble();
            } catch (final RuntimeException e) {
                return null;
            }
        };
    }

    @Override
    public FieldType getFieldType() {
        // Unlike ValuesFunctionFactory (which reads a Column's declared Format), a graph property's type is
        // whatever Val type GraphPropsCodec decoded it as - there is no separate declared-type metadata here.
        // TEXT is a safe default: ExpressionPredicateFactory's comparison path tries date/numeric interpretation
        // from the extractor functions above regardless of this declared type for the general term case.
        return FieldType.TEXT;
    }
}
