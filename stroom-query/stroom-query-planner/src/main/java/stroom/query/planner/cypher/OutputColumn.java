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

package stroom.query.planner.cypher;

/**
 * One column of a {@code RETURN} clause that mixes an aggregate with other items - either a {@link GroupKeyColumn}
 * (an implicit {@code GROUP BY} key, Cypher's rule that every non-aggregate item becomes one) or an
 * {@link AggregateColumn} (a {@code count}/{@code sum}/{@code avg}/{@code min}/{@code max} call). See
 * {@link CypherAggregation}'s Javadoc for how a list of these aligns with a compiled {@code Project}'s fields.
 */
public sealed interface OutputColumn permits GroupKeyColumn, AggregateColumn {
}
