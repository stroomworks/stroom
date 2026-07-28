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

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.Objects;
import java.util.Set;

/**
 * Asks each registered {@link QueryNodeResolver} in turn which node a query should run on, and takes the first
 * answer.
 *
 * <p>This exists so more than one feature can pin its queries to particular nodes. The single-binding arrangement
 * it replaces meant that whichever feature owned the binding was the only one that could route, and a second
 * feature's stores were silently queried locally - which for a store that only exists on some nodes returns an
 * incomplete answer with nothing to indicate it.</p>
 *
 * <p>A resolver returns null for document types it does not own, so the iteration order among resolvers that could
 * each answer is irrelevant in practice: exactly one recognises any given {@link DocRef} type.</p>
 */
@Singleton
public class CompositeQueryNodeResolver implements QueryNodeResolver {

    private final Set<QueryNodeResolver> resolvers;

    @Inject
    public CompositeQueryNodeResolver(final Set<QueryNodeResolver> resolvers) {
        this.resolvers = Objects.requireNonNull(resolvers, "resolvers must not be null");
    }

    /**
     * <p><b>Postconditions:</b> returns the node the query must run on, or null if no resolver claims
     * {@code docRef} - meaning the query may run locally.
     * <b>Null status:</b> {@code docRef} is nullable; the return value is nullable.
     *
     * @param docRef the datasource being queried.
     * @return the node name to run on, or null for no constraint.
     */
    @Override
    public String getNode(final DocRef docRef) {
        for (final QueryNodeResolver resolver : resolvers) {
            final String node = resolver.getNode(docRef);
            if (node != null) {
                return node;
            }
        }
        return null;
    }
}
