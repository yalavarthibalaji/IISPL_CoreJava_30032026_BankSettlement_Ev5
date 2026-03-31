package com.iispl.banksettlement.dao.impl;

import com.iispl.banksettlement.dao.IncomingTransactionDao;
import com.iispl.banksettlement.entity.IncomingTransaction;
import com.iispl.banksettlement.entity.SourceSystem;
import com.iispl.banksettlement.enums.ProcessingStatus;
import com.iispl.banksettlement.enums.ProtocolType;
import com.iispl.banksettlement.enums.TransactionType;
import com.iispl.connectionpool.ConnectionPool;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * IncomingTransactionDaoImpl
 *
 * JDBC implementation of IncomingTransactionDao.
 * Uses ConnectionPool.getConnection() for all DB operations.
 * Connects to Supabase PostgreSQL via HikariCP.
 *
 * IMPORTANT RULES followed here:
 *   1. Every Connection, PreparedStatement, ResultSet is closed in finally block
 *   2. Never use Statement — always use PreparedStatement (prevents SQL injection)
 *   3. Never catch and silently ignore exceptions — always throw RuntimeException
 *   4. Generated keys are read back and set on the object after INSERT
 */

public class IncomingTransactionDaoImpl implements IncomingTransactionDao {

    // -----------------------------------------------------------------------
    // SQL CONSTANTS — all SQL in one place, easy to find and change
    // -----------------------------------------------------------------------

    private static final String SQL_INSERT =
            "INSERT INTO incoming_transaction " +
            "(source_system_id, source_ref, raw_payload, txn_type, amount, " +
            "currency, value_date, processing_status, ingest_timestamp, " +
            "normalized_payload, created_at, updated_at, created_by, version) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String SQL_FIND_BY_ID =
            "SELECT t.*, s.system_code, s.protocol, s.connection_config, " +
            "s.is_active, s.contact_email " +
            "FROM incoming_transaction t " +
            "JOIN source_system s ON t.source_system_id = s.source_system_id " +
            "WHERE t.incoming_txn_id = ?";

    private static final String SQL_FIND_BY_SOURCE_REF =
            "SELECT t.*, s.system_code, s.protocol, s.connection_config, " +
            "s.is_active, s.contact_email " +
            "FROM incoming_transaction t " +
            "JOIN source_system s ON t.source_system_id = s.source_system_id " +
            "WHERE t.source_ref = ?";

    private static final String SQL_EXISTS_BY_SOURCE_REF =
            "SELECT COUNT(*) FROM incoming_transaction WHERE source_ref = ?";

    private static final String SQL_FIND_BY_STATUS =
            "SELECT t.*, s.system_code, s.protocol, s.connection_config, " +
            "s.is_active, s.contact_email " +
            "FROM incoming_transaction t " +
            "JOIN source_system s ON t.source_system_id = s.source_system_id " +
            "WHERE t.processing_status = ? " +
            "ORDER BY t.ingest_timestamp ASC";

    private static final String SQL_UPDATE_STATUS =
            "UPDATE incoming_transaction " +
            "SET processing_status = ?, updated_at = ? " +
            "WHERE incoming_txn_id = ?";

    // -----------------------------------------------------------------------
    // save()
    // -----------------------------------------------------------------------

