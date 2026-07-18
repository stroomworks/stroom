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

package stroom.query.grammar.ast.cypher;

import stroom.query.grammar.ast.AstPosition;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * {@code (variable:Label1:Label2 {key: value, ...})} - every part but the parentheses is optional.
 *
 * @param variable   nullable - anonymous nodes (e.g. an intermediate hop never referenced again) omit it.
 * @param labels     never null; possibly empty; in source order.
 * @param properties never null; possibly empty; in source order. An anchor node's properties are typically an
 *                   equality anchor (design doc &sect;5.1's property index); properties on a non-anchor node are
 *                   an additional post-expand filter.
 * @param position   never null.
 */
public record AstNodePattern(
        @Nullable String variable,
        List<String> labels,
        List<AstPropertyKeyValue> properties,
        AstPosition position) {

    public AstNodePattern {
        Objects.requireNonNull(labels, "labels");
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(position, "position");
        labels = List.copyOf(labels);
        properties = List.copyOf(properties);
    }
}
