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

import stroom.query.api.Column;
import stroom.query.api.ExpressionItem;
import stroom.query.api.ExpressionOperator;
import stroom.query.api.ExpressionTerm;
import stroom.query.api.ResultRequest;
import stroom.query.api.SearchRequest;
import stroom.query.api.TableSettings;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds every field one side of a join needs fetched, so {@link OptimisingQueryCompiler#compileJoinSide} can
 * select exactly those columns instead of {@code select *} - see
 * decision D4 (Phase 1, item A2).
 *
 * <p><b>How field references are found</b>: {@link ExpressionOperator}/{@link ExpressionTerm} trees (the residual
 * {@code where}, and a compiled {@link TableSettings}'s {@code valueFilter}/{@code aggregateFilter}) are walked
 * structurally via {@link ExpressionTerm#getField} - each term names exactly one field, so this part is exact.
 * A {@link Column#getExpression}, however, is an arbitrary StroomQL expression - e.g. {@code "a.StreamId"},
 * {@code "sum(a.Amount)"}, or {@code "concat(a.Name, b.Name)"} - not just a single field, so this class instead
 * scans that expression text for every {@code alias.field}-shaped token with a regex. This is deliberately
 * conservative in the safe direction: it can only ever <i>over</i>-match (treat something that merely looks like
 * {@code alias.field} as a reference), never miss a real one buried inside a function call - and retaining one
 * extra column is harmless, whereas silently dropping one a complex expression actually needed would produce
 * wrong results. A full StroomQL expression parse would be exact instead of conservative, but is not attempted
 * here.</p>
 */
@NullMarked
final class JoinProjectionAnalyzer {

    /** Matches an {@code alias.field}-shaped token: a bareword, a dot, then another bareword. */
    private static final Pattern ALIAS_FIELD_TOKEN =
            Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)");

    private JoinProjectionAnalyzer() {
        // Static utility - not instantiable.
    }

    /**
     * The fields one side of a join needs, expressed as their bare (alias-stripped) names, so
     * {@link OptimisingQueryCompiler#compileJoinSide} can build an explicit {@code select} list.
     *
     * <p><b>Preconditions:</b> {@code outer} and {@code alias} must not be null; {@code equiKeyFields} must not be
     * null (may be empty, though a join always has at least one equi-key in practice); {@code residualWhere} may
     * be null (no residual where clause).<br>
     * <b>Postconditions:</b> never returns null or empty - {@code equiKeyFields} is always included, even if
     * nothing else references {@code alias} at all (e.g. a query that only selects the *other* side's columns),
     * since the join itself always needs its own key to match rows.</p>
     *
     * @param outer         the outer (already-compiled) join query's request; its {@link ResultRequest}s' {@link
     *                      TableSettings} (columns, value filter, aggregate filter) are scanned for references to
     *                      {@code alias}. Must not be null.
     * @param residualWhere the residual {@code where} clause computed by {@link JoinPredicateSplitter} (or the
     *                       original, unsplit {@code where} if no split was attempted) - nullable.
     * @param alias         the side's join alias (e.g. {@code "a"}). Must not be null.
     * @param equiKeyFields the side's own equi-key field name(s), already bare (not alias-qualified) - always
     *                      included in the result regardless of whether anything else references them. Must not
     *                      be null.
     * @return never null or empty; the bare field names {@code alias.compileJoinSide}'s synthetic sub-query
     *         should {@code select}.
     */
    static Set<String> fieldsNeededFor(
            final SearchRequest outer, final @Nullable ExpressionOperator residualWhere,
            final String alias, final List<String> equiKeyFields) {
        Objects.requireNonNull(outer, "outer");
        Objects.requireNonNull(alias, "alias");
        Objects.requireNonNull(equiKeyFields, "equiKeyFields");

        final Set<String> fields = new LinkedHashSet<>(equiKeyFields);
        collectFromExpressionTree(residualWhere, alias, fields);

        final List<ResultRequest> resultRequests = outer.getResultRequests();
        if (resultRequests != null) {
            for (final ResultRequest resultRequest : resultRequests) {
                final List<TableSettings> mappings = resultRequest.getMappings();
                if (mappings == null) {
                    continue;
                }
                for (final TableSettings tableSettings : mappings) {
                    collectFromTableSettings(tableSettings, alias, fields);
                }
            }
        }
        return fields;
    }

    private static void collectFromTableSettings(
            final TableSettings tableSettings, final String alias, final Set<String> out) {
        final List<Column> columns = tableSettings.getColumns();
        if (columns != null) {
            for (final Column column : columns) {
                collectFromExpressionText(column.getExpression(), alias, out);
            }
        }
        collectFromExpressionTree(tableSettings.getValueFilter(), alias, out);
        collectFromExpressionTree(tableSettings.getAggregateFilter(), alias, out);
    }

    /** Walks an {@link ExpressionItem} tree, adding the bare field name of every {@link ExpressionTerm} whose
     * field is qualified by {@code alias}. A term for a different alias, or an unqualified one, contributes
     * nothing here (it isn't this side's concern). */
    private static void collectFromExpressionTree(
            final @Nullable ExpressionItem item, final String alias, final Set<String> out) {
        if (item == null) {
            return;
        }
        if (item instanceof final ExpressionTerm term) {
            final String field = term.getField();
            final String prefix = alias + ".";
            if (field.startsWith(prefix)) {
                out.add(field.substring(prefix.length()));
            }
        } else if (item instanceof final ExpressionOperator operator) {
            final List<ExpressionItem> children = operator.getChildren();
            if (children != null) {
                for (final ExpressionItem child : children) {
                    collectFromExpressionTree(child, alias, out);
                }
            }
        }
    }

    /** Scans free-form expression text (a {@link Column#getExpression}) for every {@code alias.field} token -
     * see this class's Javadoc for why a regex scan, not a parse. A null/blank expression contributes nothing. */
    private static void collectFromExpressionText(
            final @Nullable String expression, final String alias, final Set<String> out) {
        if (expression == null || expression.isBlank()) {
            return;
        }
        final Matcher matcher = ALIAS_FIELD_TOKEN.matcher(expression);
        while (matcher.find()) {
            if (matcher.group(1).equals(alias)) {
                out.add(matcher.group(2));
            }
        }
    }
}
