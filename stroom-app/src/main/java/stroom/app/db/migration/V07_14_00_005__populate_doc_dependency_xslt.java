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

import stroom.docref.DocRef;
import stroom.docstore.impl.db.DocStoreDbConnProvider;
import stroom.pipeline.xslt.XsltReferenceLookup;
import stroom.pipeline.xslt.XsltReferenceParser;
import stroom.util.logging.LambdaLogger;
import stroom.util.logging.LambdaLoggerFactory;

import jakarta.inject.Inject;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * One-time migration to populate {@code doc_dependency} for existing XSLTs.
 * <p>
 * Everything an XSLT refers to lives as a string in its body rather than as a {@code DocRef} field, so
 * until now nothing an XSLT referenced appeared in {@code doc_dependency} at all. The store records those
 * references from now on, but only when a document is saved - without this migration an upgraded deployment
 * shows nothing until somebody opens and re-saves every XSLT by hand, which for most deployments means the
 * feature never appears to work.
 * <p>
 * Only the two reference kinds that name a document are recorded: {@code xsl:import}/{@code xsl:include}
 * targets, and {@code stroom:dictionary()} arguments. Lookup map names are deliberately not recorded,
 * because a map name does not identify a document - which store a lookup reaches depends on the pipeline's
 * configured references rather than on the XSLT.
 * <p>
 * <b>Safe to re-run.</b> Inserts are idempotent on the {@code (from_uuid, to_uuid)} unique key, and a
 * document whose stylesheet cannot be parsed is logged and skipped rather than failing the migration. A run
 * that dies part way through can simply be run again: the work already done is not repeated, and nothing
 * depends on the order documents are visited in.
 * <p>
 * Note that this is a cross-module migration only because the parser lives in {@code stroom-pipeline} while
 * the tables live in the docstore module. It touches one database, and it reads and writes nothing outside
 * it.
 */
@SuppressWarnings("unused") // Bound in CrossModuleDbMigrationsModule for Flyway to find.
public class V07_14_00_005__populate_doc_dependency_xslt extends AbstractCrossModuleJavaDbMigration {

    private static final LambdaLogger LOGGER =
            LambdaLoggerFactory.getLogger(V07_14_00_005__populate_doc_dependency_xslt.class);

    // Hard coded rather than taken from XsltDoc.TYPE and DictionaryDoc.TYPE on purpose. A migration must
    // keep doing what it did when it was written; were a type string ever changed, reading it from a
    // constant would silently change the meaning of a migration that has already run elsewhere.
    private static final String XSLT_TYPE = "XSLT";
    private static final String DICTIONARY_TYPE = "Dictionary";

    /**
     * The stylesheet body is held as a text asset beside the document's JSON, under the extension the
     * serialiser writes it with.
     */
    private static final String XSL_EXTENSION = "xsl";

    /**
     * How often to flush inserts and report progress. Large enough to keep the batch worthwhile, small
     * enough that a big deployment reports progress rather than appearing to hang.
     */
    private static final int BATCH_SIZE = 500;

    private static final String SELECT_XSLTS = """
            SELECT d.uuid, d.name, dd.text_data
            FROM doc d
            JOIN doc_data dd ON dd.fk_doc_id = d.id AND dd.ext = ?
            WHERE d.type = ?
            AND d.deleted IS NULL
            AND dd.text_data IS NOT NULL
            """;

    // The collation matters and must not be dropped. The doc table's default collation is case
    // insensitive, but the runtime resolves names with `name COLLATE utf8mb4_0900_as_cs`, so matching
    // case insensitively here would record edges the runtime would never follow.
    private static final String SELECT_BY_NAME = """
            SELECT uuid, name
            FROM doc
            WHERE type = ?
            AND name COLLATE utf8mb4_0900_as_cs = ?
            AND deleted IS NULL
            """;

    private static final String SELECT_BY_UUID = """
            SELECT uuid, name
            FROM doc
            WHERE type = ?
            AND uuid = ?
            AND deleted IS NULL
            """;

    // ON DUPLICATE KEY UPDATE with a no-op self assignment, rather than INSERT IGNORE, so re-running is
    // free but a genuine error such as data truncation is still raised.
    private static final String INSERT_DEPENDENCY = """
            INSERT INTO doc_dependency
                (from_type, from_uuid, from_name, to_type, to_uuid, to_name)
            VALUES (?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE from_type = from_type
            """;

    private final DocStoreDbConnProvider docStoreDbConnProvider;

    @Inject
    public V07_14_00_005__populate_doc_dependency_xslt(final DocStoreDbConnProvider docStoreDbConnProvider) {
        this.docStoreDbConnProvider = docStoreDbConnProvider;
    }

