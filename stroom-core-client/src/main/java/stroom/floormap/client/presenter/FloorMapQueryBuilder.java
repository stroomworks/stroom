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

package stroom.floormap.client.presenter;

import stroom.floormap.client.ValuePathAccessor;
import stroom.floormap.shared.FloorMapFieldMapping;
import stroom.floormap.shared.ValueFormat;

import java.util.List;

/**
 * Generates StroomQL queries for reading floor map facts from a temporal store.
 *
 * <p>The generated query is derived entirely from the
 * {@link FloorMapFieldMapping} value schema and the {@link ValueFormat}. Each
 * mapping's {@link FloorMapFieldMapping#getPath() path} is wrapped in the
 * appropriate extraction function ({@code jq()} for JSON, {@code xpath()} for
 * XML) and aliased to a SQL-safe column name.</p>
 *
 * <p>This eliminates the need for a user-editable "Facts Query" — the query
 * is always in sync with the value schema configured in the Settings tab.</p>
 */
public final class FloorMapQueryBuilder {

    private FloorMapQueryBuilder() {
        // Utility class
    }

    /**
     * Builds a StroomQL query string that selects {@code Key},
     * {@code EffectiveTime}, and one column per schema mapping from
     * the {@code param('FactStore')} source.
     *
     * @param schema the value schema mappings; must not be {@code null} or empty
     * @param format the value serialisation format; must not be {@code null}
     * @return the generated StroomQL query string
     */
    public static String buildFactsQuery(final List<FloorMapFieldMapping> schema,
                                         final ValueFormat format) {
        final StringBuilder sb = new StringBuilder();
        sb.append("from param('FactStore')\nselect \n  Key, \n  EffectiveTime");

        for (final FloorMapFieldMapping mapping : schema) {
            final String path = mapping.getPath();
            if (path == null || path.isEmpty()) {
                continue;
            }
            final String expr = buildExtractExpression(path, format);
            final String alias = buildColumnAlias(path, format);
            sb.append(", \n  ").append(expr).append(" as ").append(alias);
        }
        return sb.toString();
    }

    /**
     * Builds the StroomQL extraction expression for a single path.
     *
     * <p>For JSON, wraps in {@code jq(Value, ...)}. Keys containing
     * characters that are not valid unquoted jq identifiers (e.g.
     * hyphens) are quoted.</p>
     *
     * <p>For XML, wraps in {@code xpath(Value, ...)} using the path
     * directly (which is expected to be a valid XPath from the root
     * of the Value XML).</p>
     *
     * @param path   the schema path (e.g. {@code ".type"} for JSON,
     *               {@code "/entry/type"} for XML)
     * @param format the value serialisation format
     * @return the StroomQL expression (e.g. {@code jq(Value, ".type")})
     */
    public static String buildExtractExpression(final String path,
                                                final ValueFormat format) {
        return switch (format) {
            case JSON -> {
                final String key = ValuePathAccessor.toKey(path);
                if (needsQuoting(key)) {
                    yield "jq(Value, \"\\\"" + key + "\\\"\")";
                } else {
                    yield "jq(Value, \"" + path + "\")";
                }
            }
            case XML -> "xpath(Value, \"" + path + "\")";
        };
    }

    /**
     * Derives a SQL-safe column alias from a schema path.
     *
     * <p>For JSON, strips the leading dot and replaces hyphens with
     * underscores (e.g. {@code ".tm-world-to-map"} →
     * {@code "tm_world_to_map"}).</p>
     *
     * <p>For XML, takes the last path segment and strips any leading
     * {@code @} for attributes (e.g. {@code "/entry/@type"} →
     * {@code "type"}).</p>
     *
     * @param path   the schema path
     * @param format the value serialisation format
     * @return the SQL-safe column alias
     */
    public static String buildColumnAlias(final String path,
                                          final ValueFormat format) {
        return switch (format) {
            case JSON -> ValuePathAccessor.toKey(path).replace("-", "_");
            case XML -> {
                final String last = path.contains("/")
                        ? path.substring(path.lastIndexOf('/') + 1)
                        : path;
                yield last.startsWith("@")
                        ? last.substring(1)
                        : last;
            }
        };
    }

    /**
     * Returns {@code true} if the key contains characters that require
     * quoting in a jq expression (anything other than alphanumerics
     * and underscores).
     */
    private static boolean needsQuoting(final String key) {
        for (int i = 0; i < key.length(); i++) {
            final char c = key.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') {
                return true;
            }
        }
        return false;
    }
}
