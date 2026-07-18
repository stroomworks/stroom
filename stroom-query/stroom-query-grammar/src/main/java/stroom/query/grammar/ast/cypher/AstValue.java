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

/**
 * A literal value: a string, number, boolean, scalar parameter reference, or function-call literal (e.g.
 * {@code datetime('2026-07-01T09:00:00Z')}, used by the temporal clause). Unlike StroomQL's {@code AstValue}
 * (which captures raw source text for a legacy tokeniser to re-parse), Cypher has no legacy oracle to defer to,
 * so each kind is parsed into its own proper type here.
 */
public sealed interface AstValue
        permits AstStringValue, AstNumberValue, AstBooleanValue, AstParameterValue, AstFunctionValue {

    AstPosition position();
}
