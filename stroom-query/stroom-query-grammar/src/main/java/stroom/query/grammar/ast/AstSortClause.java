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

import java.util.List;
import java.util.Objects;

/**
 * {@code sort [by] field [direction], ...}. Unlike {@code group by}, legacy does not allow {@code sort by} to
 * repeat (see {@code StroomQL.g4}'s file header) - a Task 1.4 binder concern, not enforced here.
 *
 * @param items    never null; never empty.
 * @param position never null.
 */
public record AstSortClause(List<AstSortItem> items, AstPosition position) implements AstClause {

    public AstSortClause {
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(position, "position");
        items = List.copyOf(items);
    }
}
