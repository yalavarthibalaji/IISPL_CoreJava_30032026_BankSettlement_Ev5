package com.iispl.banksettlement.dao.impl;

import com.iispl.banksettlement.dao.ReversalTransactionDao;
import com.iispl.banksettlement.entity.ReversalTransaction;
import com.iispl.banksettlement.enums.TransactionStatus;
import com.iispl.banksettlement.enums.TransactionType;
import com.iispl.connectionpool.ConnectionPool;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * ReversalTransactionDaoImpl — JDBC implementation for reversal_transaction
 * table.
 *
 * ACTUAL TABLE COLUMNS (from Supabase schema): txn_id, debit_account_id,
 * credit_account_id, original_txn_ref, reversal_reason, amount, currency,
 * txn_date, value_date, status, reference_number, txn_type, created_at,
 * updated_at, created_by, version
 *
 * NOTE: There is NO incoming_txn_id column in this table.
 */
public class ReversalTransactionDaoImpl implements ReversalTransactionDao {

	@Override
	public void save(ReversalTransaction txn) {

		// 16 columns — no incoming_txn_id
		String SQL_INSERT = "INSERT INTO reversal_transaction "
				+ "(debit_account_id, credit_account_id, original_txn_ref, reversal_reason, "
				+ "amount, currency, txn_date, value_date, status, reference_number, txn_type, "
				+ "created_at, updated_at, created_by, version) "
				+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

		Connection conn = null;
		PreparedStatement ps = null;

		try {
			conn = ConnectionPool.getConnection();
			ps = conn.prepareStatement(SQL_INSERT, Statement.RETURN_GENERATED_KEYS);

			ps.setLong(1, txn.getDebitAccountId());
			ps.setLong(2, txn.getCreditAccountId());
			ps.setString(3, txn.getOriginalTxnRef());
			ps.setString(4, txn.getReversalReason());
			ps.setBigDecimal(5, txn.getAmount());
			ps.setString(6, txn.getCurrency());
			ps.setTimestamp(7, Timestamp.valueOf(txn.getTxnDate()));
			ps.setDate(8, Date.valueOf(txn.getValueDate()));
			ps.setString(9, txn.getStatus().name());
			ps.setString(10, txn.getReferenceNumber());
			ps.setString(11, txn.getTxnType().name());
			ps.setTimestamp(12, Timestamp.valueOf(txn.getCreatedAt()));
			ps.setTimestamp(13, Timestamp.valueOf(txn.getUpdatedAt()));
			ps.setString(14, txn.getCreatedBy());
			ps.setInt(15, txn.getVersion());

			int rowsAffected = ps.executeUpdate();
			if (rowsAffected == 0) {
				throw new RuntimeException("ReversalTransactionDaoImpl.save() failed — no rows inserted for ref: "
						+ txn.getReferenceNumber());
			}

			ResultSet keys = ps.getGeneratedKeys();
			if (keys.next()) {
				txn.setTxnId(keys.getLong(1));
			}
			keys.close();

			System.out.println("[ReversalTransactionDaoImpl] Saved | txn_id: " + txn.getTxnId() + " | ref: "
					+ txn.getReferenceNumber() + " | originalRef: " + txn.getOriginalTxnRef() + " | reason: "
					+ txn.getReversalReason());

		} catch (SQLException e) {
			throw new RuntimeException("ReversalTransactionDaoImpl.save() failed: " + e.getMessage(), e);
		} finally {
			closeResources(ps, conn);
		}
	}

	@Override
	public ReversalTransaction findById(Long txnId) {

		String SQL_FIND_BY_ID = "SELECT * FROM reversal_transaction WHERE txn_id = ?";

		Connection conn = null;
		PreparedStatement ps = null;
		ResultSet rs = null;

		try {
			conn = ConnectionPool.getConnection();
			ps = conn.prepareStatement(SQL_FIND_BY_ID);
			ps.setLong(1, txnId);
			rs = ps.executeQuery();
			if (rs.next())
				return mapRow(rs);
			return null;

		} catch (SQLException e) {
			throw new RuntimeException("ReversalTransactionDaoImpl.findById() failed: " + e.getMessage(), e);
		} finally {
			closeResources(rs, ps, conn);
		}
	}

	@Override
	public List<ReversalTransaction> findByIncomingTxnId(Long incomingTxnId) {
		// No incoming_txn_id column in this table — return empty list
		return new ArrayList<>();
	}

	@Override
	public List<ReversalTransaction> findByOriginalTxnRef(String originalTxnRef) {

		String SQL_FIND_BY_ORIGINAL_TXN_REF = "SELECT * FROM reversal_transaction WHERE original_txn_ref = ?";

		Connection conn = null;
		PreparedStatement ps = null;
		ResultSet rs = null;
		List<ReversalTransaction> result = new ArrayList<>();

		try {
			conn = ConnectionPool.getConnection();
			ps = conn.prepareStatement(SQL_FIND_BY_ORIGINAL_TXN_REF);
			ps.setString(1, originalTxnRef);
			rs = ps.executeQuery();
			while (rs.next()) {
				result.add(mapRow(rs));
			}
			return result;

		} catch (SQLException e) {
			throw new RuntimeException("ReversalTransactionDaoImpl.findByOriginalTxnRef() failed: " + e.getMessage(),
					e);
		} finally {
			closeResources(rs, ps, conn);
		}
	}

	@Override
	public void updateStatus(Long txnId, String status) {

		String SQL_UPDATE_STATUS = "UPDATE reversal_transaction SET status = ?, updated_at = ? WHERE txn_id = ?";

		Connection conn = null;
		PreparedStatement ps = null;

		try {
			conn = ConnectionPool.getConnection();
			ps = conn.prepareStatement(SQL_UPDATE_STATUS);
			ps.setString(1, status);
			ps.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
			ps.setLong(3, txnId);

			int rowsAffected = ps.executeUpdate();
			if (rowsAffected == 0) {
				throw new RuntimeException("ReversalTransactionDaoImpl.updateStatus() — no row for txn_id: " + txnId);
			}

		} catch (SQLException e) {
			throw new RuntimeException("ReversalTransactionDaoImpl.updateStatus() failed: " + e.getMessage(), e);
		} finally {
			closeResources(ps, conn);
		}
	}

	private ReversalTransaction mapRow(ResultSet rs) throws SQLException {
		ReversalTransaction txn = new ReversalTransaction();
		txn.setTxnId(rs.getLong("txn_id"));
		txn.setDebitAccountId(rs.getLong("debit_account_id"));
		txn.setCreditAccountId(rs.getLong("credit_account_id"));
		txn.setOriginalTxnRef(rs.getString("original_txn_ref"));
		txn.setReversalReason(rs.getString("reversal_reason"));
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
		if (ca != null)
			txn.setCreatedAt(ca.toLocalDateTime());
		Timestamp ua = rs.getTimestamp("updated_at");
		if (ua != null)
			txn.setUpdatedAt(ua.toLocalDateTime());
		return txn;
	}

	private void closeResources(PreparedStatement ps, Connection conn) {
		try {
			if (ps != null)
				ps.close();
		} catch (SQLException ignored) {
		}
		try {
			if (conn != null)
				conn.close();
		} catch (SQLException ignored) {
		}
	}

	private void closeResources(ResultSet rs, PreparedStatement ps, Connection conn) {
		try {
			if (rs != null)
				rs.close();
		} catch (SQLException ignored) {
		}
		try {
			if (ps != null)
				ps.close();
		} catch (SQLException ignored) {
		}
		try {
			if (conn != null)
				conn.close();
		} catch (SQLException ignored) {
		}
	}
}
