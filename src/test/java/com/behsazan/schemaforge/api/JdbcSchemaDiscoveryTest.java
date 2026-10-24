package com.behsazan.schemaforge.api;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JdbcSchemaDiscoveryTest {
    @Test void jdbcSchemasIncludeDefaultAndDeduplicateCaseInsensitively() throws Exception {
        Connection conn = mock(Connection.class);
        DatabaseMetaData meta = mock(DatabaseMetaData.class);
        ResultSet rows = mock(ResultSet.class);
        when(conn.getSchema()).thenReturn("APP");
        when(meta.getSchemas()).thenReturn(rows);
        when(rows.next()).thenReturn(true, true, true, false);
        when(rows.getString("TABLE_SCHEM")).thenReturn("public", "app", "BANK");
        var found = JdbcSchemaDiscovery.inspect(conn, meta, "postgresql");
        assertTrue(found.metadataReadable());
        assertEquals("APP", found.defaultSchema());
        assertEquals(List.of("app", "BANK", "public"), found.availableSchemas());
        assertFalse(found.truncated());
        verify(meta, never()).getCatalogs();
        verify(rows).close();
    }

    @Test void mysqlAndMariaDbEnumerateCatalogsNotJdbcSchemas() throws Exception {
        for (String platform : List.of("mysql", "mariadb")) {
            Connection conn = mock(Connection.class);
            DatabaseMetaData meta = mock(DatabaseMetaData.class);
            ResultSet rows = mock(ResultSet.class);
            when(conn.getCatalog()).thenReturn("test_db");
            when(meta.getCatalogs()).thenReturn(rows);
            when(rows.next()).thenReturn(true, true, false);
            when(rows.getString("TABLE_CAT")).thenReturn("test_db", "other_db");
            var found = JdbcSchemaDiscovery.inspect(conn, meta, platform);
            assertEquals("test_db", found.defaultSchema());
            assertEquals(List.of("other_db", "test_db"), found.availableSchemas());
            assertTrue(found.metadataReadable());
            verify(meta, never()).getSchemas();
        }
    }

    @Test void unsupportedEnumerationIsNotMistakenForVerifiedMetadataPrivileges() throws Exception {
        Connection conn = mock(Connection.class);
        DatabaseMetaData meta = mock(DatabaseMetaData.class);
        when(conn.getSchema()).thenThrow(new SQLException("unsupported"));
        when(meta.getSchemas()).thenThrow(new SQLException("unsupported"));
        var result = JdbcSchemaDiscovery.inspect(conn, meta, "oracle");
        assertFalse(result.metadataReadable());
        assertTrue(result.availableSchemas().isEmpty());
        assertEquals("", result.defaultSchema());
    }

    @Test void discoveryIsBoundedButOffersCurrentDefaultEvenIfNotInFirst200() throws Exception {
        Connection conn = mock(Connection.class);
        DatabaseMetaData meta = mock(DatabaseMetaData.class);
        ResultSet rows = mock(ResultSet.class);
        when(conn.getSchema()).thenReturn("CURRENT_SCHEMA");
        when(meta.getSchemas()).thenReturn(rows);
        AtomicInteger count = new AtomicInteger();
        when(rows.next()).thenAnswer(unused -> count.getAndIncrement() <= JdbcSchemaDiscovery.MAX_NAMES);
        when(rows.getString("TABLE_SCHEM")).thenAnswer(unused -> "S" + count.get());
        var result = JdbcSchemaDiscovery.inspect(conn, meta, "db2luw");
        assertTrue(result.truncated());
        assertTrue(result.metadataReadable());
        assertEquals(JdbcSchemaDiscovery.MAX_NAMES, result.availableSchemas().size());
        assertTrue(result.availableSchemas().contains("CURRENT_SCHEMA"));
    }
}
