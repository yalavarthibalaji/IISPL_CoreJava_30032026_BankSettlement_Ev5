package com.iispl.banksettlement.dao.impl;

import com.iispl.banksettlement.dao.AccountDao;
import com.iispl.banksettlement.entity.Account;
import com.iispl.banksettlement.enums.AccountStatus;
import com.iispl.banksettlement.enums.AccountType;
import com.iispl.connectionpool.ConnectionPool;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * AccountDaoImpl — JDBC implementation of AccountDao.
 *
 * Uses ConnectionPool.getConnection() for all DB operations. All resources are
 * closed in finally blocks. Always uses PreparedStatement — never raw
 * Statement.
 *
 * TABLE EXPECTED IN DB: account ( account_id BIGSERIAL PRIMARY KEY, -- FIX: was
 * "id" account_number VARCHAR(50) UNIQUE NOT NULL, account_type VARCHAR(30) NOT
 * NULL, balance NUMERIC(19,4) NOT NULL, currency VARCHAR(10) NOT NULL,
 * customer_id BIGINT REFERENCES customer(customer_id), bank_id BIGINT, status
 * VARCHAR(20) NOT NULL, created_at TIMESTAMP, updated_at TIMESTAMP, created_by
 * VARCHAR(50), version INT DEFAULT 0 )
 */
public class AccountDaoImpl implements AccountDao {

	@Override
	public Account findByAccountNumber(String accountNumber) {

		// FIX: Changed "id" → "account_id" to match the actual Supabase schema
		String SQL_FIND_BY_ACCOUNT_NUMBER = "SELECT account_id, account_number, account_type, balance, currency, "
				+ "customer_id, bank_id, status, created_by, version " + "FROM account " + "WHERE account_number = ?";

		Connection conn = null;
		PreparedStatement ps = null;
		ResultSet rs = null;

		try {
			conn = ConnectionPool.getConnection();
			ps = conn.prepareStatement(SQL_FIND_BY_ACCOUNT_NUMBER);
			ps.setString(1, accountNumber);
			rs = ps.executeQuery();

			if (rs.next()) {
				return mapRow(rs);
			}

			return null;

		} catch (SQLException e) {
			throw new RuntimeException("AccountDaoImpl.findByAccountNumber() failed: " + e.getMessage(), e);
		} finally {
			closeResources(rs, ps, conn);
		}
	}

	// -----------------------------------------------------------------------
	// isAccountActiveByNumber()
	// -----------------------------------------------------------------------

	@Override
	public boolean isAccountActiveByNumber(String accountNumber) {

		String SQL_IS_ACTIVE = "SELECT COUNT(*) FROM account " + "WHERE account_number = ? AND status = 'ACTIVE'";

		Connection conn = null;
		PreparedStatement ps = null;
		ResultSet rs = null;

		try {
			conn = ConnectionPool.getConnection();
			ps = conn.prepareStatement(SQL_IS_ACTIVE);
			ps.setString(1, accountNumber);
			rs = ps.executeQuery();

			if (rs.next()) {
				return rs.getInt(1) > 0;
			}

			return false;

		} catch (SQLException e) {
			throw new RuntimeException("AccountDaoImpl.isAccountActiveByNumber() failed: " + e.getMessage(), e);
		} finally {
			closeResources(rs, ps, conn);
		}
	}

	// -----------------------------------------------------------------------
	// Private helper — map one ResultSet row to Account object
	// -----------------------------------------------------------------------

	private Account mapRow(ResultSet rs) throws SQLException {
		Account acc = new Account();
		// FIX: Changed rs.getLong("id") → rs.getLong("account_id") to match actual
		// schema
		acc.setId(rs.getLong("account_id"));
		acc.setAccountNumber(rs.getString("account_number"));
		acc.setAccountType(AccountType.valueOf(rs.getString("account_type")));
		acc.setBalance(rs.getBigDecimal("balance"));
		acc.setCurrency(rs.getString("currency"));
		acc.setCustomerId(rs.getLong("customer_id"));
		acc.setBankId(rs.getLong("bank_id"));
		acc.setStatus(AccountStatus.valueOf(rs.getString("status")));
		acc.setCreatedBy(rs.getString("created_by"));
		acc.setVersion(rs.getInt("version"));
		return acc;
	}

	// -----------------------------------------------------------------------
	// Private helpers — close JDBC resources safely
	// -----------------------------------------------------------------------

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
