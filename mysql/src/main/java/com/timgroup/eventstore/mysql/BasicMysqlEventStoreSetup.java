package com.timgroup.eventstore.mysql;

import javax.annotation.ParametersAreNonnullByDefault;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static java.util.Objects.requireNonNull;

@ParametersAreNonnullByDefault
public class BasicMysqlEventStoreSetup {
    private final ConnectionProvider connectionProvider;
    private final String tableName;

    public BasicMysqlEventStoreSetup(ConnectionProvider connectionProvider, String tableName) {
        this.connectionProvider = requireNonNull(connectionProvider);
        this.tableName = requireNonNull(tableName);
    }

    public void drop() {
        try (Connection connection = connectionProvider.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("drop table if exists " + tableName);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void create() {
        create(false);
    }

    public void lazyCreate() {
        create(true);
    }

    private void create(boolean ifNotExists) {
        try (Connection connection = connectionProvider.getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            if (ifNotExists) {
                String searchStringEscape = meta.getSearchStringEscape();
                String escapedTableName = tableName.replace("_", searchStringEscape + "_").replace("%", searchStringEscape + "%");
                try(ResultSet res = meta.getTables(null, null, escapedTableName, new String[]{"TABLE", "VIEW"})) {
                    if (res.first()) {
                        return;
                    }
                }
            }

            try (Statement statement = connection.createStatement()) {
                statement.execute("create table " + (ifNotExists ? "if not exists" : "") + " " + tableName + "(" +
                        "position bigint primary key, " +
                        timestampColumnDefinition(meta) +
                        "stream_category varchar(255) not null, " +
                        "stream_id varchar(255) not null, " +
                        "event_number bigint not null, " +
                        "event_type varchar(255) not null," +
                        "data mediumblob not null, " +
                        "metadata blob not null," +
                        "unique stream_category(stream_category, stream_id, event_number)," +
                        "key stream_category_2(stream_category, position)" +
                        ") row_format=DYNAMIC");
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // You can only specify the fractional second precision of a DATETIME column starting with MySql 5.5
    // (compare https://dev.mysql.com/doc/refman/5.5/en/datetime.html and
    // https://docs.oracle.com/cd/E19078-01/mysql/mysql-refman-5.1/data-types.html#datetime).
    protected static boolean mysqlSupportsFractionalSecondsForDatetime(DatabaseMetaData meta) throws SQLException {
        return (meta.getDatabaseMajorVersion() == 5 && meta.getDatabaseMinorVersion() >= 5)
                || meta.getDatabaseMajorVersion() > 5;
    }

    private static String timestampColumnDefinition(DatabaseMetaData meta) throws SQLException {
        return mysqlSupportsFractionalSecondsForDatetime(meta)
                ? "timestamp datetime(6) not null, "
                : "timestamp datetime not null, ";
    }

}
