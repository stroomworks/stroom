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

package stroom.query.planner.rewrite;

import stroom.query.api.ExpressionOperator;
import stroom.query.planner.logical.Aggregate;
import stroom.query.planner.logical.Expand;
import stroom.query.planner.logical.Filter;
import stroom.query.planner.logical.Having;
import stroom.query.planner.logical.Join;
import stroom.query.planner.logical.Limit;
import stroom.query.planner.logical.LogicalPlan;
import stroom.query.planner.logical.NodeScan;
import stroom.query.planner.logical.Project;
import stroom.query.planner.logical.Scan;
import stroom.query.planner.logical.Sort;
import stroom.query.planner.logical.VarLengthExpand;
import stroom.query.planner.logical.Window;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Shared tree-walk helpers for rewrite rules - kept package-private since these are implementation details of
 * the rules themselves, not part of the module's public rewrite API ({@link RewriteRule}/{@link RewritePipeline}).
 */
final class PlanRewriteUtil {

    private PlanRewriteUtil() {
        // Static utility - not instantiable.
    }

    /**
     * Rebuilds {@code plan}, applying {@code transform} to every {@link Filter#wherePredicate()}/
     * {@link Filter#filterPredicate()} and {@link Having#predicate()} found anywhere in the tree. Used by rules
     * that simplify predicate expressions in place ({@code ConstantFoldingRule},
     * {@code RedundantTermPruningRule}) without needing to know anything about the surrounding plan shape.
     *
     * @param plan      never null.
     * @param transform never null; applied only to non-null predicates (a {@code null} slot stays {@code null}).
     * @return never null.
     */
    static LogicalPlan mapPredicates(final LogicalPlan plan, final UnaryOperator<ExpressionOperator> transform) {
        return switch (plan) {
            case final Scan scan -> scan;
            case final Filter f -> new Filter(
                    mapPredicates(f.input(), transform),
                    f.wherePredicate() == null ? null : transform.apply(f.wherePredicate()),
                    f.filterPredicate() == null ? null : transform.apply(f.filterPredicate()),
                    f.position());
            case final Project p -> new Project(mapPredicates(p.input(), transform), p.fields(), p.position());
            case final Join j -> new Join(
                    mapPredicates(j.left(), transform),
                    mapPredicates(j.right(), transform),
                    j.joinType(), j.equiKeys(), j.position());
            case final Aggregate a -> new Aggregate(
                    mapPredicates(a.input(), transform), a.groupFields(), a.position());
            case final Having h -> new Having(
                    mapPredicates(h.input(), transform), transform.apply(h.predicate()), h.position());
            case final Window w -> new Window(
                    mapPredicates(w.input(), transform), w.field(), w.windowSize(), w.advanceSize(),
                    w.usingFunction(), w.position());
            case final Sort s -> new Sort(mapPredicates(s.input(), transform), s.keys(), s.position());
            case final Limit l -> new Limit(mapPredicates(l.input(), transform), l.values(), l.position());
            // Graph nodes (Task PoC.2/P3.1): a NodeScan's property anchor, and an Expand/VarLengthExpand's
            // target property predicate, ARE predicates in this sense, so transform them like any other
            // optional predicate slot.
            case final NodeScan ns -> new NodeScan(
                    ns.variable(), ns.labels(),
                    ns.propertyAnchor() == null ? null : transform.apply(ns.propertyAnchor()),
                    ns.position());
            case final Expand e -> new Expand(
                    mapPredicates(e.input(), transform), e.edgeType(), e.direction(), e.targetVariable(),
                    e.targetLabels(),
                    e.targetPropertyPredicate() == null ? null : transform.apply(e.targetPropertyPredicate()),
                    e.position());
            case final VarLengthExpand vle -> new VarLengthExpand(
                    mapPredicates(vle.input(), transform), vle.edgeType(), vle.direction(),
                    vle.minHops(), vle.maxHops(), vle.targetVariable(), vle.targetLabels(),
                    vle.targetPropertyPredicate() == null ? null : transform.apply(vle.targetPropertyPredicate()),
                    vle.position());
        };
    }

    /**
     * @param plan never null.
     * @return never null; every {@link Scan} reachable beneath {@code plan}, keyed by alias, in left-to-right
     *         source order. Used by rules that need to know which datasource a predicate's field belongs to
     *         ({@code AutoWhereFilterSplitRule}, {@code PushFiltersBelowJoinsRule}).
     */
    static Map<String, Scan> collectScans(final LogicalPlan plan) {
        return switch (plan) {
            case final Scan scan -> {
                final Map<String, Scan> map = new LinkedHashMap<>();
                map.put(scan.alias(), scan);
                yield map;
            }
            case final Join j -> {
                final Map<String, Scan> map = new LinkedHashMap<>(collectScans(j.left()));
                map.putAll(collectScans(j.right()));
                yield map;
            }
            case final Filter f -> collectScans(f.input());
            case final Project p -> collectScans(p.input());
            case final Aggregate a -> collectScans(a.input());
            case final Having h -> collectScans(h.input());
            case final Window w -> collectScans(w.input());
            case final Sort s -> collectScans(s.input());
            case final Limit l -> collectScans(l.input());
            // Graph nodes (Task PoC.2): a NodeScan is not a relational Scan (no alias.field join model applies
            // to graph pattern variables), so it contributes no entries; Expand/VarLengthExpand recurse through.
            case final NodeScan ns -> Map.of();
            case final Expand e -> collectScans(e.input());
            case final VarLengthExpand vle -> collectScans(vle.input());
        };
    }

    /**
     * @param rawField an {@link stroom.query.api.ExpressionTerm#getField()} value - either a plain field name
     *                 or a {@code alias.field}-qualified one (see {@code Binder.qualifiedName}, which is what
     *                 produces this string in the first place).
     * @return the alias prefix, or null if {@code rawField} has none (a plain, unqualified field name).
     */
    static String aliasOf(final String rawField) {
        final int dot = rawField.indexOf('.');
        return dot < 0 ? null : rawField.substring(0, dot);
    }
}
