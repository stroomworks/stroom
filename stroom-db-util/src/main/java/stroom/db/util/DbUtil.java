/*
 * Copyright 2024 Crown Copyright
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

package stroom.db.util;

import stroom.config.common.ConnectionConfig;
import stroom.util.logging.LogUtil;

import com.google.common.base.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.sql.DataSource;

public class DbUtil {

    private static final Logger LOGGER = LoggerFactory.getLogger(DbUtil.class);
    private static final long MAX_SLEEP_TIME_MS = 30_000;
    private static final int ACCESS_DENIED_BAD_UNAME_OR_PWORD = 1045;
    private static final int ACCESS_DENIED_BAD_DATABASE = 1044;

    private DbUtil() {
    }

    public static void validate(final ConnectionConfig connectionConfig) {
        Preconditions.checkNotNull(connectionConfig.getClassName(),
                "The JDBC driver class has not been supplied. %s", connectionConfig);
        Preconditions.checkNotNull(connectionConfig.getUrl(),
                "The JDBC URL has not been supplied. %s", connectionConfig);
        Preconditions.checkNotNull(connectionConfig.getUser(),
                "The JDBC username has not been supplied. %s", connectionConfig);
        Preconditions.checkNotNull(connectionConfig.getPassword(),
                "The JDBC password has not been supplied. %s", connectionConfig);

        try {
            Class.forName(connectionConfig.getClassName());
        } catch (final ClassNotFoundException e) {
            throw new RuntimeException(LogUtil.message(
                    "Invalid JDBC driver class name {}", connectionConfig.getClassName()), e);
        }
    }

    public static Connection getSingleConnection(final ConnectionConfig connectionConfig) throws SQLException {
        return DriverManager.getConnection(
                connectionConfig.getUrl(),
                connectionConfig.getUser(),
                connectionConfig.getPassword());
    }

    public static void doWithPreparedStatement(final DataSource dataSource,
                                               final String sql,
                                               final Consumer<PreparedStatement> consumer) {
        if (consumer != null) {

            try {
                try (final Connection connection = dataSource.getConnection()) {
                    try (final PreparedStatement prepStmt = connection.prepareStatement(sql)) {
                        LOGGER.debug("prepStmt: {}", prepStmt);
                        consumer.accept(prepStmt);
                    }
                }
            } catch (final SQLException e) {
                throw new RuntimeException(LogUtil.message("Error running SQL: '{}', {}", sql, e.getMessage()), e);
            }
        }
    }

    public static <T> T getWithPreparedStatement(final DataSource dataSource,
                                                 final String sql,
                                                 final Function<PreparedStatement, T> function) {
        return getWithPreparedStatement(dataSource, sql, false, function);
    }

    public static <T> T getWithPreparedStatement(final DataSource dataSource,
                                                 final String sql,
                                                 final boolean retrieveAutoGeneratedKeys,
                                                 final Function<PreparedStatement, T> function) {
        if (function != null) {
            try {
                try (final Connection connection = dataSource.getConnection()) {
                    try (final PreparedStatement prepStmt = createPrepStmt(
                            connection,
                            sql,
                            retrieveAutoGeneratedKeys)) {
                        LOGGER.debug("prepStmt: {}", prepStmt);
                        return function.apply(prepStmt);
                    }
                }
            } catch (final SQLException e) {
                throw new RuntimeException(LogUtil.message("Error running SQL: '{}', {}", sql, e.getMessage()), e);
            }
        } else {
            return null;
        }
    }

    private static PreparedStatement createPrepStmt(final Connection connection,
                                                    final String sql,
                                                    final boolean retrieveAutoGeneratedKeys) throws SQLException {
        return retrieveAutoGeneratedKeys
                ? connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)
                : connection.prepareStatement(sql);
    }

    public static void executeStatement(final DataSource dataSource,
                                        final String sql) {
        try {
            try (final Connection connection = dataSource.getConnection()) {
                try (final Statement stmt = connection.createStatement()) {
                    LOGGER.debug("prepStmt: {}", stmt);
                    stmt.execute(sql);
                }
            }
        } catch (final SQLException e) {
            throw new RuntimeException(LogUtil.message("Error running SQL: '{}', {}", sql, e.getMessage()), e);
        }
    }

    public static void waitForConnection(final ConnectionConfig connectionConfig) {
        waitForConnection(connectionConfig, null);
    }

    /**
     * Attempts to connect to the database using the passed connection details. If it fails
     * it will log a warning, and keep retrying. The retry interval will steadily increase.
     * <p>
     * If the connection could not be established and the reason for the failure makes a
     * retry pointless, e.g. invalid password, then an exception will be thrown.
     */
    public static void waitForConnection(final ConnectionConfig connectionConfig,
                                         final String connectionName) {
        final String jdbcUrl = connectionConfig.getUrl();
        final String username = connectionConfig.getUser();
        final String nameText = connectionName != null
                ? connectionName + " "
                : "";
        LOGGER.info("Ensuring database connection {}to [{}] with username [{}] and driver class [{}]",
                nameText, jdbcUrl, username, connectionConfig.getClassName());

        long sleepMs = 500;
        Throwable lastThrowable = null;

        while (true) {
            try (final Connection connection = getSingleConnection(connectionConfig)) {
                LOGGER.info("Successfully established connection to {} with username {}", jdbcUrl, username);
                break;
            } catch (final SQLException e) {
                if (e.getErrorCode() == ACCESS_DENIED_BAD_UNAME_OR_PWORD ||
                        e.getErrorCode() == ACCESS_DENIED_BAD_DATABASE ||
                        (e.getMessage() != null && e.getMessage().startsWith("Unsupported"))) {

                    // These errors are not due to the DB not being up, so throw it
                    throw new RuntimeException(LogUtil.message(
                            "Error connecting to {} with username {}", jdbcUrl, username), e);
                }
                final Throwable cause = e.getCause();
                final String errorMsg = cause != null
                        ? cause.getMessage()
                        : e.getMessage();
                final int vendorCode = e.getErrorCode();
                LOGGER.warn("Unable to establish database connection due to error: [{}] " +
                                "and vendorCode [{}], will try again " +
                                "in {}ms, enable debug to see stack trace",
                        errorMsg, vendorCode, sleepMs);
                if (LOGGER.isDebugEnabled()) {
                    if (lastThrowable == null || !e.getMessage().equals(lastThrowable.getMessage())) {
                        // Only log the stack when it changes, else it fills up the log pretty quickly
                        LOGGER.debug("Unable to establish database connection due to error", e);
                    }
                    lastThrowable = e;
                }
            }
            try {
                Thread.sleep(sleepMs);
            } catch (final InterruptedException e) {
                // Nothing to do here as the
                Thread.currentThread().interrupt();
                break;
            }

            // Gradually increase the sleep time up to a maximum
            sleepMs = (long) (sleepMs * 1.3);
            if (sleepMs >= MAX_SLEEP_TIME_MS) {
                sleepMs = MAX_SLEEP_TIME_MS;
            }
        }
    }

    public static boolean validateConnection(final DataSource dataSource) {
        try (final Connection connection = dataSource.getConnection()) {
            return true;
        } catch (final SQLException e) {
            final Throwable cause = e.getCause();
            final String errorMsg = cause != null
                    ? cause.getMessage()
                    : e.getMessage();
            final int vendorCode = e.getErrorCode();
            throw new RuntimeException(LogUtil.message(
                    "Unable to establish database connection due to error: [{}] and vendorCode [{}].",
                    errorMsg, vendorCode));
        }
    }

    public static int countEntity(final DataSource dataSource, final String tableName) {
        try (final Connection connection = dataSource.getConnection()) {
            return countEntity(connection, tableName);
        } catch (final SQLException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public static int countEntity(final Connection connection, final String tableName) {
        try (final Statement statement = connection.createStatement()) {
            try (final ResultSet resultSet = statement.executeQuery("SELECT count(*) FROM " + tableName)) {
                if (resultSet.next()) {
                    return resultSet.getInt(1);
                }
            }
        } catch (final SQLException e) {
            throw new RuntimeException(e.getMessage(), e);
        }

        return 0;
    }

    public static boolean doesTableExist(final Connection connection, final String tableName) {
        try {
            final String catalog = connection.getCatalog(); // AKA the mysql DB in use
            final String schema = connection.getSchema();
            LOGGER.debug("doesTableExist - catalog: {}, schema: {}, tableName: {}", catalog, schema, tableName);
            final DatabaseMetaData meta = connection.getMetaData();
            try (final ResultSet tables = meta.getTables(
                    catalog, schema, tableName, null)) {
//                boolean found = false;
//                while (tables.next()) {
//                    found = true;
//                    final String tblCat = tables.getString("TABLE_CAT");
//                    final String tblSchema = tables.getString("TABLE_SCHEM");
//                    final String tblName = tables.getString("TABLE_NAME");
//                    LOGGER.info("tblCat: {}, tblSchema: {}, tableName: {}", tblCat, tblSchema, tblName);
//                }
//                return found;
                return tables.next();
            }
        } catch (final SQLException e) {
            throw new RuntimeException(
                    LogUtil.message("Error establishing if table {} exists in the database.", tableName), e);
        }
    }

    public static void renameTable(final Connection connection,
                                   final String oldTableName,
                                   final String newTableName) {
        renameTable(connection, oldTableName, newTableName);
    }

    public static void renameTable(final Connection connection,
                                   final String oldTableName,
                                   final String newTableName,
                                   final boolean isIdempotent) {

        if (doesTableExist(connection, oldTableName)) {
            final String sql = "RENAME TABLE " + oldTableName + " TO " + newTableName;
            try {
                try (final PreparedStatement stmt = connection.prepareStatement(sql)) {
                    stmt.execute();
                }
            } catch (final SQLException e) {
                throw new RuntimeException(
                        LogUtil.message("Error renaming table {} to {}.", oldTableName, newTableName), e);
            }
        } else {
            if (!isIdempotent) {
                throw new RuntimeException(LogUtil.message("Expecting table {} to exist", oldTableName));
            }
        }
    }

    public static Byte getByte(final ResultSet rs, final String columnLabel) throws SQLException {
        final byte val = rs.getByte(columnLabel);
        return rs.wasNull()
                ? null
                : val;
    }

    public static Byte getByte(final ResultSet rs, final int columnIndex) throws SQLException {
        final byte val = rs.getByte(columnIndex);
        return rs.wasNull()
                ? null
                : val;
    }

    public static Short getShort(final ResultSet rs, final String columnLabel) throws SQLException {
        final short val = rs.getShort(columnLabel);
        return rs.wasNull()
                ? null
                : val;
    }

    public static Short getShort(final ResultSet rs, final int columnIndex) throws SQLException {
        final short val = rs.getShort(columnIndex);
        return rs.wasNull()
                ? null
                : val;
    }

    public static Float getFloat(final ResultSet rs, final String columnLabel) throws SQLException {
        final float val = rs.getFloat(columnLabel);
        return rs.wasNull()
                ? null
                : val;
    }

    public static Float getFloat(final ResultSet rs, final int columnIndex) throws SQLException {
        final float val = rs.getFloat(columnIndex);
        return rs.wasNull()
                ? null
                : val;
    }

    public static Double getDouble(final ResultSet rs, final String columnLabel) throws SQLException {
        final double val = rs.getDouble(columnLabel);
        return rs.wasNull()
                ? null
                : val;
    }

    public static Double getDouble(final ResultSet rs, final int columnIndex) throws SQLException {
        final double val = rs.getDouble(columnIndex);
        return rs.wasNull()
                ? null
                : val;
    }

    public static Integer getInteger(final ResultSet rs, final String columnLabel) throws SQLException {
        final int val = rs.getInt(columnLabel);
        return rs.wasNull()
                ? null
                : val;
    }

    public static Integer getInteger(final ResultSet rs, final int columnIndex) throws SQLException {
        final int val = rs.getInt(columnIndex);
        return rs.wasNull()
                ? null
                : val;
    }

    public static Long getLong(final ResultSet rs, final String columnLabel) throws SQLException {
        final long val = rs.getLong(columnLabel);
        return rs.wasNull()
                ? null
                : val;
    }

    public static Long getLong(final ResultSet rs, final int columnIndex) throws SQLException {
        final long val = rs.getLong(columnIndex);
        return rs.wasNull()
                ? null
                : val;
    }

    public static void setByte(final PreparedStatement ps,
                               final int parameterIndex,
                               final Byte val) throws SQLException {
        if (val == null) {
            ps.setNull(parameterIndex, Types.TINYINT);
        } else {
            ps.setByte(parameterIndex, val);
        }
    }

    public static void setInteger(final PreparedStatement ps,
                                  final int parameterIndex,
                                  final Integer val) throws SQLException {
        if (val == null) {
            ps.setNull(parameterIndex, Types.INTEGER);
        } else {
            ps.setInt(parameterIndex, val);
        }
    }

    public static void setLong(final PreparedStatement ps,
                               final int columnIndex,
                               final Long val) throws SQLException {
        if (val == null) {
            ps.setNull(columnIndex, Types.BIGINT);
        } else {
            ps.setLong(columnIndex, val);
        }
    }
}