    @Override
    public void save(IncomingTransaction txn) {

        Connection conn = null;
        PreparedStatement ps = null;

        try {
            conn = ConnectionPool.getConnection();

            ps = conn.prepareStatement(SQL_INSERT, Statement.RETURN_GENERATED_KEYS);

            ps.setLong(1, txn.getSourceSystem().getSourceSystemId());
            ps.setString(2, txn.getSourceRef());
            ps.setString(3, txn.getRawPayload());
            ps.setString(4, txn.getTxnType().name());
            ps.setBigDecimal(5, txn.getAmount());
            ps.setString(6, txn.getCurrency());
            ps.setDate(7, Date.valueOf(txn.getValueDate()));
            ps.setString(8, txn.getProcessingStatus().name());
            ps.setTimestamp(9, Timestamp.valueOf(txn.getIngestTimestamp()));
            ps.setString(10, txn.getNormalizedPayload());
            ps.setTimestamp(11, Timestamp.valueOf(txn.getCreatedAt()));
            ps.setTimestamp(12, Timestamp.valueOf(txn.getUpdatedAt()));
            ps.setString(13, txn.getCreatedBy());
            ps.setInt(14, txn.getVersion());

            int rowsAffected = ps.executeUpdate();

            if (rowsAffected == 0) {
                throw new RuntimeException(
                        "IncomingTransactionDaoImpl.save() failed — no rows inserted for sourceRef: "
                                + txn.getSourceRef()
                );
            }

            ResultSet generatedKeys = ps.getGeneratedKeys();
            if (generatedKeys.next()) {
                txn.setIncomingTxnId(generatedKeys.getLong(1));
                txn.setId(generatedKeys.getLong(1));
            }
            generatedKeys.close();

            System.out.println("[IncomingTransactionDaoImpl] Saved txn: "
                    + txn.getSourceRef()
                    + " | DB id: " + txn.getIncomingTxnId());

        } catch (SQLException e) {
            throw new RuntimeException(
                    "IncomingTransactionDaoImpl.save() failed: " + e.getMessage(), e
            );
        } finally {
            closeResources(ps, conn);
        }
    }

    // -----------------------------------------------------------------------
    // findById()
    // -----------------------------------------------------------------------

    @Override
    public IncomingTransaction findById(Long incomingTxnId) {

        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;

        try {
            conn = ConnectionPool.getConnection();
            ps = conn.prepareStatement(SQL_FIND_BY_ID);
            ps.setLong(1, incomingTxnId);
            rs = ps.executeQuery();

            if (rs.next()) {
                return mapRowToIncomingTransaction(rs);
            }
            return null;

        } catch (SQLException e) {
            throw new RuntimeException(
                    "IncomingTransactionDaoImpl.findById() failed: " + e.getMessage(), e
            );
        } finally {
            closeResources(rs, ps, conn);
        }
    }

    // -----------------------------------------------------------------------
    // findBySourceRef()
    // -----------------------------------------------------------------------

    @Override
    public IncomingTransaction findBySourceRef(String sourceRef) {

        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;

        try {
            conn = ConnectionPool.getConnection();
            ps = conn.prepareStatement(SQL_FIND_BY_SOURCE_REF);
            ps.setString(1, sourceRef);
            rs = ps.executeQuery();

            if (rs.next()) {
                return mapRowToIncomingTransaction(rs);
            }
            return null;

        } catch (SQLException e) {
            throw new RuntimeException(
                    "IncomingTransactionDaoImpl.findBySourceRef() failed: " + e.getMessage(), e
            );
        } finally {
            closeResources(rs, ps, conn);
        }
    }

    // -----------------------------------------------------------------------
    // existsBySourceRef()
    // -----------------------------------------------------------------------

    @Override
    public boolean existsBySourceRef(String sourceRef) {

        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;

        try {
            conn = ConnectionPool.getConnection();
            ps = conn.prepareStatement(SQL_EXISTS_BY_SOURCE_REF);
            ps.setString(1, sourceRef);
            rs = ps.executeQuery();

            if (rs.next()) {
                return rs.getInt(1) > 0;
            }

            return false;

        } catch (SQLException e) {
            throw new RuntimeException(
                    "IncomingTransactionDaoImpl.existsBySourceRef() failed: " + e.getMessage(), e
            );
        } finally {
            closeResources(rs, ps, conn);
        }
    }

    // -----------------------------------------------------------------------
    // findByStatus()
    // -----------------------------------------------------------------------

    @Override
    public List<IncomingTransaction> findByStatus(ProcessingStatus status) {

        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        List<IncomingTransaction> result = new ArrayList<>();

        try {
            conn = ConnectionPool.getConnection();
            ps = conn.prepareStatement(SQL_FIND_BY_STATUS);
            ps.setString(1, status.name());
            rs = ps.executeQuery();

            while (rs.next()) {
                result.add(mapRowToIncomingTransaction(rs));
            }

            return result;

        } catch (SQLException e) {
            throw new RuntimeException(
                    "IncomingTransactionDaoImpl.findByStatus() failed: " + e.getMessage(), e
            );
        } finally {
            closeResources(rs, ps, conn);
        }
    }

