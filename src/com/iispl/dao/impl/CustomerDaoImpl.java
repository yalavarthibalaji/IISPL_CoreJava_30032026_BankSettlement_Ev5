package com.iispl.dao.impl;

import com.iispl.connectionpool.ConnectionPool;
import com.iispl.dao.CustomerDao;
import com.iispl.entity.Customer;
import com.iispl.enums.KycStatus;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * CustomerDaoImpl — JDBC implementation of CustomerDao.
 *
 * Uses ConnectionPool.getConnection() for all DB operations. All resources are
 * closed in finally blocks. Always uses PreparedStatement — never raw
 * Statement.
 *
 * TABLE EXPECTED IN DB: customer ( customer_id BIGSERIAL PRIMARY KEY, -- FIX:
 * was "id" first_name VARCHAR(50) NOT NULL, last_name VARCHAR(50) NOT NULL,
 * email VARCHAR(100) NOT NULL UNIQUE, kyc_status VARCHAR(20) NOT NULL,
 * customer_tier VARCHAR(20), onboarding_date DATE, created_at TIMESTAMP,
 * updated_at TIMESTAMP, created_by VARCHAR(50), version INT DEFAULT 0 )
 */
public class CustomerDaoImpl implements CustomerDao {

	@Override
	public Customer findById(Long customerId) {

		// FIX: Changed "id" → "customer_id" in SELECT list and WHERE clause
		String SQL_FIND_BY_ID = "SELECT customer_id, first_name, last_name, email, kyc_status, "
				+ "customer_tier, onboarding_date, created_by, version " + "FROM customer " + "WHERE customer_id = ?";

		Connection conn = null;
		PreparedStatement ps = null;
		ResultSet rs = null;

		try {
			conn = ConnectionPool.getConnection();
			ps = conn.prepareStatement(SQL_FIND_BY_ID);
			ps.setLong(1, customerId);
			rs = ps.executeQuery();

			if (rs.next()) {
				return mapRow(rs);
			}

			return null;

		} catch (SQLException e) {
			throw new RuntimeException("CustomerDaoImpl.findById() failed: " + e.getMessage(), e);
		} finally {
			closeResources(rs, ps, conn);
		}
	}

	// -----------------------------------------------------------------------
	// isCustomerKycVerified()
	// -----------------------------------------------------------------------

	@Override
	public boolean isCustomerKycVerified(Long customerId) {

		// FIX: Changed "id" → "customer_id" in WHERE clause
		String SQL_IS_KYC_VERIFIED = "SELECT COUNT(*) FROM customer "
				+ "WHERE customer_id = ? AND kyc_status = 'VERIFIED'";

		Connection conn = null;
		PreparedStatement ps = null;
		ResultSet rs = null;

		try {
			conn = ConnectionPool.getConnection();
			ps = conn.prepareStatement(SQL_IS_KYC_VERIFIED);
			ps.setLong(1, customerId);
			rs = ps.executeQuery();

			if (rs.next()) {
				return rs.getInt(1) > 0;
			}

			return false;

		} catch (SQLException e) {
			throw new RuntimeException("CustomerDaoImpl.isCustomerKycVerified() failed: " + e.getMessage(), e);
		} finally {
			closeResources(rs, ps, conn);
		}
	}

	// -----------------------------------------------------------------------
	// Private helper — map one ResultSet row to Customer object
	// -----------------------------------------------------------------------

	private Customer mapRow(ResultSet rs) throws SQLException {
		Customer c = new Customer();
		// FIX: Changed rs.getLong("id") → rs.getLong("customer_id") to match actual
		// schema
		c.setId(rs.getLong("customer_id"));
		c.setFirstName(rs.getString("first_name"));
		c.setLastName(rs.getString("last_name"));
		c.setEmail(rs.getString("email"));
		c.setKycStatus(KycStatus.valueOf(rs.getString("kyc_status")));
		c.setCustomerTier(rs.getString("customer_tier"));
		Date d = rs.getDate("onboarding_date");
		if (d != null) {
			c.setOnboardingDate(d.toLocalDate());
		}
		c.setCreatedBy(rs.getString("created_by"));
		c.setVersion(rs.getInt("version"));
		return c;
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
