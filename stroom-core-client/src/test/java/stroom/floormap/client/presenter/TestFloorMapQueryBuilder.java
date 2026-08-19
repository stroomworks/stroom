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

import stroom.floormap.shared.FloorMapFieldMapping;
import stroom.floormap.shared.FloorMapFieldMapping.Role;
import stroom.floormap.shared.ValueFormat;
import stroom.query.api.token.QuotedStringUtil;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link FloorMapQueryBuilder} — verifying that the generated
 * StroomQL queries and column aliases are correct for both JSON and XML
 * value formats.
 */
class TestFloorMapQueryBuilder {

    // ---- buildFactsQuery (JSON) ----

    @Test
    void testBuildFactsQuery_json_singleMapping() {
        final List<FloorMapFieldMapping> schema = List.of(
                new FloorMapFieldMapping(".type", Role.TYPE, "Type", null));

        final String query = FloorMapQueryBuilder.buildFactsQuery(
                schema, ValueFormat.JSON);

        assertThat(query).startsWith("from param('FactStore')");
        assertThat(query).contains("Key");
        assertThat(query).contains("EffectiveTime");
        assertThat(query).contains("jq(Value, \".type\") as type");
    }

    @Test
    void testBuildFactsQuery_json_multipleMappings() {
        final List<FloorMapFieldMapping> schema = List.of(
                new FloorMapFieldMapping(".type", Role.TYPE, "Type", null),
                new FloorMapFieldMapping(
                        ".coords", Role.POSITION, "Position", null),
                new FloorMapFieldMapping(
                        ".img", Role.IMAGE, "Image", null));

        final String query = FloorMapQueryBuilder.buildFactsQuery(
                schema, ValueFormat.JSON);

        assertThat(query).contains("jq(Value, \".type\") as type");
        assertThat(query).contains("jq(Value, \".coords\") as coords");
        assertThat(query).contains("jq(Value, \".img\") as img");
    }

    @Test
    void testBuildFactsQuery_json_hyphenatedKey() {
        final List<FloorMapFieldMapping> schema = List.of(
                new FloorMapFieldMapping(
                        ".tm-world-to-map", Role.WORLD_TO_MAP,
                        "World to Map", null));

        final String query = FloorMapQueryBuilder.buildFactsQuery(
                schema, ValueFormat.JSON);

        // Hyphenated keys need quoting in jq — with the leading dot for field
        // access (a bare quoted string is a jq literal, not a lookup).
        assertThat(query).contains(
                "jq(Value, \".\\\"tm-world-to-map\\\"\") as tm_world_to_map");
    }

    @Test
    void testBuildFactsQuery_json_skipsNullPath() {
        final List<FloorMapFieldMapping> schema = List.of(
                new FloorMapFieldMapping(
                        null, Role.TYPE, "Type", null),
                new FloorMapFieldMapping(
                        ".coords", Role.POSITION, "Position", null));

        final String query = FloorMapQueryBuilder.buildFactsQuery(
                schema, ValueFormat.JSON);

        // Should only contain the coords mapping, not type
        assertThat(query).doesNotContain("type");
        assertThat(query).contains("jq(Value, \".coords\") as coords");
    }

    @Test
    void testBuildFactsQuery_json_skipsEmptyPath() {
        final List<FloorMapFieldMapping> schema = List.of(
                new FloorMapFieldMapping(
                        "", Role.TYPE, "Type", null),
                new FloorMapFieldMapping(
                        ".coords", Role.POSITION, "Position", null));

        final String query = FloorMapQueryBuilder.buildFactsQuery(
                schema, ValueFormat.JSON);

        assertThat(query).doesNotContain("as type");
        assertThat(query).contains("jq(Value, \".coords\") as coords");
    }

    // ---- buildFactsQuery (XML) ----

    @Test
    void testBuildFactsQuery_xml_singleMapping() {
        final List<FloorMapFieldMapping> schema = List.of(
                new FloorMapFieldMapping(
                        "/entry/type", Role.TYPE, "Type", null));

        final String query = FloorMapQueryBuilder.buildFactsQuery(
                schema, ValueFormat.XML);

        assertThat(query).startsWith("from param('FactStore')");
        assertThat(query).contains(
                "xpath(Value, \"/entry/type\") as type");
    }

    @Test
    void testBuildFactsQuery_xml_attributePath() {
        final List<FloorMapFieldMapping> schema = List.of(
                new FloorMapFieldMapping(
                        "/entry/@type", Role.TYPE, "Type", null));

        final String query = FloorMapQueryBuilder.buildFactsQuery(
                schema, ValueFormat.XML);

        assertThat(query).contains(
                "xpath(Value, \"/entry/@type\") as type");
    }

    // ---- buildExtractExpression ----

    @Test
    void testBuildExtractExpression_json_simplePath() {
        final String expr = FloorMapQueryBuilder.buildExtractExpression(
                ".type", ValueFormat.JSON);
        assertThat(expr).isEqualTo("jq(Value, \".type\")");
    }

    @Test
    void testBuildExtractExpression_json_hyphenatedPath() {
        final String expr = FloorMapQueryBuilder.buildExtractExpression(
                ".tm-world-to-map", ValueFormat.JSON);
        assertThat(expr).isEqualTo(
                "jq(Value, \".\\\"tm-world-to-map\\\"\")");
    }

