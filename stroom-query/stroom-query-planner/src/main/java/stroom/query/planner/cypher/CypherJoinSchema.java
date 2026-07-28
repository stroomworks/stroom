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

import stroom.query.planner.logical.Limit;
import stroom.query.planner.logical.LogicalPlan;
import stroom.query.planner.logical.Project;
import stroom.query.planner.logical.ProjectField;
import stroom.query.planner.logical.Sort;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * <b>C0 contract</b>: how a Cypher sub-query used
 * as a StroomQL join side advertises its columns, frozen here before any grammar work landed, per that plan's
 * gating requirement:
 *
 * <ol>
 *     <li><b>{@code AS} aliases are mandatory.</b> A join binds {@code alias.field} against a side's declared
 *     columns; a graph side has none of its own ({@code GraphSearchProvider.getFieldInfo} is always empty), so
 *     its {@code RETURN} projection <i>is</i> the schema, and every visible item in it must carry an explicit
 *     {@code AS name} - there is no positional/auto-named fallback a join key could reasonably bind to.</li>
 *     <li><b>Type inference policy: conservative "unknown".</b> A graph property's type is whatever
 *     {@code GraphPropsCodec} decoded at ingest, not a declared column type - this v1 contract does not attempt
 *     to infer a specific {@code FieldType} from the projection shape (literal/property-access/aggregate). Every
 *     derived column is exposed with an unknown domain type (so {@code Binder.validateDomainTypeCompatibility}
 *     degrades gracefully rather than rejecting a legitimate join) and no {@code ConditionSet} restriction (so an
 *     outer {@code where}/{@code select} referencing it is never wrongly rejected for "using an unsupported
 *     condition" - the actual comparison happens generically, at execution time, over the joined row's
 *     {@code Val[]}).</li>
 *     <li><b>A {@code RETURN} with no scalar shape is rejected.</b> {@code RETURN n} (a bare pattern variable -
 *     a whole matched node/edge) has no single value to join on - {@code GraphTraversalEngine} already rejects
 *     this for an ordinary standalone query at execution time ({@code stroom-graphdb-impl}, not reachable from
 *     this module), so this contract re-applies the same rule at compile time for a join side specifically, with
 *     a clear, positioned error instead of deferring to a runtime failure deep inside the join executor.</li>
 *     <li><b>{@code DISTINCT}/aggregation are allowed</b> - the projected columns (whatever they are) remain the
 *     schema regardless of whether the graph side de-duplicates or aggregates its rows.</li>
 * </ol>
 *
 * <p><b>Worked examples</b> mapping a {@code RETURN} list to its column set: {@code return u.id as userId,
 * g.name as groupName} -&gt; {@code (userId, groupName)}; {@code return distinct h.id as hostId} -&gt;
 * {@code (hostId)}; {@code return a.number as acct, r.name as rule} -&gt; {@code (acct, rule)}. Rejected shapes:
 * {@code return n} (bare variable - no {@code AS}, and no scalar shape even if aliased, e.g.
 * {@code return n as node}); {@code return u.id} (no {@code AS} alias).</p>
 *
 * <p>Used by both {@code stroom.query.planner.bind.Binder} (Phase P2 - deriving a graph join side's schema to
 * bind {@code alias.field} join keys against) and {@code stroom.query.language.OptimisingQueryCompiler} (Phase
 * P3 - building the side's wire {@code TableSettings} columns); centralised here, in the one module both already
 * depend on, rather than duplicated (the plan's own instruction is to reuse the column extraction
 * {@code CypherCompiler.buildResultRequests} in {@code stroom-graphdb-impl} already computes - unreachable from
 * either caller module, so this is the closest shared home for the same logic).</p>
 */
public final class CypherJoinSchema {

    private CypherJoinSchema() {
        // Static utility - not instantiable.
    }

    /**
     * <b>Preconditions:</b> {@code compiledPlan} is the {@code .plan} of a
     * {@link CypherToLogicalPlan#compile(stroom.query.grammar.ast.cypher.AstCypherQuery)} result.
     * <b>Postconditions:</b> returns every visible {@link ProjectField} the compiled {@code RETURN} produces, in
     * source order - never empty (a {@code RETURN} with no visible items throws instead, see below).
     * <b>Null status:</b> the parameter must not be null; neither the return value nor its elements are nullable.
     *
     * @throws CypherCompileException per this class's Javadoc: a visible {@code RETURN} item has no {@code AS}
     *                                 alias, or names a bare pattern variable with no scalar shape.
     */
    public static List<ProjectField> deriveJoinColumns(final LogicalPlan compiledPlan) {
        Objects.requireNonNull(compiledPlan, "compiledPlan");
        final List<ProjectField> fields = terminalProjectFields(compiledPlan);
        final List<ProjectField> visible = new ArrayList<>(fields.size());
        for (final ProjectField field : fields) {
            if (!field.visible()) {
                continue;
            }
            if (field.alias() == null) {
                throw new CypherCompileException(
                        "A graph join side's RETURN items must each have an explicit AS alias (e.g. "
                        + "'return u.id as userId') so the join has a column name to bind on - '"
                        + field.rawExpression() + "' has none", field.position());
            }
            if (isBarePatternVariable(field.rawExpression())) {
                throw new CypherCompileException(
                        "RETURN item '" + field.rawExpression() + "' (aliased '" + field.alias() + "') names a "
                        + "whole matched node/edge, which has no single scalar value to join on - project one of "
                        + "its properties instead (e.g. '" + strippedVariableName(field.rawExpression()) + ".id')",
                        field.position());
            }
            visible.add(field);
        }
        if (visible.isEmpty()) {
            throw new CypherCompileException(
                    "A graph join side's RETURN must project at least one column", compiledPlan.position());
        }
        return visible;
    }

    /**
     * Walks past any {@link Limit} then {@link Sort} wrapper to the plan's terminal {@link Project} node - the
     * same unwrap {@code CypherCompiler}/{@code GraphSearchProvider} each keep a private copy of in
     * {@code stroom-graphdb-impl} (a module this one cannot depend on - see this class's Javadoc), centralised
     * here for the two callers that live in a module both of them already depend on.
     *
     * <b>Preconditions:</b> {@code plan} must not be null.
     * <b>Postconditions:</b> never returns null; never empty (every compiled Cypher {@code RETURN} produces at
     * least one {@link ProjectField} - the grammar requires it).
     *
     * @throws IllegalArgumentException if the plan's terminal node (after unwrapping) isn't a {@link Project} -
     *                                   every compiled Cypher plan has one (see {@code CypherToLogicalPlan}'s
     *                                   Javadoc), so this indicates a plan from somewhere else entirely.
     */
    public static List<ProjectField> terminalProjectFields(final LogicalPlan plan) {
        Objects.requireNonNull(plan, "plan");
        LogicalPlan current = plan;
        while (current instanceof final Limit limit) {
            current = limit.input();
        }
        while (current instanceof final Sort sort) {
            current = sort.input();
        }
        if (!(current instanceof final Project project)) {
            throw new IllegalArgumentException(
                    "Unsupported compiled plan shape for a graph join side: expected a Project node (after "
                    + "unwrapping Limit/Sort), found " + current.getClass().getSimpleName());
        }
        return project.fields();
    }

    /**
     * @return true if {@code rawExpression} is exactly a bare {@code ${name}} pattern-variable reference (no
     *         {@code .property} access) - {@code CypherToLogicalPlan}'s rendering for a bare pattern-variable
     *         {@code RETURN} item (e.g. {@code "${n}"} for {@code RETURN n}). A property access
     *         ({@code "${a.id}"}), a literal, or an aggregate ({@code "count(${n})"}) are all scalar and never
     *         match this.
     */
    private static boolean isBarePatternVariable(final String rawExpression) {
        if (!rawExpression.startsWith("${") || !rawExpression.endsWith("}")) {
            return false;
        }
        final String inner = rawExpression.substring(2, rawExpression.length() - 1);
        return !inner.isEmpty() && inner.indexOf('.') < 0;
    }

    private static String strippedVariableName(final String rawExpression) {
        return rawExpression.substring(2, rawExpression.length() - 1);
    }
}
