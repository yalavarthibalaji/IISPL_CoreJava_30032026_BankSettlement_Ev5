package com.iispl.banksettlement.dao.impl;

import com.iispl.banksettlement.dao.SettlementBatchDao;
import com.iispl.banksettlement.entity.SettlementBatch;
import com.iispl.banksettlement.entity.SettlementRecord;
import com.iispl.banksettlement.enums.BatchStatus;
import com.iispl.banksettlement.enums.SettlementStatus;
import com.iispl.connectionpool.ConnectionPool;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * SettlementBatchDaoImpl — JDBC implementation of SettlementBatchDao.
 *
 * Handles two DB tables:
 *   1. settlement_batch  — one row per channel batch
 *   2. settlement_record — one row per transaction settled inside a batch
 *
 * HOW THE ENGINE USES THIS:
 *   Step 1 → saveBatch()    → INSERT into settlement_batch  (status=RUNNING)
 *   Step 2 → saveRecord()   → INSERT into settlement_record (one per txn)
 *   Step 3 → updateBatch()  → UPDATE settlement_batch totals + final status
 *
 * DB TABLE COLUMNS:
 *
 *   settlement_batch:
 *     batch_id, batch_date, batch_status, total_transactions,
 *     total_amount, run_by, run_at, created_at, updated_at, created_by, version
 *
 *   settlement_record:
 *     record_id(serial), batch_id, incoming_txn_id, settled_amount,
 *     settled_date, settled_status, failure_reason,
 *     created_at, updated_at, created_by, version
 *
 * PACKAGE: com.iispl.banksettlement.dao.impl
 */
public class SettlementBatchDaoImpl implements SettlementBatchDao {

    // -----------------------------------------------------------------------
    // SQL — settlement_batch table
    // -----------------------------------------------------------------------

    private static final String SQL_INSERT_BATCH =
            "INSERT INTO settlement_batch " +
            "(batch_id, batch_date, batch_status, total_transactions, total_amount, " +
            " run_by, run_at, created_at, updated_at, created_by, version) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    // Updates totals and final status after all transactions are processed
    private static final String SQL_UPDATE_BATCH =
            "UPDATE settlement_batch " +
            "SET batch_status = ?, total_transactions = ?, total_amount = ?, updated_at = ? " +
            "WHERE batch_id = ?";

    private static final String SQL_FIND_BATCH_BY_ID =
            "SELECT * FROM settlement_batch WHERE batch_id = ?";

    // -----------------------------------------------------------------------
    // SQL — settlement_record table
    // -----------------------------------------------------------------------

    private static final String SQL_INSERT_RECORD =
            "INSERT INTO settlement_record " +
            "(batch_id, incoming_txn_id, settled_amount, settled_date, " +
            " settled_status, failure_reason, created_at, updated_at, created_by, version) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String SQL_FIND_RECORDS_BY_BATCH =
            "SELECT * FROM settlement_record WHERE batch_id = ? ORDER BY record_id ASC";

    // -----------------------------------------------------------------------
    // saveBatch() — INSERT one row into settlement_batch
    // -----------------------------------------------------------------------

    @Override
    public void saveBatch(SettlementBatch batch) {
        Connection conn = null;
        PreparedStatement ps = null;
        try {
            conn = ConnectionPool.getConnection();
            ps   = conn.prepareStatement(SQL_INSERT_BATCH);

            ps.setString(1,     batch.getBatchId());
            ps.setDate(2,       Date.valueOf(batch.getBatchDate()));
            ps.setString(3,     batch.getBatchStatus().name());
            ps.setInt(4,        batch.getTotalTransactions());
            ps.setBigDecimal(5, batch.getTotalAmount());
            ps.setString(6,     batch.getRunBy());

            LocalDateTime runAt = (batch.getRunAt() != null)
                    ? batch.getRunAt() : LocalDateTime.now();
            ps.setTimestamp(7,  Timestamp.valueOf(runAt));

            LocalDateTime now = LocalDateTime.now();
            ps.setTimestamp(8,  Timestamp.valueOf(now));  // created_at
            ps.setTimestamp(9,  Timestamp.valueOf(now));  // updated_at
            ps.setString(10,    "SETTLEMENT_ENGINE");
            ps.setInt(11,       0);                       // version

            ps.executeUpdate();

            System.out.println("[SettlementBatchDaoImpl] Batch saved | batchId: "
                    + batch.getBatchId() + " | status: " + batch.getBatchStatus());

        } catch (SQLException e) {
            throw new RuntimeException(
                "SettlementBatchDaoImpl.saveBatch() failed for batchId: "
                + batch.getBatchId() + " | " + e.getMessage(), e);
        } finally {
            closeResources(null, ps, conn);
        }
    }

