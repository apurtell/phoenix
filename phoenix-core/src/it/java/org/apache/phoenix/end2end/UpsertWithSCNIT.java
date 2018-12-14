package org.apache.phoenix.end2end;

import org.apache.phoenix.exception.SQLExceptionCode;
import org.apache.phoenix.exception.SQLExceptionInfo;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;


public class UpsertWithSCNIT extends ParallelStatsDisabledIT {

    @Rule
    public final ExpectedException exception = ExpectedException.none();
    Properties props = null;
    PreparedStatement prep = null;
    String tableName =null;

    private void helpTestUpserWithSCNIT(boolean rowColumn, boolean txTable,
                                        boolean mutable, boolean local, boolean global)
            throws SQLException {

        tableName = generateUniqueName();
        String indx;
        String createTable = "CREATE TABLE "+tableName+" ("
                + (rowColumn ? "CREATED_DATE DATE NOT NULL, ":"")
                + "METRIC_ID CHAR(15) NOT NULL,METRIC_VALUE VARCHAR(50) CONSTRAINT PK PRIMARY KEY("
                + (rowColumn? "CREATED_DATE ROW_TIMESTAMP, ":"") + "METRIC_ID)) "
                + (mutable? "IMMUTABLE_ROWS=false":"" )
                + (txTable ? "TRANSACTION_PROVIDER='TEPHRA',TRANSACTIONAL=true":"");
        props = new Properties();
        Connection conn = DriverManager.getConnection(getUrl(), props);
        conn.createStatement().execute(createTable);

        if(local || global ){
            indx = "CREATE "+ (local? "LOCAL " : "") + "INDEX "+tableName+"_idx ON " +
                    ""+tableName+" (METRIC_VALUE)";
            conn.createStatement().execute(indx);
        }

        props.setProperty("CurrentSCN", Long.toString(System.currentTimeMillis()));
        conn = DriverManager.getConnection(getUrl(), props);
        conn.setAutoCommit(true);
        String upsert = "UPSERT INTO "+tableName+" (METRIC_ID, METRIC_VALUE) VALUES (?,?)";
        prep = conn.prepareStatement(upsert);
        prep.setString(1,"abc");
        prep.setString(2,"This is the first comment!");
    }

    @Test // See https://issues.apache.org/jira/browse/PHOENIX-4983
    public void testUpsertOnSCNSetTxnTable() throws SQLException {

        helpTestUpserWithSCNIT(false, true, false, false, false);
        exception.expect(SQLException.class);
        exception.expectMessage(containsString(String.valueOf(
                SQLExceptionCode
                .CANNOT_SPECIFY_SCN_FOR_TXN_TABLE
                .getErrorCode())));
        prep.executeUpdate();
    }

    @Test
    public void testUpsertOnSCNSetMutTableWithoutIdx() throws Exception {

        helpTestUpserWithSCNIT(false, false, true, false, false);
        prep.executeUpdate();
        props = new Properties();
        Connection conn = DriverManager.getConnection(getUrl(),props);
        ResultSet rs = conn.createStatement().executeQuery("SELECT * FROM "+tableName);
        assertTrue(rs.next());
        assertEquals("abc", rs.getString(1));
        assertEquals("This is the first comment!", rs.getString(2));
        assertFalse(rs.next());
    }

    @Test
    public void testUpsertOnSCNSetTable() throws Exception {

        helpTestUpserWithSCNIT(false, false, false, false, false);
        prep.executeUpdate();
        props = new Properties();
        Connection conn = DriverManager.getConnection(getUrl(),props);
        ResultSet rs = conn.createStatement().executeQuery("SELECT * FROM "+tableName);
        assertTrue(rs.next());
        assertEquals("abc", rs.getString(1));
        assertEquals("This is the first comment!", rs.getString(2));
        assertFalse(rs.next());
    }

    @Test
    public void testUpsertOnSCNSetMutTableWithLocalIdx() throws Exception {

        helpTestUpserWithSCNIT(false, false, true, true, false);
        exception.expect(SQLException.class);
        exception.expectMessage(containsString(String.valueOf(
                SQLExceptionCode
                .CANNOT_UPSERT_WITH_SCN_FOR_MUTABLE_TABLE_WITH_INDEXES
                .getErrorCode())));
        prep.executeUpdate();
    }
    @Test
    public void testUpsertOnSCNSetMutTableWithGlobalIdx() throws Exception {

        helpTestUpserWithSCNIT(false, false, true, false, true);
        exception.expect(SQLException.class);
        exception.expectMessage(containsString(String.valueOf(
                SQLExceptionCode
                        .CANNOT_UPSERT_WITH_SCN_FOR_MUTABLE_TABLE_WITH_INDEXES
                        .getErrorCode())));
        prep.executeUpdate();

    }
    @Test
    public void testUpsertOnSCNSetWithRowTSColumn() throws Exception {

        helpTestUpserWithSCNIT(true, false, false, false, false);
        exception.expect(SQLException.class);
        exception.expectMessage(containsString(String.valueOf(
                SQLExceptionCode
                        .CANNOT_UPSERT_WITH_SCN_FOR_ROW_TIMSTAMP_COLUMN
                        .getErrorCode())));
        prep.executeUpdate();
    }
}
