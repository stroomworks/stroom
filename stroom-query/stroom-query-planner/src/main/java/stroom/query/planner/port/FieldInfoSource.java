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

package stroom.query.planner.port;

import stroom.query.api.datasource.QueryField;

import java.util.List;
import java.util.Optional;

/**
 * The field-metadata port the {@code Binder} (Task 2.2) needs from the host application - wraps
 * {@code DataSourceProviderRegistry.getFieldInfo}/the resolved provider's {@code getTimeField}
 * (see {@code stroom-query-common/.../v2/DataSourceProviderRegistry.java} and
 * {@code stroom-query-api/.../datasource/DataSourceProvider.java}), whose real implementation lives in
 * {@code stroom-query-common} (this module must not depend on it - see the port/adapter split in the design
 * doc's Task 2.2).
 *
 * <p>Deliberately keyed by the datasource's raw <b>name</b> as written in the query (what
 * {@code AstFrom.source()}/{@code AstJoin.source()} already carry), not a resolved {@code DocRef}: name/UUID
 * lookup and permission checks belong to {@code DataSourceResolver} in {@code stroom-query-common}, which the
 * adapter calls internally so the binder itself never needs to know about {@code DocRef}s.</p>
 */
public interface FieldInfoSource {

    /**
     * @param dataSourceName never null; the datasource name/UUID as written in a {@code from}/{@code join}
     *                       clause.
     * @return never null; every field the named datasource exposes, or empty if the name doesn't resolve to a
     *         known datasource (the binder turns that into a {@code BindException} at the {@code from}/{@code
     *         join} site, not here).
     */
    List<QueryField> getFields(String dataSourceName);

    /**
     * @param dataSourceName never null.
     * @return the named datasource's time field, or empty if it has none or the name doesn't resolve.
     */
    Optional<QueryField> getTimeField(String dataSourceName);
}
