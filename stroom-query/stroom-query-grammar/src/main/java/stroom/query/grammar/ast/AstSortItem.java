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

package stroom.query.grammar.ast;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * One {@code field [direction]} within a {@code sort by} clause. {@code asc}/{@code desc} are contextual, not
 * reserved keywords (see {@code StroomQL.g4}'s file header): {@link #direction()} is just whatever name token
 * followed the field, and Task 1.4 interprets it case-insensitively as {@code asc}/{@code desc}, falling back to
 * {@code SortDirection.valueOf(...)} exactly as legacy's {@code processSortBy} does.
 *
 * @param field     never null.
 * @param direction nullable; set iff a direction token followed {@link #field()}.
 * @param position  never null.
 */
public record AstSortItem(AstToken field, @Nullable AstToken direction, AstPosition position) {

    public AstSortItem {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(position, "position");
    }
}
