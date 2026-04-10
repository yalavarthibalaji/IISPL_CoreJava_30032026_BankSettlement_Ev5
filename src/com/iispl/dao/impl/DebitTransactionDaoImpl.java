package com.iispl.dao.impl;

import com.iispl.connectionpool.ConnectionPool;
import com.iispl.dao.DebitTransactionDao;
import com.iispl.entity.DebitTransaction;
import com.iispl.enums.TransactionStatus;
import com.iispl.enums.TransactionType;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * DebitTransactionDaoImpl — updated with from_bank, to_bank, incoming_txn_id.
 */
public class DebitTransactionDaoImpl implements DebitTransactionDao {
    private static final Logger LOGGER = Logger.getLogger(DebitTransactionDaoImpl.class.getName());

    @Override
    public void save(DebitTransaction txn) {

        String SQL_INSERT = "INSERT INTO debit_transaction " +
                "(debit_account_id, credit_account_id, debit_account_number, " +
                " amount, currency, txn_date, value_date, status, reference_number, txn_type, " +
                " from_bank, to_bank, incoming_txn_id, " +
                " created_at, updated_at, created_by, version) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

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
            ps.setString(11,    txn.getFromBank());
            ps.setString(12,    txn.getToBank());
            if (txn.getIncomingTxnId() != null && txn.getIncomingTxnId() > 0) {
                ps.setLong(13, txn.getIncomingTxnId());
            } else {
                ps.setNull(13, Types.BIGINT);
            }
            ps.setTimestamp(14, Timestamp.valueOf(txn.getCreatedAt()));
            ps.setTimestamp(15, Timestamp.valueOf(txn.getUpdatedAt()));
            ps.setString(16,    txn.getCreatedBy());
            ps.setInt(17,       txn.getVersion());

            int rows = ps.executeUpdate();
            if (rows == 0) throw new RuntimeException(
                    "DebitTransactionDaoImpl.save() failed — no rows inserted for ref: "
                            + txn.getReferenceNumber());

            ResultSet keys = ps.getGeneratedKeys();
            if (keys.next()) txn.setTxnId(keys.getLong(1));
            keys.close();

            LOGGER.fine("[DebitTransactionDaoImpl] Saved | txn_id: " + txn.getTxnId()
                    + " | ref: " + txn.getReferenceNumber()
                    + " | amount: " + txn.getAmount()
                    + " | fromBank: " + txn.getFromBank()
                    + " | toBank: " + txn.getToBank());

        } catch (SQLException e) {
            throw new RuntimeException("DebitTransactionDaoImpl.save() failed: " + e.getMessage(), e);
        } finally {
            closeResources(null, ps, conn);
        }
    }

    @Override
    public DebitTransaction findById(Long txnId) {
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = ConnectionPool.getConnection();
            ps   = conn.prepareStatement("SELECT * FROM debit_transaction WHERE txn_id = ?");
            ps.setLong(1, txnId);
            rs = ps.executeQuery();
            if (rs.next()) return mapRow(rs);
            return null;
        } catch (SQLException e) {
            throw new RuntimeException("DebitTransactionDaoImpl.findById() failed: " + e.getMessage(), e);
        } finally {
            closeResources(rs, ps, conn);
        }
    }

    @Override
    public List<DebitTransaction> findByIncomingTxnId(Long incomingTxnId) {
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        List<DebitTransaction> list = new ArrayList<>();
        try {
            conn = ConnectionPool.getConnection();
            ps   = conn.prepareStatement(
                    "SELECT * FROM debit_transaction WHERE incoming_txn_id = ?");
            ps.setLong(1, incomingTxnId);
            rs = ps.executeQuery();
            while (rs.next()) list.add(mapRow(rs));
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("DebitTransactionDaoImpl.findByIncomingTxnId() failed: "
                    + e.getMessage(), e);
        } finally {
            closeResources(rs, ps, conn);
        }
    }

    @Override
    public void updateStatus(Long txnId, String status) {
        Connection conn = null;
        PreparedStatement ps = null;
        try {
            conn = ConnectionPool.getConnection();
            ps   = conn.prepareStatement(
                    "UPDATE debit_transaction SET status = ?, updated_at = ? WHERE txn_id = ?");
            ps.setString(1,    status);
            ps.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
            ps.setLong(3,      txnId);
            int rows = ps.executeUpdate();
            if (rows == 0) throw new RuntimeException(
                    "DebitTransactionDaoImpl.updateStatus() — no row for txn_id: " + txnId);
        } catch (SQLException e) {
            throw new RuntimeException("DebitTransactionDaoImpl.updateStatus() failed: "
                    + e.getMessage(), e);
        } finally {
            closeResources(null, ps, conn);
        }
    }

    private DebitTransaction mapRow(ResultSet rs) throws SQLException {
        DebitTransaction txn = new DebitTransaction();
        txn.setTxnId(rs.getLong("txn_id"));
        txn.setDebitAccountId(rs.getLong("debit_account_id"));
        txn.setDebitAccountNumber(rs.getString("debit_account_number"));
        txn.setCreditAccountId(rs.getLong("credit_account_id"));
        txn.setAmount(rs.getBigDecimal("amount"));
        txn.setCurrency(rs.getString("currency"));
        txn.setTxnDate(rs.getTimestamp("txn_date").toLocalDateTime());
        txn.setValueDate(rs.getDate("value_date").toLocalDate());
        txn.setStatus(TransactionStatus.valueOf(rs.getString("status")));
        txn.setReferenceNumber(rs.getString("reference_number"));
        txn.setTxnType(TransactionType.valueOf(rs.getString("txn_type")));
        txn.setFromBank(rs.getString("from_bank"));
        txn.setToBank(rs.getString("to_bank"));
        txn.setIncomingTxnId(rs.getLong("incoming_txn_id"));
        txn.setCreatedBy(rs.getString("created_by"));
        txn.setVersion(rs.getInt("version"));
        Timestamp ca = rs.getTimestamp("created_at");
        if (ca != null) txn.setCreatedAt(ca.toLocalDateTime());
        Timestamp ua = rs.getTimestamp("updated_at");
        if (ua != null) txn.setUpdatedAt(ua.toLocalDateTime());
        return txn;
    }

    private void closeResources(ResultSet rs, PreparedStatement ps, Connection conn) {
        try { if (rs   != null) rs.close();   } catch (SQLException ignored) {}
        try { if (ps   != null) ps.close();   } catch (SQLException ignored) {}
        try { if (conn != null) conn.close();  } catch (SQLException ignored) {}
    }
}