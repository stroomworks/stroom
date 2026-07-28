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

package stroom.query.impl;

import stroom.docref.DocRef;
import stroom.query.api.QueryNodeResolver;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the composition rule: the first resolver that claims a datasource decides where the query runs.
 *
 * <p>The empty case is the one worth pinning down, because the multibinder is declared in an always-installed module
 * and is legitimately empty in a deployment with no node-pinned features - it must mean "no constraint", not fail.</p>
 */
class TestCompositeQueryNodeResolver {

    private static final DocRef DOC_REF = DocRef.builder().type("Anything").uuid("u").name("n").build();

    @Test
    void getNode_withNoResolvers_imposesNoConstraint() {
        assertThat(new CompositeQueryNodeResolver(Set.of()).getNode(DOC_REF)).isNull();
    }

    @Test
    void getNode_withNoResolverClaimingTheDatasource_imposesNoConstraint() {
        final CompositeQueryNodeResolver composite =
                new CompositeQueryNodeResolver(ordered(docRef -> null, docRef -> null));

        assertThat(composite.getNode(DOC_REF)).isNull();
    }

    @Test
    void getNode_returnsTheFirstNonNullAnswer_andDoesNotConsultLaterResolvers() {
        final CompositeQueryNodeResolver composite = new CompositeQueryNodeResolver(ordered(
                docRef -> null,
                docRef -> "nodeA",
                docRef -> "nodeB"));

        assertThat(composite.getNode(DOC_REF)).isEqualTo("nodeA");
    }

    /** Insertion-ordered so "first non-null" is a meaningful assertion rather than a hash-order accident. */
    private static Set<QueryNodeResolver> ordered(final QueryNodeResolver... resolvers) {
        return new LinkedHashSet<>(Arrays.asList(resolvers));
    }
}
