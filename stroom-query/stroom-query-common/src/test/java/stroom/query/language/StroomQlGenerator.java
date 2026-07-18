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

import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Generates random-but-valid StroomQL queries over a small, fixed, representative datasource shape, for the
 * generative parity fuzzer (see docs/query-optimiser-implementation-plan.md, Task 1.7). Deliberately restricted
 * to constructs already proven to match in the hand-written corpus (see {@code TestQueryCompilerParity}) - this
 * generator's job is to explore combinations/depths the ~30 hand-picked queries don't reach, not to hunt for
 * genuinely new syntax or malformed-input edge cases (that's the hand corpus's job).
 *
 * <p>Clauses are always emitted in a single fixed order (where, eval, sort, group, having, select, limit) that
 * satisfies every ordering constraint in {@code stroom.query.api.token.TokenType} simultaneously - the generator
 * explores query *content* diversity, not clause-ordering edge cases.</p>
 */
final class StroomQlGenerator {

    private static final List<String> DATA_SOURCES = List.of("Test Index", "index_view", "View");
    private static final List<String> FIELDS =
            List.of("StreamId", "EventId", "EventTime", "UserId", "Description", "Status", "Feed");
    private static final List<String> COMPARISON_CONDITIONS = List.of("=", "!=", ">", ">=", "<", "<=");
    // Only functions actually recognised by the shared ExpressionParser are used, so a mismatch always indicates
    // a genuine bug rather than an unsupported-function false positive.
    private static final List<String> UNARY_FUNCTIONS = List.of("upperCase", "lowerCase");

    private final Random random;

    StroomQlGenerator(final Random random) {
        this.random = random;
    }

    String generate() {
        final StringBuilder sb = new StringBuilder();
        sb.append("from \"").append(pick(DATA_SOURCES)).append('"');

        if (random.nextBoolean()) {
            sb.append(" where ").append(generateExpr(2));
        }

        final int evalCount = random.nextInt(3);
        final List<String> evalVariables = IntStream.range(0, evalCount)
                .mapToObj(i -> "computed" + i)
                .collect(Collectors.toList());
        for (final String variable : evalVariables) {
            sb.append(" eval ").append(variable).append(" = ")
                    .append(pick(UNARY_FUNCTIONS)).append('(').append(pick(FIELDS)).append(')');
        }

        final boolean grouped = random.nextBoolean();
        if (random.nextBoolean()) {
            sb.append(" sort by ").append(pick(FIELDS));
            if (random.nextBoolean()) {
                sb.append(random.nextBoolean() ? " asc" : " desc");
            }
        }
        if (grouped) {
            sb.append(" group by ").append(pick(FIELDS));
        }
        if (grouped && random.nextBoolean()) {
            sb.append(" having ").append(pick(FIELDS)).append(" > 0");
        }

        // `limit` must precede `select` - see StroomQL's grammar/design doc Appendix A ("select ... limit N" is
        // rejected). Placing it earlier keeps the fuzzer inside the valid-query space it's meant to explore.
        if (random.nextBoolean()) {
            sb.append(" limit ").append(1 + random.nextInt(1000));
        }

        sb.append(" select ").append(generateSelectList(evalVariables));
        return sb.toString();
    }

    private String generateSelectList(final List<String> evalVariables) {
        final int fieldCount = 1 + random.nextInt(3);
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fieldCount; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(pick(FIELDS));
        }
        for (final String variable : evalVariables) {
            sb.append(", ").append(variable);
        }
        return sb.toString();
    }

    /** Generates a boolean expression, recursing (and/or/not/brackets) down to {@code depth} 0, where it always
     *  produces a single comparison term - bounding recursion so generated queries stay finite and readable. */
    private String generateExpr(final int depth) {
        if (depth <= 0) {
            return generateTerm();
        }
        final int choice = random.nextInt(5);
        return switch (choice) {
            case 0 -> generateTerm();
            case 1 -> generateExpr(depth - 1) + " and " + generateExpr(depth - 1);
            case 2 -> generateExpr(depth - 1) + " or " + generateExpr(depth - 1);
            case 3 -> "not " + generateTerm();
            default -> "(" + generateExpr(depth - 1) + ")";
        };
    }

    private String generateTerm() {
        final String field = pick(FIELDS);
        final int kind = random.nextInt(3);
        return switch (kind) {
            case 0 -> field + " " + pick(COMPARISON_CONDITIONS) + " " + generateValue();
            case 1 -> field + " between " + (1 + random.nextInt(100)) + " and " + (101 + random.nextInt(100));
            default -> field + " in (" + generateValue() + ", " + generateValue() + ")";
        };
    }

    private String generateValue() {
        return random.nextBoolean()
                ? String.valueOf(random.nextInt(10_000))
                : "'value" + random.nextInt(100) + "'";
    }

    private <T> T pick(final List<T> values) {
        return values.get(random.nextInt(values.size()));
    }
}
