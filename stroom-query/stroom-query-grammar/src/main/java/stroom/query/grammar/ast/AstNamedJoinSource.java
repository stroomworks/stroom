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

import java.util.Objects;

/**
 * An ordinary {@code join <name>} source - a bare datasource name/UUID, exactly as every join source was before
 * Workstream C (docs/graphdb-stroomql-join-implementation-plan.md, Phase P1) introduced
 * {@link AstSubQueryJoinSource}.
 *
 * @param token never null.
 */
public record AstNamedJoinSource(AstToken token) implements AstJoinSource {

    public AstNamedJoinSource {
        Objects.requireNonNull(token, "token");
    }

    @Override
    public AstPosition position() {
        return token.position();
    }
}
