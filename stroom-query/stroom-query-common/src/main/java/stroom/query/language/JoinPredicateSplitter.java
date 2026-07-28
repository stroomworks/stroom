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

import stroom.query.api.ExpressionItem;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionOperator.Op;
import stroom.query.api.ExpressionTerm;
import stroom.query.api.JoinSpec;
import stroom.query.api.datasource.QueryField;
import stroom.query.planner.port.FieldInfoSource;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Splits a join's outer {@code where} clause into the part(s) safe to pre-filter each side with, before the join
 * runs, and the residual that must still be evaluated on the combined row - see
 * decision D3 (Phase 1, item A1).
 *
 * <p>Deliberately conservative and deliberately independent of {@code PushFiltersBelowJoinsRule} (the existing
 * rewrite-pipeline rule that pushes a filter below a {@code Join} for {@code EXPLAIN}'s cost estimates): that rule
 * classifies a whole predicate slot at once and does not know about {@code JoinType} at all, so reusing its output
 * here would silently mis-execute a {@code LEFT} join (pushing a predicate onto the null-supplying side changes
 * which rows survive - see this class's {@link #split} Javadoc). This class instead walks the top-level
 * conjuncts of an {@code AND} one at a time, decides each independently, and is the only place that decision is
 * made for real per-side execution (as opposed to a cost estimate).</p>
 *
 * <p>Only a bare, enabled {@link ExpressionTerm} conjunct is ever considered for push - a nested
 * {@link ExpressionOperator} (a parenthesised {@code AND}/{@code OR}/{@code NOT}) always stays in the residual,
 * matching {@code AutoWhereFilterSplitRule}'s own conservative convention: a case this class can't resolve with
 * full confidence is left exactly where it was, never guessed at.</p>
 */
@NullMarked
final class JoinPredicateSplitter {

    private final FieldInfoSource fieldInfoSource;

    /**
     * @param fieldInfoSource must not be null; used to look up each candidate field's {@link QueryField#queryable}
     *                        and {@link QueryField#getConditionSet} to decide index-eligibility (decision D2).
     */
    JoinPredicateSplitter(final FieldInfoSource fieldInfoSource) {
        this.fieldInfoSource = Objects.requireNonNull(fieldInfoSource, "fieldInfoSource");
    }

    /**
     * The three-way split of a join's {@code where} clause.
     *
     * @param leftPush  the conjuncts safe to pre-filter the left side with (alias already stripped from each
     *                  term's field, e.g. {@code "a.StreamId"} becomes {@code "StreamId"}), or null if none
     *                  qualify.
     * @param rightPush same as {@code leftPush}, for the right side; always null when {@code joinType} is
     *                  {@link JoinSpec.JoinType#LEFT} (see {@link #split}'s Javadoc on why).
     * @param residual  never null; every conjunct not pushed to either side, still alias-qualified exactly as
     *                  written - this is what must still be evaluated on the combined row (see
     *                  {@code JoinSearchProvider.whereRowPredicate}). Identical to the original {@code where}
     *                  (same object) when nothing was pushed, so a no-op split changes nothing downstream.
     */
    record Split(@Nullable ExpressionOperator leftPush, @Nullable ExpressionOperator rightPush,
                ExpressionOperator residual) {
    }

    /**
     * Splits {@code where} into per-side pushable predicates and a residual, given each side's alias, datasource
     * name, and the join's type.
     *
     * <p><b>Preconditions:</b> {@code where}, {@code leftAlias}, {@code leftDataSourceName}, {@code rightAlias},
     * {@code rightDataSourceName}, {@code joinType} must all be non-null.<br>
     * <b>Postconditions:</b> never returns null. A conjunct appears in exactly one of {@code leftPush}/
     * {@code rightPush}/{@code residual} - never duplicated, never dropped. A disabled conjunct, an unqualified
     * conjunct, one qualified by neither alias, or one referencing a field this method cannot confirm is
     * index-eligible for its side (see {@link #isIndexEligible}) always ends up in {@code residual}
     * (decision D2). If {@code where}'s top-level operator is not {@code AND} (including a bare {@code OR}/
     * {@code NOT}), or it has no children, nothing is decomposed and the whole of {@code where} is the residual.</p>
     *
     * <p><b>Why {@code JoinType.LEFT} never pushes to the right side:</b> the right side of a {@code LEFT} join is
     * the side whose columns are null-padded for an unmatched left row. Pre-filtering it before the join removes
     * candidate matches from consideration entirely, which is fine for an {@code INNER} join (a removed candidate
     * was never going to produce a row anyway) but wrong for {@code LEFT}: the unmatched-left-row-survives
     * behaviour must still apply against the *unfiltered* right side, so a right-side predicate is only safe to
     * apply after the join (residual), where it can be evaluated against the null-padded columns without having
     * pre-excluded any candidate right row from ever being tried as a match.</p>
     */
    Split split(final ExpressionOperator where,
               final String leftAlias, final String leftDataSourceName,
               final String rightAlias, final String rightDataSourceName,
               final JoinSpec.JoinType joinType) {
        Objects.requireNonNull(where, "where");
        Objects.requireNonNull(leftAlias, "leftAlias");
        Objects.requireNonNull(leftDataSourceName, "leftDataSourceName");
        Objects.requireNonNull(rightAlias, "rightAlias");
        Objects.requireNonNull(rightDataSourceName, "rightDataSourceName");
        Objects.requireNonNull(joinType, "joinType");

        final Op op = where.getOp() == null ? Op.AND : where.getOp();
        final List<ExpressionItem> children = where.getChildren();
        if (op != Op.AND || children == null || children.isEmpty()) {
            return new Split(null, null, where);
        }

        final List<ExpressionTerm> leftTerms = new ArrayList<>();
        final List<ExpressionTerm> rightTerms = new ArrayList<>();
        final List<ExpressionItem> residualItems = new ArrayList<>();

        for (final ExpressionItem child : children) {
            if (!child.enabled() || !(child instanceof final ExpressionTerm term)) {
                residualItems.add(child);
                continue;
            }
            final String alias = aliasOf(term.getField());
            if (alias == null) {
                residualItems.add(child);
            } else if (alias.equals(leftAlias) && isIndexEligible(term, leftDataSourceName, leftAlias)) {
                leftTerms.add(stripAlias(term, leftAlias));
            } else if (alias.equals(rightAlias) && joinType != JoinSpec.JoinType.LEFT
                       && isIndexEligible(term, rightDataSourceName, rightAlias)) {
                rightTerms.add(stripAlias(term, rightAlias));
            } else {
                residualItems.add(child);
            }
        }

        if (leftTerms.isEmpty() && rightTerms.isEmpty()) {
            return new Split(null, null, where);
        }
        final ExpressionOperator leftPush = leftTerms.isEmpty() ? null : asAnd(leftTerms);
        final ExpressionOperator rightPush = rightTerms.isEmpty() ? null : asAnd(rightTerms);
        final ExpressionOperator residual = where.copy().children(residualItems).build();
        return new Split(leftPush, rightPush, residual);
    }

    /**
     * @param rawField an {@link ExpressionTerm#getField} value, e.g. {@code "a.StreamId"} or {@code "StreamId"}.
     * @return the alias prefix (text before the first {@code '.'}), or null if {@code rawField} has none.
     */
    private static @Nullable String aliasOf(final String rawField) {
        final int dot = rawField.indexOf('.');
        return dot < 0 ? null : rawField.substring(0, dot);
    }

    /**
     * A term is index-eligible when its (alias-qualified) field resolves to a {@link QueryField} on
     * {@code dataSourceName} that is {@link QueryField#queryable} and whose {@link QueryField#getConditionSet}
     * supports the term's {@link ExpressionTerm#getCondition} - mirroring {@code AutoWhereFilterSplitRule}'s own
     * eligibility test. An unknown field, or one with no condition set, is never eligible (decision D2's
     * conservative default) - pushing an unsupported field/condition into a side's own sub-query can otherwise
     * silently zero that side's results (an ANDed {@code MatchNoDocsQuery} at the datasource).
     */
    private boolean isIndexEligible(final ExpressionTerm term, final String dataSourceName, final String alias) {
        final String bareFieldName = term.getField().substring(alias.length() + 1);
        final Optional<QueryField> field = fieldInfoSource.getFields(dataSourceName).stream()
                .filter(candidate -> candidate.getFldName().equals(bareFieldName))
                .findFirst();
        return field.isPresent()
               && field.get().queryable()
               && field.get().getConditionSet() != null
               && field.get().getConditionSet().supportsCondition(term.getCondition());
    }

    /** Rebuilds {@code term} with the {@code alias.} prefix removed from its field, e.g. {@code "a.StreamId"} (with
     * {@code alias == "a"}) becomes {@code "StreamId"} - the side's own sub-query knows the field only by its bare
     * name, never by the outer join's alias. */
    private static ExpressionTerm stripAlias(final ExpressionTerm term, final String alias) {
        return term.copy().field(term.getField().substring(alias.length() + 1)).build();
    }

    private static ExpressionOperator asAnd(final List<ExpressionTerm> terms) {
        return ExpressionOperator.builder().op(Op.AND).addTerms(terms).build();
    }
}
