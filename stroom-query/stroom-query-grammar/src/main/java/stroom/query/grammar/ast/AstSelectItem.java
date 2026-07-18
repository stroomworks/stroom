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

/**
 * One item in a {@code select} list: {@code *}, a function call, a plain field, or a {@code ${param}} reference,
 * each optionally aliased with {@code as}.
 */
public sealed interface AstSelectItem permits AstSelectStar, AstSelectFunction, AstSelectField, AstSelectParam {

    /** @return nullable; set iff {@code as <alias>} was present. */
    @Nullable AstToken alias();

    AstPosition position();
}