    // -----------------------------------------------------------------------
    // updateBatch() — UPDATE totals + final status
    // -----------------------------------------------------------------------

    @Override
    public void updateBatch(SettlementBatch batch) {
        Connection conn = null;
        PreparedStatement ps = null;
        try {
            conn = ConnectionPool.getConnection();
            ps   = conn.prepareStatement(SQL_UPDATE_BATCH);

            ps.setString(1,     batch.getBatchStatus().name());
            ps.setInt(2,        batch.getTotalTransactions());
            ps.setBigDecimal(3, batch.getTotalAmount());
            ps.setTimestamp(4,  Timestamp.valueOf(LocalDateTime.now()));
            ps.setString(5,     batch.getBatchId());

            int rows = ps.executeUpdate();
            if (rows == 0) {
                throw new RuntimeException(
                    "SettlementBatchDaoImpl.updateBatch() — no row found for batchId: "
                    + batch.getBatchId());
            }

            System.out.println("[SettlementBatchDaoImpl] Batch updated | batchId: "
                    + batch.getBatchId()
                    + " | finalStatus: " + batch.getBatchStatus()
                    + " | totalTxns: "   + batch.getTotalTransactions()
                    + " | totalAmt: "    + batch.getTotalAmount());

        } catch (SQLException e) {
            throw new RuntimeException(
                "SettlementBatchDaoImpl.updateBatch() failed for batchId: "
                + batch.getBatchId() + " | " + e.getMessage(), e);
        } finally {
            closeResources(null, ps, conn);
        }
    }

    // -----------------------------------------------------------------------
    // findBatchById()
    // -----------------------------------------------------------------------

