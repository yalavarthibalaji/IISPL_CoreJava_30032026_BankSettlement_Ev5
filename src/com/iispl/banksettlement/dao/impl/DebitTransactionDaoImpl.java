package com.iispl.banksettlement.dao.impl;

import com.iispl.banksettlement.dao.DebitTransactionDao;
import com.iispl.banksettlement.entity.DebitTransaction;
import com.iispl.banksettlement.enums.TransactionStatus;
import com.iispl.banksettlement.enums.TransactionType;
import com.iispl.connectionpool.ConnectionPool;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * DebitTransactionDaoImpl — JDBC implementation for debit_transaction table.
 *
 * ACTUAL TABLE COLUMNS (from Supabase schema):
 *   txn_id, debit_account_id, credit_account_id, debit_account_number,
 *   amount, currency, txn_date, value_date, status, reference_number,
 *   txn_type, created_at, updated_at, created_by, version
 *
 * NOTE: There is NO incoming_txn_id column in this table.
 */
public class DebitTransactionDaoImpl implements DebitTransactionDao {

    // 14 columns — no incoming_txn_id
    private static final String SQL_INSERT =
            "INSERT INTO debit_transaction " +
            "(debit_account_id, credit_account_id, debit_account_number, " +
            "amount, currency, txn_date, value_date, status, reference_number, txn_type, " +
            "created_at, updated_at, created_by, version) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String SQL_FIND_BY_ID =
            "SELECT * FROM debit_transaction WHERE txn_id = ?";

    private static final String SQL_UPDATE_STATUS =
            "UPDATE debit_transaction SET status = ?, updated_at = ? WHERE txn_id = ?";

    @Override
    public void save(DebitTransaction txn) {

        Connection conn = null;
        PreparedStatement ps = null;

        try {
            conn = ConnectionPool.getConnection();
            ps   = conn.prepareStatement(SQL_INSERT, Statement.RETURN_GENERATED_KEYS);

            ps.setLong(1,       txn.getDebitAccountId());
            ps.setLong(2,       txn.getCreditAccountId());
            ps.setString(3,     txn.getDebitAccountNumber());
            ps.setBigDecimal(4, txn.getAmount());
            ps.setString(5,     txn.getCurrency());
            ps.setTimestamp(6,  Timestamp.valueOf(txn.getTxnDate()));
            ps.setDate(7,       Date.valueOf(txn.getValueDate()));
            ps.setString(8,     txn.getStatus().name());
            ps.setString(9,     txn.getReferenceNumber());
            ps.setString(10,    txn.getTxnType().name());
            ps.setTimestamp(11, Timestamp.valueOf(txn.getCreatedAt()));
            ps.setTimestamp(12, Timestamp.valueOf(txn.getUpdatedAt()));
            ps.setString(13,    txn.getCreatedBy());
            ps.setInt(14,       txn.getVersion());

            int rowsAffected = ps.executeUpdate();
            if (rowsAffected == 0) {
                throw new RuntimeException(
                    "DebitTransactionDaoImpl.save() failed — no rows inserted for ref: "
                    + txn.getReferenceNumber());
            }

            ResultSet keys = ps.getGeneratedKeys();
            if (keys.next()) {
                txn.setTxnId(keys.getLong(1));
            }
            keys.close();

            System.out.println("[DebitTransactionDaoImpl] Saved | txn_id: " + txn.getTxnId()
                    + " | ref: " + txn.getReferenceNumber()
                    + " | amount: " + txn.getAmount());

        } catch (SQLException e) {
            throw new RuntimeException(
                "DebitTransactionDaoImpl.save() failed: " + e.getMessage(), e);
        } finally {
            closeResources(ps, conn);
        }
    }

    @Override
    public DebitTransaction findById(Long txnId) {

        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;

        try {
            conn = ConnectionPool.getConnection();
            ps   = conn.prepareStatement(SQL_FIND_BY_ID);
            ps.setLong(1, txnId);
            rs = ps.executeQuery();
            if (rs.next()) return mapRow(rs);
            return null;

        } catch (SQLException e) {
            throw new RuntimeException(
                "DebitTransactionDaoImpl.findById() failed: " + e.getMessage(), e);
        } finally {
            closeResources(rs, ps, conn);
        }
    }

    @Override
    public List<DebitTransaction> findByIncomingTxnId(Long incomingTxnId) {
        // No incoming_txn_id column in this table — return empty list
        return new ArrayList<>();
    }

    @Override
    public void updateStatus(Long txnId, String status) {

        Connection conn = null;
        PreparedStatement ps = null;

        try {
            conn = ConnectionPool.getConnection();
            ps   = conn.prepareStatement(SQL_UPDATE_STATUS);
            ps.setString(1,    status);
            ps.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
            ps.setLong(3,      txnId);

            int rowsAffected = ps.executeUpdate();
            if (rowsAffected == 0) {
                throw new RuntimeException(
                    "DebitTransactionDaoImpl.updateStatus() — no row for txn_id: " + txnId);
            }

        } catch (SQLException e) {
            throw new RuntimeException(
                "DebitTransactionDaoImpl.updateStatus() failed: " + e.getMessage(), e);
        } finally {
            closeResources(ps, conn);
        }
    }

    private DebitTransaction mapRow(ResultSet rs) throws SQLException {
        DebitTransaction txn = new DebitTransaction();
        txn.setTxnId(rs.getLong("txn_id"));
        txn.setDebitAccountId(rs.getLong("debit_account_id"));
        txn.setCreditAccountId(rs.getLong("credit_account_id"));
        txn.setDebitAccountNumber(rs.getString("debit_account_number"));
        txn.setAmount(rs.getBigDecimal("amount"));
        txn.setCurrency(rs.getString("currency"));
        txn.setTxnDate(rs.getTimestamp("txn_date").toLocalDateTime());
        txn.setValueDate(rs.getDate("value_date").toLocalDate());
        txn.setStatus(TransactionStatus.valueOf(rs.getString("status")));
        txn.setReferenceNumber(rs.getString("reference_number"));
        txn.setTxnType(TransactionType.valueOf(rs.getString("txn_type")));
        txn.setCreatedBy(rs.getString("created_by"));
        txn.setVersion(rs.getInt("version"));
        Timestamp ca = rs.getTimestamp("created_at");
        if (ca != null) txn.setCreatedAt(ca.toLocalDateTime());
        Timestamp ua = rs.getTimestamp("updated_at");
        if (ua != null) txn.setUpdatedAt(ua.toLocalDateTime());
        return txn;
    }

    private void closeResources(PreparedStatement ps, Connection conn) {
        try { if (ps   != null) ps.close();  } catch (SQLException ignored) {}
        try { if (conn != null) conn.close(); } catch (SQLException ignored) {}
    }

    private void closeResources(ResultSet rs, PreparedStatement ps, Connection conn) {
        try { if (rs   != null) rs.close();  } catch (SQLException ignored) {}
        try { if (ps   != null) ps.close();  } catch (SQLException ignored) {}
        try { if (conn != null) conn.close(); } catch (SQLException ignored) {}
    }
}
