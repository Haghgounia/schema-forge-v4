package com.behsazan.schemaforge.metadata.repository;

import org.springframework.jdbc.CannotGetJdbcConnectionException;

import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Classifies failures that mean an optional metadata database connection is unavailable. */
public final class MetadataConnectionFailureClassifier {
    private MetadataConnectionFailureClassifier() {
    }

    public static boolean isConnectionFailure(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = throwable;
        while (current != null && seen.add(current)) {
            if (current instanceof CannotGetJdbcConnectionException
                    || current instanceof SQLTransientConnectionException
                    || current instanceof SQLNonTransientConnectionException
                    || current instanceof SQLRecoverableException
                    || current instanceof SQLInvalidAuthorizationSpecException
                    || current instanceof ConnectException
                    || current instanceof SocketTimeoutException
                    || current instanceof SocketException) {
                return true;
            }
            if (current instanceof SQLException sqlException
                    && isConnectionSqlState(sqlException.getSQLState())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isConnectionSqlState(String sqlState) {
        if (sqlState == null || sqlState.length() < 2) {
            return false;
        }
        String sqlStateClass = sqlState.substring(0, 2);
        // 08 = connection exception, 28 = invalid authorization specification.
        return "08".equals(sqlStateClass) || "28".equals(sqlStateClass);
    }
}