    @Override
    public SettlementBatch findBatchById(String batchId) {
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = ConnectionPool.getConnection();
            ps   = conn.prepareStatement(SQL_FIND_BATCH_BY_ID);
            ps.setString(1, batchId);
            rs = ps.executeQuery();
            if (rs.next()) return mapBatchRow(rs);
            return null;
        } catch (SQLException e) {
            throw new RuntimeException(
                "SettlementBatchDaoImpl.findBatchById() failed: " + e.getMessage(), e);
        } finally {
            closeResources(rs, ps, conn);
        }
    }

    // -----------------------------------------------------------------------
    // saveRecord() — INSERT one row into settlement_record
    // -----------------------------------------------------------------------

    @Override
    public void saveRecord(SettlementRecord record) {
        Connection conn = null;
        PreparedStatement ps = null;
        try {
            conn = ConnectionPool.getConnection();
            ps   = conn.prepareStatement(SQL_INSERT_RECORD, Statement.RETURN_GENERATED_KEYS);

            ps.setString(1,     record.getBatchId());
            ps.setLong(2,       record.getIncomingTxnId());
            ps.setBigDecimal(3, record.getSettledAmount());

            LocalDateTime settledDate = (record.getSettledDate() != null)
                    ? record.getSettledDate() : LocalDateTime.now();
            ps.setTimestamp(4,  Timestamp.valueOf(settledDate));

            ps.setString(5,     record.getSettledStatus().name());
            // failure_reason is null for successful settlements — setString handles null correctly
            ps.setString(6,     record.getFailureReason());

            LocalDateTime now = LocalDateTime.now();
            ps.setTimestamp(7,  Timestamp.valueOf(now));  // created_at
            ps.setTimestamp(8,  Timestamp.valueOf(now));  // updated_at
            ps.setString(9,     "SETTLEMENT_ENGINE");
            ps.setInt(10,       0);                       // version

            int rows = ps.executeUpdate();
            if (rows == 0) {
                throw new RuntimeException(
                    "SettlementBatchDaoImpl.saveRecord() — no row inserted for batchId: "
                    + record.getBatchId());
            }

            // Set back the DB-generated record_id
            ResultSet keys = ps.getGeneratedKeys();
            if (keys.next()) {
                record.setRecordId(keys.getLong(1));
            }
            keys.close();

            System.out.println("[SettlementBatchDaoImpl] Record saved | recordId: "
                    + record.getRecordId()
                    + " | batchId: "       + record.getBatchId()
                    + " | incomingTxnId: " + record.getIncomingTxnId()
                    + " | status: "        + record.getSettledStatus()
                    + " | amount: "        + record.getSettledAmount());

        } catch (SQLException e) {
            throw new RuntimeException(
                "SettlementBatchDaoImpl.saveRecord() failed: " + e.getMessage(), e);
        } finally {
            closeResources(null, ps, conn);
        }
    }

    // -----------------------------------------------------------------------
    // findRecordsByBatchId()
    // -----------------------------------------------------------------------

    @Override
    public List<SettlementRecord> findRecordsByBatchId(String batchId) {
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        List<SettlementRecord> records = new ArrayList<>();
        try {
            conn = ConnectionPool.getConnection();
            ps   = conn.prepareStatement(SQL_FIND_RECORDS_BY_BATCH);
            ps.setString(1, batchId);
            rs = ps.executeQuery();
            while (rs.next()) {
                records.add(mapRecordRow(rs));
            }
            return records;
        } catch (SQLException e) {
            throw new RuntimeException(
                "SettlementBatchDaoImpl.findRecordsByBatchId() failed: " + e.getMessage(), e);
        } finally {
            closeResources(rs, ps, conn);
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers — map ResultSet rows to objects
    // -----------------------------------------------------------------------

    private SettlementBatch mapBatchRow(ResultSet rs) throws SQLException {
        SettlementBatch batch = new SettlementBatch();
        batch.setBatchId(rs.getString("batch_id"));
        batch.setBatchDate(rs.getDate("batch_date").toLocalDate());
        batch.setBatchStatus(BatchStatus.valueOf(rs.getString("batch_status")));
        batch.setTotalTransactions(rs.getInt("total_transactions"));
        batch.setTotalAmount(rs.getBigDecimal("total_amount"));
        batch.setRunBy(rs.getString("run_by"));
        Timestamp runAt = rs.getTimestamp("run_at");
        if (runAt != null) batch.setRunAt(runAt.toLocalDateTime());
        return batch;
    }

    private SettlementRecord mapRecordRow(ResultSet rs) throws SQLException {
        SettlementRecord rec = new SettlementRecord();
        rec.setRecordId(rs.getLong("record_id"));
        rec.setBatchId(rs.getString("batch_id"));
        rec.setIncomingTxnId(rs.getLong("incoming_txn_id"));
        rec.setSettledAmount(rs.getBigDecimal("settled_amount"));
        Timestamp sd = rs.getTimestamp("settled_date");
        if (sd != null) rec.setSettledDate(sd.toLocalDateTime());
        rec.setSettledStatus(SettlementStatus.valueOf(rs.getString("settled_status")));
        rec.setFailureReason(rs.getString("failure_reason"));
        return rec;
    }

    // -----------------------------------------------------------------------
    // Private helpers — close JDBC resources safely
    // -----------------------------------------------------------------------

    private void closeResources(ResultSet rs, PreparedStatement ps, Connection conn) {
        try { if (rs   != null) rs.close();   } catch (SQLException ignored) {}
        try { if (ps   != null) ps.close();   } catch (SQLException ignored) {}
        try { if (conn != null) conn.close(); } catch (SQLException ignored) {}
    }
}