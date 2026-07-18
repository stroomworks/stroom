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

package stroom.query.planner.logical;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * A field reference optionally qualified by a {@link Scan#alias()} - the bound form of a plain field name or an
 * {@code alias.field} reference (see {@code StroomQL.g4}'s file header on why {@code alias.field} is one
 * bareword token at the grammar level, split here rather than in the lexer).
 *
 * @param alias the {@link Scan} alias this field belongs to, or null when the query is single-source and the
 *              reference was unqualified (nothing to disambiguate).
 * @param field never null; the field name.
 */
public record QualifiedField(@Nullable String alias, String field) {

    public QualifiedField {
        Objects.requireNonNull(field, "field");
    }
}
