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

import java.util.Objects;

/**
 * A string literal, already unescaped and with its surrounding quotes stripped.
 *
 * @param value    never null; the unescaped string content.
 * @param position never null.
 */
public record AstStringValue(String value, AstPosition position) implements AstValue {

    public AstStringValue {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(position, "position");
    }
}
