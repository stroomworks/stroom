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

import stroom.docref.DocRef;
import stroom.graphdb.shared.GraphDbDoc;
import stroom.query.api.SearchRequest;
import stroom.query.language.AlternativeQueryCompiler;
import stroom.query.language.functions.ExpressionContext;

import java.util.Objects;

/**
 * Task P6.1: the {@link AlternativeQueryCompiler} adapter that finally gives {@link CypherCompiler} a real,
 * live caller - previously it was invoked only from test setup, never from any production request path.
 */
public class GraphCypherQueryCompiler implements AlternativeQueryCompiler {

    private final CypherCompiler cypherCompiler = new CypherCompiler();

    @Override
    public boolean supports(final DocRef dataSourceRef) {
        Objects.requireNonNull(dataSourceRef, "dataSourceRef");
        return GraphDbDoc.TYPE.equals(dataSourceRef.getType());
    }

    @Override
    public SearchRequest create(final String query, final SearchRequest in,
                                final ExpressionContext expressionContext) {
        return cypherCompiler.create(query, in, expressionContext);
    }
}
