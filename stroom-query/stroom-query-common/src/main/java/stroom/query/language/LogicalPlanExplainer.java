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

package stroom.query.language;

import stroom.query.api.ExplainPlan;
import stroom.query.api.ExpressionTerm;
import stroom.query.language.functions.ExpressionContext;
import stroom.query.planner.cost.CostModel;
import stroom.query.planner.cost.CostedAccessPath;
import stroom.query.planner.cost.JoinCostModel;
import stroom.query.planner.cost.JoinPlan;
import stroom.query.planner.cost.StateLookup;
import stroom.query.planner.logical.Aggregate;
import stroom.query.planner.logical.Expand;
import stroom.query.planner.logical.Filter;
import stroom.query.planner.logical.GraphJoinSource;
import stroom.query.planner.logical.Having;
import stroom.query.planner.logical.Join;
import stroom.query.planner.logical.Limit;
import stroom.query.planner.logical.LogicalPlan;
import stroom.query.planner.logical.NodeScan;
import stroom.query.planner.logical.Project;
import stroom.query.planner.logical.QualifiedField;
import stroom.query.planner.logical.Scan;
import stroom.query.planner.logical.Sort;
import stroom.query.planner.logical.VarLengthExpand;
import stroom.query.planner.logical.Window;
import stroom.query.planner.port.FieldInfoSource;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Turns a bound, rewritten {@link LogicalPlan} into an {@link ExplainPlan} wire-tree, costing each {@code Scan}
 * via {@link CostModel}, Task 4.1. Package-private:
 * an implementation detail of {@link OptimisingQueryCompiler#explain}, not part of this package's public API.
 *
 * <p><b>Scope note</b>: extracting a time range and selectivity-relevant terms for a {@code Scan}'s cost is
 * only done for the common shape - a {@link Filter} directly above that {@code Scan}. A deeper/more complex
 * shape (a {@code Filter} above something other than a bare {@code Scan}) still produces a correct plan
 * <i>tree</i>, just without that extra refinement on the affected {@code Scan}'s cost - a conservative
 * simplification (an under-refined but not wrong estimate), not a defect. Likewise, join cost annotation
 * ({@link JoinCostModel}) is only attempted when both sides of a {@code Join} are themselves direct
 * {@code Scan}s; a nested join still produces a correct tree, just without its own cardinality/algorithm note.</p>
 */
final class LogicalPlanExplainer {

    private final CostModel costModel;
    private final FieldInfoSource fieldInfoSource;
    private final ExpressionContext expressionContext;

    LogicalPlanExplainer(
            final CostModel costModel, final FieldInfoSource fieldInfoSource,
            final ExpressionContext expressionContext) {
        this.costModel = Objects.requireNonNull(costModel, "costModel");
        this.fieldInfoSource = Objects.requireNonNull(fieldInfoSource, "fieldInfoSource");
        this.expressionContext = Objects.requireNonNull(expressionContext, "expressionContext");
    }

    ExplainPlan explain(final LogicalPlan plan) {
        Objects.requireNonNull(plan, "plan");
        return toNode(plan).explainPlan();
    }

    /** A node's wire-ready {@link ExplainPlan}, plus (for a {@code Scan} only) the internal cost result a
     *  parent {@code Join} needs to choose its algorithm - not itself part of the wire shape. */
    private record Node(ExplainPlan explainPlan, @Nullable CostedAccessPath costedAccessPath) {
    }

    private Node toNode(final LogicalPlan plan) {
        return switch (plan) {
            case final Scan scan -> costScan(scan, null, null, List.of());
            case final Filter f -> explainFilter(f);
            case final Project p -> wrap("Project", toNode(p.input()));
            case final Join j -> explainJoin(j);
            case final Aggregate a -> wrap(
                    "Group by " + a.groupFields().stream().map(this::describeField)
                            .collect(Collectors.joining(", ")),
                    toNode(a.input()));
            case final Having h -> wrap("Having", toNode(h.input()));
            case final Window w -> wrap("Window by " + describeField(w.field()), toNode(w.input()));
            case final Sort s -> wrap("Sort", toNode(s.input()));
            case final Limit l -> wrap(describeLimit(l), toNode(l.input()));
            case final NodeScan ns -> new Node(
                    ExplainPlan.builder()
                            .description("NodeScan " + ns.variable()
                                    + (ns.labels().isEmpty() ? "" : ":" + String.join(":", ns.labels())))
                            .build(),
                    null);
            case final Expand e -> wrap(
                    "Expand " + e.direction() + " " + e.edgeType() + " as " + e.targetVariable(),
                    toNode(e.input()));
            case final VarLengthExpand vle -> wrap(
                    "VarLengthExpand " + vle.direction() + " " + vle.edgeType()
                            + "*" + vle.minHops() + ".." + vle.maxHops() + " as " + vle.targetVariable(),
                    toNode(vle.input()));
            // A graph join side (Phase P1/P2): no compile-time
            // cost estimate yet (Phase P5, deferrable - the graph engine's stats port isn't wired into CostModel
            // for a join side) - explainJoin's null-costedAccessPath "nested join" branch already handles that
            // gracefully.
            case final GraphJoinSource g -> new Node(
                    ExplainPlan.builder()
                            .description("GraphJoinSource " + g.alias() + " (Cypher sub-query)")
                            .build(),
                    null);
        };
    }

    private Node wrap(final String description, final Node child) {
        return new Node(
                ExplainPlan.builder().description(description).children(List.of(child.explainPlan())).build(),
                null);
    }

    private Node costScan(
            final Scan scan, final @Nullable Long fromTimeMs, final @Nullable Long toTimeMs,
            final List<ExpressionTerm> selectivityTerms) {
        final CostedAccessPath costed = costModel.estimate(scan, fromTimeMs, toTimeMs, selectivityTerms);
        final ExplainPlan explainPlan = ExplainPlan.builder()
                .description("Scan " + scan.dataSourceName() + " as " + scan.alias()
                             + " (" + costed.accessPath().getClass().getSimpleName() + ")")
                .estimatedRows(costed.estimate().rows())
                .estimatedDurationMs(costed.estimate().durationMs())
                .confidence(costed.estimate().confidence())
                .notes(costed.estimate().notes())
                .build();
        return new Node(explainPlan, costed);
    }

    private Node explainFilter(final Filter filter) {
        if (filter.input() instanceof final Scan scan) {
            final ScanTimeBounds bounds = ScanTimeRangeExtractor.extract(
                    scan, filter, fieldInfoSource, expressionContext);
            final Node scanNode = costScan(scan, bounds.fromTimeMs(), bounds.toTimeMs(), bounds.selectivityTerms());
            // Propagate the inner Scan's costedAccessPath through this Filter-over-Scan wrapper (unlike the
            // general wrap below) so a parent Join sees a costed side rather than treating Join(Filter(Scan),
            // ...) - the shape produced for essentially every filtered join - as an un-annotated "nested join"
            // (see the class Javadoc's scope note and finding F10).
            return new Node(
                    ExplainPlan.builder().description("Filter").children(List.of(scanNode.explainPlan())).build(),
                    scanNode.costedAccessPath());
        }
        return wrap("Filter", toNode(filter.input()));
    }

    private Node explainJoin(final Join join) {
        final Node left = toNode(join.left());
        final Node right = toNode(join.right());
        final List<ExplainPlan> children = List.of(left.explainPlan(), right.explainPlan());

        if (left.costedAccessPath() == null || right.costedAccessPath() == null) {
            // A nested join (a Join whose own side is itself a Join/Filter/...) - still a correct tree, just
            // without a cardinality/algorithm note on this level (Task 6.3 annotation of the nested case).
            return new Node(ExplainPlan.builder()
                    .description("Join")
                    .children(children)
                    .notes(List.of("nested join - at least one side is itself a Join/Filter/... rather than a "
                                   + "direct Scan, so it has no compile-time cost estimate to combine; no "
                                   + "cardinality/algorithm annotation on this level"))
                    .build(), null);
        }

        final CostedAccessPath leftPath = left.costedAccessPath();
        final CostedAccessPath rightPath = right.costedAccessPath();
        final JoinPlan joinPlan = JoinCostModel.chooseAlgorithm(leftPath, rightPath);

        // Task 6.3: a StateLookup side is a keyed point-lookup, so its join key is unique by construction - use
        // its row count as its distinct-key count (an honest, structural signal, not an invented number). Any
        // other access path has no distinct-key estimate available at compile time, so it stays 0 (the "unknown"
        // that estimateCardinality degrades to the pessimistic cross-product upper bound). This makes the common
        // enrichment join (index/searchable ⋈ keyed state lookup) estimate ~= the probe side's row count rather
        // than the full cross-product. Real per-field distinct-key stats for two non-keyed sides would need a new
        // cost port and are out of scope here.
        final long leftDistinct = leftPath.accessPath() instanceof StateLookup ? leftPath.estimate().rows() : 0;
        final long rightDistinct = rightPath.accessPath() instanceof StateLookup ? rightPath.estimate().rows() : 0;
        final long cardinality = JoinCostModel.estimateCardinality(
                leftPath.estimate().rows(), rightPath.estimate().rows(), leftDistinct, rightDistinct);
        final String note = (leftDistinct > 0 || rightDistinct > 0)
                ? "cardinality uses the keyed (State lookup) side's unique-key count; the other side's distinct "
                  + "keys are unknown"
                : "distinct-key counts unknown - cardinality is the pessimistic upper bound (full cross-product)";
        final ExplainPlan explainPlan = ExplainPlan.builder()
                .description("Join (" + joinPlan.algorithm() + ", build side: " + joinPlan.buildSide() + ")")
                .children(children)
                .estimatedRows(cardinality)
                .notes(List.of(note))
                .build();
        return new Node(explainPlan, null);
        // Deliberately not propagating a CostedAccessPath upward for the join itself yet - no AccessPath
        // variant represents "the result of a join", and nothing needs a join-of-joins estimate in this pass
        // (see class Javadoc's nested-join scope note).
    }

    private String describeField(final QualifiedField field) {
        return field.alias() == null ? field.field() : field.alias() + "." + field.field();
    }

    /**
     * Describes a row window: {@code "Limit [10]"}, {@code "Limit [10] offset 5"}, or {@code "Limit offset 5"} for
     * a {@code SKIP} with no {@code LIMIT}.
     *
     * <p>The offset is only shown when it is set, so the overwhelmingly common no-offset plan explains exactly as
     * it did before. An explain that silently omitted a non-zero offset would be worse than one that never
     * mentioned offsets at all: it would show a plan returning rows it does not return.</p>
     */
    private String describeLimit(final Limit limit) {
        final StringBuilder description = new StringBuilder("Limit");
        if (!limit.values().isEmpty()) {
            description.append(' ').append(limit.values());
        }
        if (limit.offset() > 0) {
            description.append(" offset ").append(limit.offset());
        }
        return description.toString();
    }
}
