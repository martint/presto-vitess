/*
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
package io.prestosql.plugin.vitess;

import com.google.common.collect.ImmutableSet;
import com.mysql.jdbc.Driver;
import com.mysql.jdbc.Statement;
import io.airlift.log.Logger;
import io.prestosql.plugin.jdbc.BaseJdbcClient;
import io.prestosql.plugin.jdbc.BaseJdbcConfig;
import io.prestosql.plugin.jdbc.ConnectionFactory;
import io.prestosql.plugin.jdbc.DriverConnectionFactory;
import io.prestosql.plugin.jdbc.JdbcColumnHandle;
import io.prestosql.plugin.jdbc.JdbcSplit;
import io.prestosql.plugin.jdbc.JdbcTableHandle;
import io.prestosql.plugin.jdbc.QueryBuilder;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.connector.SchemaTableName;
import io.prestosql.spi.type.Type;
import io.prestosql.spi.type.VarcharType;

import javax.annotation.Nullable;
import javax.inject.Inject;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static io.prestosql.plugin.jdbc.DriverConnectionFactory.basicConnectionProperties;
import static io.prestosql.plugin.jdbc.JdbcErrorCode.JDBC_ERROR;
import static io.prestosql.spi.StandardErrorCode.NOT_SUPPORTED;
import static io.prestosql.spi.type.RealType.REAL;
import static io.prestosql.spi.type.TimeWithTimeZoneType.TIME_WITH_TIME_ZONE;
import static io.prestosql.spi.type.TimestampType.TIMESTAMP;
import static io.prestosql.spi.type.TimestampWithTimeZoneType.TIMESTAMP_WITH_TIME_ZONE;
import static io.prestosql.spi.type.VarbinaryType.VARBINARY;
import static io.prestosql.spi.type.Varchars.isVarcharType;
import static java.util.Locale.ENGLISH;

public class VitessClient
        extends BaseJdbcClient
{
    private String vttabletSchemaName;
    private static final Logger log = Logger.get(BaseJdbcClient.class);

    @Inject
    public VitessClient(BaseJdbcConfig config, VitessConfig vitessConfig)
            throws SQLException
    {
        super(config, "`", connectionFactory(config, vitessConfig));
        this.vttabletSchemaName = String.valueOf(vitessConfig.getVttabletSchemaName());
    }

    private static ConnectionFactory connectionFactory(BaseJdbcConfig config, VitessConfig vitessConfig)
            throws SQLException
    {
        Properties connectionProperties = basicConnectionProperties(config);
        connectionProperties.setProperty("useInformationSchema", "true");
        connectionProperties.setProperty("nullCatalogMeansCurrent", "false");
        connectionProperties.setProperty("useUnicode", "true");
        connectionProperties.setProperty("characterEncoding", "utf8");
        connectionProperties.setProperty("tinyInt1isBit", "false");
        if (vitessConfig.isAutoReconnect()) {
            connectionProperties.setProperty("autoReconnect", String.valueOf(vitessConfig.isAutoReconnect()));
            connectionProperties.setProperty("maxReconnects", String.valueOf(vitessConfig.getMaxReconnects()));
        }
        if (vitessConfig.getConnectionTimeout() != null) {
            connectionProperties.setProperty("connectTimeout", String.valueOf(vitessConfig.getConnectionTimeout().toMillis()));
        }

        return new DriverConnectionFactory(new Driver(), config.getConnectionUrl(), connectionProperties);
    }

    @Override
    public Set<String> getSchemaNames()
    {
        try (Connection connection = connectionFactory.openConnection();
                ResultSet resultSet = connection.getMetaData().getCatalogs()) {
            ImmutableSet.Builder<String> schemaNames = ImmutableSet.builder();
            while (resultSet.next()) {
                String schemaName = resultSet.getString("TABLE_CAT").toLowerCase(ENGLISH);
                // skip internal schemas
                if (!schemaName.equals("information_schema") && !schemaName.equals("performance_schema") && !schemaName.equals("mysql")) {
                    schemaNames.add(schemaName);
                }
                schemaNames.add(schemaName);
            }
            return schemaNames.build();
        }
        catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void abortReadConnection(Connection connection)
            throws SQLException
    {
        // Abort connection before closing. Without this, the MySQL driver
        // attempts to drain the connection by reading all the results.
        connection.abort(directExecutor());
    }

    @Override
    public PreparedStatement getPreparedStatement(Connection connection, String sql)
            throws SQLException
    {
        PreparedStatement statement = connection.prepareStatement(sql);
        if (statement.isWrapperFor(Statement.class)) {
            statement.unwrap(Statement.class).enableStreamingResults();
        }
        return statement;
    }

    @Override
    protected ResultSet getTables(Connection connection, String schemaName, String tableName)
            throws SQLException
    {
        DatabaseMetaData metadata = connection.getMetaData();
        String vttabletSchemaName = this.vttabletSchemaName;
        String escape = metadata.getSearchStringEscape();
        return metadata.getTables(
                vttabletSchemaName,
                null,
                escapeNamePattern(Optional.of(tableName), Optional.of(escape)).get(),
                new String[] {"TABLE", "VIEW"});
    }

    @Override
    protected SchemaTableName getSchemaTableName(ResultSet resultSet)
            throws SQLException
    {
        return new SchemaTableName(
                resultSet.getString("TABLE_CAT").toLowerCase(ENGLISH),
                resultSet.getString("TABLE_NAME").toLowerCase(ENGLISH));
    }

    @Nullable
    @Override
    public JdbcTableHandle getTableHandle(SchemaTableName schemaTableName)
    {
        try (Connection connection = connectionFactory.openConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            String jdbcSchemaName = this.vttabletSchemaName;
            String jdbcTableName = schemaTableName.getTableName();

            log.info(vttabletSchemaName + " " + jdbcSchemaName);

            if (metadata.storesUpperCaseIdentifiers()) {
                jdbcSchemaName = jdbcSchemaName.toUpperCase(ENGLISH);
                jdbcTableName = jdbcTableName.toUpperCase(ENGLISH);
            }
            try (ResultSet resultSet = getTables(connection, jdbcSchemaName, jdbcTableName)) {
                List<JdbcTableHandle> tableHandles = new ArrayList<>();

                while (resultSet.next()) {
                    tableHandles.add(new JdbcTableHandle(
                            schemaTableName,
                            jdbcSchemaName,
                            null,
                            resultSet.getString("TABLE_NAME")));
                }
                if (tableHandles.isEmpty()) {
                    return null;
                }
                if (tableHandles.size() > 1) {
                    throw new PrestoException(NOT_SUPPORTED, "Multiple tables matched: " + schemaTableName);
                }
                return getOnlyElement(tableHandles);
            }
        }
        catch (SQLException e) {
            throw new PrestoException(JDBC_ERROR, e);
        }
    }

    @Override
    public PreparedStatement buildSql(Connection connection, JdbcSplit split, List<JdbcColumnHandle> columnHandles)
            throws SQLException
    {
        return new QueryBuilder(identifierQuote).buildSql(
                this,
                connection,
                null,
                split.getSchemaName(),
                split.getTableName(),
                columnHandles,
                split.getTupleDomain());
    }

    @Override
    protected String toSqlType(Type type)
    {
        if (REAL.equals(type)) {
            return "float";
        }
        if (TIME_WITH_TIME_ZONE.equals(type) || TIMESTAMP_WITH_TIME_ZONE.equals(type)) {
            throw new PrestoException(NOT_SUPPORTED, "Unsupported column type: " + type.getDisplayName());
        }
        if (TIMESTAMP.equals(type)) {
            return "datetime";
        }
        if (VARBINARY.equals(type)) {
            return "mediumblob";
        }
        if (isVarcharType(type)) {
            VarcharType varcharType = (VarcharType) type;
            if (varcharType.isUnbounded()) {
                return "longtext";
            }
            if (varcharType.getBoundedLength() <= 255) {
                return "tinytext";
            }
            if (varcharType.getBoundedLength() <= 65535) {
                return "text";
            }
            if (varcharType.getBoundedLength() <= 16777215) {
                return "mediumtext";
            }
            return "longtext";
        }

        return super.toSqlType(type);
    }
}
