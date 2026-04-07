package com.iispl.banksettlement.dao.impl;

import com.iispl.banksettlement.dao.InterBankTransactionDao;
import com.iispl.banksettlement.entity.InterBankTransaction;
import com.iispl.banksettlement.enums.TransactionStatus;
import com.iispl.banksettlement.enums.TransactionType;
import com.iispl.connectionpool.ConnectionPool;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * InterBankTransactionDaoImpl — JDBC implementation for interbank_transaction
 * table.
 *
 * ACTUAL TABLE COLUMNS (from Supabase schema): txn_id, debit_account_id,
 * credit_account_id, correspondent_bank_code, amount, currency, txn_date,
 * value_date, status, reference_number, txn_type, created_at, updated_at,
 * created_by, version
 *
 * NOTE: There is NO incoming_txn_id column in this table.
 */
public class InterBankTransactionDaoImpl implements InterBankTransactionDao {

	@Override
	public void save(InterBankTransaction txn) {

		// 14 columns — no incoming_txn_id
		String SQL_INSERT = "INSERT INTO interbank_transaction "
				+ "(debit_account_id, credit_account_id, correspondent_bank_code, "
				+ "amount, currency, txn_date, value_date, status, reference_number, txn_type, "
				+ "created_at, updated_at, created_by, version) " + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

		Connection conn = null;
		PreparedStatement ps = null;

		try {
			conn = ConnectionPool.getConnection();
			ps = conn.prepareStatement(SQL_INSERT, Statement.RETURN_GENERATED_KEYS);

			ps.setLong(1, txn.getDebitAccountId());
			ps.setLong(2, txn.getCreditAccountId());
			ps.setString(3, txn.getCorrespondentBankCode());
			ps.setBigDecimal(4, txn.getAmount());
			ps.setString(5, txn.getCurrency());
			ps.setTimestamp(6, Timestamp.valueOf(txn.getTxnDate()));
			ps.setDate(7, Date.valueOf(txn.getValueDate()));
			ps.setString(8, txn.getStatus().name());
			ps.setString(9, txn.getReferenceNumber());
			ps.setString(10, txn.getTxnType().name());
			ps.setTimestamp(11, Timestamp.valueOf(txn.getCreatedAt()));
			ps.setTimestamp(12, Timestamp.valueOf(txn.getUpdatedAt()));
			ps.setString(13, txn.getCreatedBy());
			ps.setInt(14, txn.getVersion());

			int rowsAffected = ps.executeUpdate();
			if (rowsAffected == 0) {
				throw new RuntimeException("InterBankTransactionDaoImpl.save() failed — no rows inserted for ref: "
						+ txn.getReferenceNumber());
			}

			ResultSet keys = ps.getGeneratedKeys();
			if (keys.next()) {
				txn.setTxnId(keys.getLong(1));
			}
			keys.close();

			System.out.println("[InterBankTransactionDaoImpl] Saved | txn_id: " + txn.getTxnId() + " | ref: "
					+ txn.getReferenceNumber() + " | correspondent: " + txn.getCorrespondentBankCode() + " | amount: "
					+ txn.getAmount());

		} catch (SQLException e) {
			throw new RuntimeException("InterBankTransactionDaoImpl.save() failed: " + e.getMessage(), e);
		} finally {
			closeResources(ps, conn);
		}
	}

	@Override
	public InterBankTransaction findById(Long txnId) {

		String SQL_FIND_BY_ID = "SELECT * FROM interbank_transaction WHERE txn_id = ?";

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
			throw new RuntimeException("InterBankTransactionDaoImpl.findById() failed: " + e.getMessage(), e);
		} finally {
			closeResources(rs, ps, conn);
		}
	}

	@Override
	public List<InterBankTransaction> findByIncomingTxnId(Long incomingTxnId) {
		// No incoming_txn_id column in this table — return empty list
		return new ArrayList<>();
	}

	@Override
	public List<InterBankTransaction> findByCorrespondentBankCode(String correspondentBankCode) {

		String SQL_FIND_BY_CORRESPONDENT = "SELECT * FROM interbank_transaction WHERE correspondent_bank_code = ? "
				+ "ORDER BY txn_date DESC";

		Connection conn = null;
		PreparedStatement ps = null;
		ResultSet rs = null;
		List<InterBankTransaction> result = new ArrayList<>();

		try {
			conn = ConnectionPool.getConnection();
			ps = conn.prepareStatement(SQL_FIND_BY_CORRESPONDENT);
			ps.setString(1, correspondentBankCode);
			rs = ps.executeQuery();
			while (rs.next()) {
				result.add(mapRow(rs));
			}
			return result;

		} catch (SQLException e) {
			throw new RuntimeException(
					"InterBankTransactionDaoImpl.findByCorrespondentBankCode() failed: " + e.getMessage(), e);
		} finally {
			closeResources(rs, ps, conn);
		}
	}

	@Override
	public void updateStatus(Long txnId, String status) {

		String SQL_UPDATE_STATUS = "UPDATE interbank_transaction SET status = ?, updated_at = ? WHERE txn_id = ?";

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
				throw new RuntimeException("InterBankTransactionDaoImpl.updateStatus() — no row for txn_id: " + txnId);
			}

		} catch (SQLException e) {
			throw new RuntimeException("InterBankTransactionDaoImpl.updateStatus() failed: " + e.getMessage(), e);
		} finally {
			closeResources(ps, conn);
		}
	}

	private InterBankTransaction mapRow(ResultSet rs) throws SQLException {
		InterBankTransaction txn = new InterBankTransaction();
		txn.setTxnId(rs.getLong("txn_id"));
		txn.setDebitAccountId(rs.getLong("debit_account_id"));
		txn.setCreditAccountId(rs.getLong("credit_account_id"));
		txn.setCorrespondentBankCode(rs.getString("correspondent_bank_code"));
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