    @Test
    void testBuildExtractExpression_xml_elementPath() {
        final String expr = FloorMapQueryBuilder.buildExtractExpression(
                "/entry/type", ValueFormat.XML);
        assertThat(expr).isEqualTo("xpath(Value, \"/entry/type\")");
    }

    @Test
    void testBuildExtractExpression_xml_attributePath() {
        final String expr = FloorMapQueryBuilder.buildExtractExpression(
                "/entry/@id", ValueFormat.XML);
        assertThat(expr).isEqualTo("xpath(Value, \"/entry/@id\")");
    }

    // ---- buildColumnAlias ----

    @Test
    void testBuildColumnAlias_json_simplePath() {
        final String alias = FloorMapQueryBuilder.buildColumnAlias(
                ".type", ValueFormat.JSON);
        assertThat(alias).isEqualTo("type");
    }

    @Test
    void testBuildColumnAlias_json_hyphenReplaced() {
        final String alias = FloorMapQueryBuilder.buildColumnAlias(
                ".tm-world-to-map", ValueFormat.JSON);
        assertThat(alias).isEqualTo("tm_world_to_map");
    }

    @Test
    void testBuildColumnAlias_xml_lastSegment() {
        final String alias = FloorMapQueryBuilder.buildColumnAlias(
                "/entry/type", ValueFormat.XML);
        assertThat(alias).isEqualTo("type");
    }

    @Test
    void testBuildColumnAlias_xml_nestedPath() {
        final String alias = FloorMapQueryBuilder.buildColumnAlias(
                "/entry/nested/prop", ValueFormat.XML);
        assertThat(alias).isEqualTo("prop");
    }

    @Test
    void testBuildColumnAlias_xml_attributeStripsAt() {
        final String alias = FloorMapQueryBuilder.buildColumnAlias(
                "/entry/@type", ValueFormat.XML);
        assertThat(alias).isEqualTo("type");
    }

    @Test
    void testBuildColumnAlias_xml_singleSegment() {
        final String alias = FloorMapQueryBuilder.buildColumnAlias(
                "type", ValueFormat.XML);
        assertThat(alias).isEqualTo("type");
    }
    // ---- interpolated paths are escaped for the enclosing StroomQL literal ----

    /**
     * A schema path containing a double quote must not close the StroomQL literal
     * it is interpolated into.
     *
     * <p>Schema paths come from the Settings grid, so this is ordinary user input
     * rather than a hostile edge case. Unescaped, {@code .a"b} produced
     * {@code jq(Value, ".a"b")} — the literal ends at the second quote and the rest
     * is stray tokens, so the whole query fails to parse.</p>
     */
    @Test
    void testBuildExtractExpression_json_escapesQuoteInPath() {
        final String expr = FloorMapQueryBuilder.buildExtractExpression(
                ".a\"b", ValueFormat.JSON);

        assertThat(expr)
                .as("the quote must be escaped, not left to terminate the literal")
                .isEqualTo("jq(Value, \".\\\"a\\\\\\\"b\\\"\")");
        assertUnescapesTo(expr, "jq(Value, ", ".\"a\\\"b\"");
    }

    /**
     * An XPath may legitimately contain quotes — a predicate such as
     * {@code /entry[@type="gate"]} is perfectly ordinary — so the XML branch has to
     * escape them too.
     */
    @Test
    void testBuildExtractExpression_xml_escapesQuotesInXPath() {
        final String expr = FloorMapQueryBuilder.buildExtractExpression(
                "/entry[@type=\"gate\"]", ValueFormat.XML);

        assertThat(expr).isEqualTo(
                "xpath(Value, \"/entry[@type=\\\"gate\\\"]\")");
        assertUnescapesTo(expr, "xpath(Value, ", "/entry[@type=\"gate\"]");
    }

    /** A backslash in a path is escaped so it survives unescaping intact. */
    @Test
    void testBuildExtractExpression_xml_escapesBackslash() {
        final String expr = FloorMapQueryBuilder.buildExtractExpression(
                "/entry/a\\b", ValueFormat.XML);
        assertUnescapesTo(expr, "xpath(Value, ", "/entry/a\\b");
    }

    /** Ordinary paths are unchanged, so the common case reads as before. */
    @Test
    void testBuildExtractExpression_ordinaryPathsAreUnchanged() {
        assertThat(FloorMapQueryBuilder.buildExtractExpression(".type", ValueFormat.JSON))
                .isEqualTo("jq(Value, \".type\")");
        assertThat(FloorMapQueryBuilder.buildExtractExpression("/entry/type", ValueFormat.XML))
                .isEqualTo("xpath(Value, \"/entry/type\")");
    }

    /**
     * Extracts the quoted literal from {@code prefix"..."} and asserts that
     * unescaping it — exactly as the query tokeniser does — recovers
     * {@code expected}. Checking the round trip rather than only the literal text
     * proves the escaping is actually correct rather than merely different.
     */
    private static void assertUnescapesTo(final String expression,
                                          final String prefix,
                                          final String expected) {
        assertThat(expression).startsWith(prefix + "\"");
        assertThat(expression).endsWith("\")");
        final String literal = expression.substring(
                prefix.length(), expression.length() - 1);
        final char[] chars = literal.toCharArray();
        assertThat(QuotedStringUtil.unescape(chars, 0, chars.length - 1, '\\'))
                .as("tokeniser view of " + literal)
                .isEqualTo(expected);
    }

}
