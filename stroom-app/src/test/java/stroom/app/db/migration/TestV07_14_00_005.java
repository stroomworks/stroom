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

package stroom.app.db.migration;

import stroom.db.util.DbUtil;
import stroom.docstore.impl.db.DocStoreDbConnProvider;
import stroom.util.exception.ThrowingConsumer;
import stroom.util.exception.ThrowingFunction;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;

import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests {@link V07_14_00_005__populate_doc_dependency_xslt} against a real database.
 * <p>
 * The parser and the edges it yields are covered by unit tests elsewhere; what only a database can check is
 * the part this migration adds - that the SQL matches the schema, that the stylesheet body is read from the
 * right column, and that name resolution keeps the runtime's case sensitivity rather than the table's
 * case-insensitive default.
 */
public class TestV07_14_00_005 extends AbstractCrossModuleMigrationTest {

    private static final LambdaLogger LOGGER = LambdaLoggerFactory.getLogger(TestV07_14_00_005.class);

    private static final String IMPORTER_UUID = "xslt_uuid_importer";
    private static final String COMMON_UUID = "xslt_uuid_common";
    private static final String DICTIONARY_UUID = "dict_uuid_geoip";
    private static final String LOOKUP_ONLY_UUID = "xslt_uuid_lookup_only";
    private static final String BROKEN_UUID = "xslt_uuid_broken";
    private static final String WRONG_CASE_UUID = "xslt_uuid_wrong_case";

    @Override
    public Class<? extends AbstractCrossModuleMigrationTestData> getTestDataClass() {
        return TestData.class;
    }

    @Override
    Class<? extends AbstractCrossModuleJavaDbMigration> getTargetClass() {
        return V07_14_00_005__populate_doc_dependency_xslt.class;
    }

    @Test
    void test() {
        // Five XSLTs and one dictionary were inserted by TestData before the migration ran. Reaching this
        // point at all means the migration completed rather than failing on the malformed one below.
        final List<String> edges = fetchEdges();

        // The importer names another XSLT by name and a dictionary by name, so both resolve.
        assertThat(edges).contains(
                IMPORTER_UUID + " -> XSLT " + COMMON_UUID,
                IMPORTER_UUID + " -> Dictionary " + DICTIONARY_UUID);

        // A lookup names a map, not a document, so it contributes no edge at all. This is the two-halves
        // constraint: which store a lookup reaches depends on the pipeline's configured references.
        assertThat(edges).noneMatch(edge -> edge.startsWith(LOOKUP_ONLY_UUID));

        // A stylesheet that is not well-formed XML is skipped, and crucially does not fail the migration -
        // if it had, this test would not have got as far as asserting anything.
        assertThat(edges).noneMatch(edge -> edge.startsWith(BROKEN_UUID));

        // Names are matched case sensitively, as the runtime does. The doc table's own collation is case
        // insensitive, so a query without an explicit COLLATE would wrongly resolve 'COMMON' here and
        // record an edge the runtime would never follow.
        assertThat(edges).noneMatch(edge -> edge.startsWith(WRONG_CASE_UUID));

        // Exactly the two edges above, from the whole corpus.
        assertThat(edges).hasSize(2);
    }

    private List<String> fetchEdges() {
        return DbUtil.getWithPreparedStatement(
                getDatasource(DocStoreDbConnProvider.class),
                """
                        SELECT from_uuid, to_type, to_uuid
                        FROM doc_dependency
                        ORDER BY from_uuid, to_uuid
                        """,
                ThrowingFunction.unchecked(prepStmt -> {
                    final List<String> edges = new ArrayList<>();
                    try (final ResultSet resultSet = prepStmt.executeQuery()) {
                        while (resultSet.next()) {
                            edges.add(resultSet.getString(1)
                                      + " -> " + resultSet.getString(2)
                                      + " " + resultSet.getString(3));
                        }
                    }
                    LOGGER.info(() -> "doc_dependency edges: " + edges);
                    return edges;
                }));
    }

    /**
     * Inserts the documents the migration will find. Runs as a Flyway migration immediately before the one
     * under test.
     */
    static class TestData extends AbstractCrossModuleMigrationTestData {

        private static final String INSERT_DOC = """
                INSERT INTO doc (type, uuid, name, version)
                VALUES (?, ?, ?, '1')
                """;

        private static final String INSERT_DOC_DATA = """
                INSERT INTO doc_data (fk_doc_id, ext, data_type, text_data)
                VALUES (?, 'xsl', 2, ?)
                """;

        private final DocStoreDbConnProvider docStoreDbConnProvider;

        @Inject
        TestData(final TestState testState, final DocStoreDbConnProvider docStoreDbConnProvider) {
            super(testState);
            this.docStoreDbConnProvider = docStoreDbConnProvider;
        }

        @Override
        void setupTestData() throws Exception {
            // The documents referred to. 'Common' is what the importer imports by name, and the dictionary
            // is what it reads.
            insertDoc("XSLT", COMMON_UUID, "Common", null);
            insertDoc("Dictionary", DICTIONARY_UUID, "GeoIP", null);

            insertDoc("XSLT", IMPORTER_UUID, "Importer", """
                    <xsl:stylesheet version="2.0"
                                    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                                    xmlns:stroom="stroom">
                      <xsl:import href="Common"/>
                      <xsl:template match="/">
                        <xsl:value-of select="stroom:dictionary('GeoIP')"/>
                      </xsl:template>
                    </xsl:stylesheet>""");

            insertDoc("XSLT", LOOKUP_ONLY_UUID, "Lookup Only", """
                    <xsl:stylesheet version="2.0"
                                    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                                    xmlns:stroom="stroom">
                      <xsl:template match="/">
                        <xsl:value-of select="stroom:lookup('geo_ip', @ip)"/>
                      </xsl:template>
                    </xsl:stylesheet>""");

            insertDoc("XSLT", BROKEN_UUID, "Broken", "<xsl:stylesheet><unclosed>");

            // Imports 'COMMON', which differs from 'Common' only in case.
            insertDoc("XSLT", WRONG_CASE_UUID, "Wrong Case", """
                    <xsl:stylesheet version="2.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
                      <xsl:import href="COMMON"/>
                    </xsl:stylesheet>""");
        }

        private void insertDoc(final String type,
                               final String uuid,
                               final String name,
                               final String data) {
            final long docId = DbUtil.getWithPreparedStatement(
                    docStoreDbConnProvider,
                    INSERT_DOC,
                    true,
                    ThrowingFunction.unchecked(prepStmt -> {
                        prepStmt.setString(1, type);
                        prepStmt.setString(2, uuid);
                        prepStmt.setString(3, name);
                        prepStmt.executeUpdate();
                        try (final ResultSet keys = prepStmt.getGeneratedKeys()) {
                            if (!keys.next()) {
                                throw new IllegalStateException("No generated key for doc " + uuid);
                            }
                            return keys.getLong(1);
                        }
                    }));

            if (data != null) {
                DbUtil.doWithPreparedStatement(
                        docStoreDbConnProvider,
                        INSERT_DOC_DATA,
                        ThrowingConsumer.unchecked(prepStmt -> {
                            prepStmt.setLong(1, docId);
                            prepStmt.setString(2, data);
                            prepStmt.executeUpdate();
                        }));
            }
            LOGGER.info(() -> "Inserted " + type + " '" + name + "' (" + uuid + ")");
        }
    }
}