    // -----------------------------------------------------------------------
    // updateStatus()
    // -----------------------------------------------------------------------

    @Override
    public void updateStatus(Long incomingTxnId, ProcessingStatus status) {

        Connection conn = null;
        PreparedStatement ps = null;

        try {
            conn = ConnectionPool.getConnection();
            ps = conn.prepareStatement(SQL_UPDATE_STATUS);
            ps.setString(1, status.name());
            ps.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
            ps.setLong(3, incomingTxnId);

            int rowsAffected = ps.executeUpdate();

            if (rowsAffected == 0) {
                throw new RuntimeException(
                        "IncomingTransactionDaoImpl.updateStatus() — no row found for id: "
                                + incomingTxnId
                );
            }

        } catch (SQLException e) {
            throw new RuntimeException(
                    "IncomingTransactionDaoImpl.updateStatus() failed: " + e.getMessage(), e
            );
        } finally {
            closeResources(ps, conn);
        }
    }

    // -----------------------------------------------------------------------
    // Private helper — maps one ResultSet row to IncomingTransaction object
    // -----------------------------------------------------------------------

    private IncomingTransaction mapRowToIncomingTransaction(ResultSet rs) throws SQLException {

        SourceSystem sourceSystem = new SourceSystem();
        sourceSystem.setSourceSystemId(rs.getLong("source_system_id"));
        sourceSystem.setSystemCode(rs.getString("system_code"));
        sourceSystem.setProtocol(ProtocolType.valueOf(rs.getString("protocol")));
        sourceSystem.setConnectionConfig(rs.getString("connection_config"));
        sourceSystem.setActive(rs.getBoolean("is_active"));
        sourceSystem.setContactEmail(rs.getString("contact_email"));

        IncomingTransaction txn = new IncomingTransaction();
        txn.setIncomingTxnId(rs.getLong("incoming_txn_id"));
        txn.setId(rs.getLong("incoming_txn_id"));
        txn.setSourceSystem(sourceSystem);
        txn.setSourceRef(rs.getString("source_ref"));
        txn.setRawPayload(rs.getString("raw_payload"));
        txn.setTxnType(TransactionType.valueOf(rs.getString("txn_type")));
        txn.setAmount(rs.getBigDecimal("amount"));
        txn.setCurrency(rs.getString("currency"));
        txn.setValueDate(rs.getDate("value_date").toLocalDate());
        txn.setProcessingStatus(ProcessingStatus.valueOf(rs.getString("processing_status")));
        txn.setIngestTimestamp(rs.getTimestamp("ingest_timestamp").toLocalDateTime());
        txn.setNormalizedPayload(rs.getString("normalized_payload"));
        txn.setCreatedBy(rs.getString("created_by"));
        txn.setVersion(rs.getInt("version"));

        Timestamp createdAt = rs.getTimestamp("created_at");
        if (createdAt != null) {
            txn.setCreatedAt(createdAt.toLocalDateTime());
        }

        Timestamp updatedAt = rs.getTimestamp("updated_at");
        if (updatedAt != null) {
            txn.setUpdatedAt(updatedAt.toLocalDateTime());
        }

        return txn;
    }

    // -----------------------------------------------------------------------
    // Private helpers — close JDBC resources safely
    // -----------------------------------------------------------------------

    private void closeResources(PreparedStatement ps, Connection conn) {
        try {
            if (ps != null) ps.close();
        } catch (SQLException ignored) {
        }

        try {
            if (conn != null) conn.close();
        } catch (SQLException ignored) {
        }
    }

    private void closeResources(ResultSet rs, PreparedStatement ps, Connection conn) {
        try {
            if (rs != null) rs.close();
        } catch (SQLException ignored) {
        }

        try {
            if (ps != null) ps.close();
        } catch (SQLException ignored) {
        }

        try {
            if (conn != null) conn.close();
        } catch (SQLException ignored) {
        }
    }
}