    @Override
    public void migrate(final Context context) throws Exception {
        int xsltCount = 0;
        int edgeCount = 0;
        int errorCount = 0;
        int unresolvedCount = 0;

        try (final Connection connection = docStoreDbConnProvider.getConnection();
                final PreparedStatement selectStmt = connection.prepareStatement(SELECT_XSLTS);
                final PreparedStatement byNameStmt = connection.prepareStatement(SELECT_BY_NAME);
                final PreparedStatement byUuidStmt = connection.prepareStatement(SELECT_BY_UUID);
                final PreparedStatement insertStmt = connection.prepareStatement(INSERT_DEPENDENCY)) {

            final XsltReferenceParser parser =
                    XsltReferenceParser.create(new SqlLookup(byNameStmt, byUuidStmt));

            selectStmt.setString(1, XSL_EXTENSION);
            selectStmt.setString(2, XSLT_TYPE);

            try (final ResultSet resultSet = selectStmt.executeQuery()) {
                while (resultSet.next()) {
                    final String uuid = resultSet.getString(1);
                    final String name = safeStr(resultSet.getString(2));
                    final String data = resultSet.getString(3);
                    xsltCount++;

                    try {
                        // Never throws, so a half-written stylesheet costs its own edges and nothing else.
                        final var references = parser.parse(data);
                        unresolvedCount += references.unresolved().size();

                        final Set<DocRef> targets = references.documentTargets();
                        for (final DocRef target : targets) {
                            insertStmt.setString(1, XSLT_TYPE);
                            insertStmt.setString(2, uuid);
                            insertStmt.setString(3, name);
                            insertStmt.setString(4, safeStr(target.getType()));
                            insertStmt.setString(5, target.getUuid());
                            insertStmt.setString(6, safeStr(target.getName()));
                            insertStmt.addBatch();
                            edgeCount++;
                        }
                    } catch (final Exception e) {
                        // Containment per document: one unreadable XSLT must not deny every other XSLT its
                        // dependencies, and must not leave the migration needing manual repair.
                        errorCount++;
                        LOGGER.error(() -> "Error extracting dependencies from XSLT ("
                                           + uuid + "): " + e.getMessage(), e);
                    }

                    if (xsltCount % BATCH_SIZE == 0) {
                        insertStmt.executeBatch();
                        final int processed = xsltCount;
                        final int edges = edgeCount;
                        LOGGER.info(() -> "XSLTs: processed " + processed + ", " + edges + " edges so far");
                    }
                }
            }

            insertStmt.executeBatch();
        }

        final int processed = xsltCount;
        final int edges = edgeCount;
        final int errors = errorCount;
        final int unresolved = unresolvedCount;
        LOGGER.info(() -> "doc_dependency XSLT migration complete: processed=" + processed
                          + ", edges=" + edges + ", errors=" + errors
                          + ", unresolved references=" + unresolved);
    }

    private static String safeStr(final String value) {
        return value == null
                ? ""
                : value;
    }

    /**
     * Resolves names and UUIDs straight from the {@code doc} table.
     * <p>
     * The parser reaches the document store only through this interface, which is what lets it run here at
     * all: there is no Guice injector during a migration, and no docstore service to call.
     * <p>
     * Not thread safe - it holds prepared statements - which is fine, because the migration is
     * single threaded.
     */
    private static class SqlLookup implements XsltReferenceLookup {

        private final PreparedStatement byNameStmt;
        private final PreparedStatement byUuidStmt;

        SqlLookup(final PreparedStatement byNameStmt, final PreparedStatement byUuidStmt) {
            this.byNameStmt = byNameStmt;
            this.byUuidStmt = byUuidStmt;
        }

        @Override
        public List<DocRef> findByName(final String type, final String name) {
            try {
                byNameStmt.setString(1, type);
                byNameStmt.setString(2, name);
                return query(byNameStmt, type);
            } catch (final Exception e) {
                // A lookup failure must not fail the migration; the reference is simply left unresolved.
                LOGGER.error(() -> "Error finding " + type + " named '" + name + "': " + e.getMessage(), e);
                return List.of();
            }
        }

        @Override
        public Optional<DocRef> findByUuid(final String type, final String uuid) {
            try {
                byUuidStmt.setString(1, type);
                byUuidStmt.setString(2, uuid);
                return query(byUuidStmt, type).stream().findFirst();
            } catch (final Exception e) {
                LOGGER.error(() -> "Error finding " + type + " with UUID '" + uuid + "': "
                                   + e.getMessage(), e);
                return Optional.empty();
            }
        }

        private static List<DocRef> query(final PreparedStatement statement, final String type)
                throws Exception {
            final List<DocRef> docRefs = new ArrayList<>();
            try (final ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    docRefs.add(new DocRef(type, resultSet.getString(1), safeStr(resultSet.getString(2))));
                }
            }
            return docRefs;
        }
    }
}
