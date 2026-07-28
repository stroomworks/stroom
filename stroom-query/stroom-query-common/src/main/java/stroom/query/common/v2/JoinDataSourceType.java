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

package stroom.query.common.v2;

/**
 * The sentinel {@code DocRef} type a join query's outer {@code Query.dataSource} uses to route execution to the
 * join-handling {@code SearchProvider}, Task 6.1a.
 * {@link SearchProviderRegistry} resolves providers purely by {@code DocRef.getType} (verified:
 * {@code SearchProviderRegistryImpl}), so this constant is the entire routing contract between the compiler
 * (which builds a join's outer {@code SearchRequest}) and the provider (which reads it back).
 */
public final class JoinDataSourceType {

    public static final String TYPE = "StroomQLJoin";

    private JoinDataSourceType() {
    }
}
