package com.iispl.dao.impl;

import com.iispl.connectionpool.ConnectionPool;
import com.iispl.dao.BankDao;
import com.iispl.entity.Bank;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * BankDaoImpl — JDBC implementation for the bank table.
 *
 * TABLE: bank (bank_id, bank_name, bank_code, is_active, created_at, updated_at)
 */
public class BankDaoImpl implements BankDao {

    private static final String SQL_FIND_BY_ID =
            "SELECT bank_id, bank_name, bank_code, is_active FROM bank WHERE bank_id = ?";

    private static final String SQL_FIND_BY_NAME =
            "SELECT bank_id, bank_name, bank_code, is_active FROM bank WHERE LOWER(bank_name) = LOWER(?)";

    private static final String SQL_FIND_ALL =
            "SELECT bank_id, bank_name, bank_code, is_active FROM bank ORDER BY bank_id ASC";

    @Override
    public Bank findById(Long bankId) {
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = ConnectionPool.getConnection();
            ps = conn.prepareStatement(SQL_FIND_BY_ID);
            ps.setLong(1, bankId);
            rs = ps.executeQuery();
            if (rs.next()) return mapRow(rs);
            return null;
        } catch (SQLException e) {
            throw new RuntimeException("BankDaoImpl.findById() failed: " + e.getMessage(), e);
        } finally {
            closeResources(rs, ps, conn);
        }
    }

    @Override
    public Bank findByName(String bankName) {
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = ConnectionPool.getConnection();
            ps = conn.prepareStatement(SQL_FIND_BY_NAME);
            ps.setString(1, bankName);
            rs = ps.executeQuery();
            if (rs.next()) return mapRow(rs);
            return null;
        } catch (SQLException e) {
            throw new RuntimeException("BankDaoImpl.findByName() failed: " + e.getMessage(), e);
        } finally {
            closeResources(rs, ps, conn);
        }
    }

    @Override
    public List<Bank> findAll() {
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        List<Bank> banks = new ArrayList<>();
        try {
            conn = ConnectionPool.getConnection();
            ps = conn.prepareStatement(SQL_FIND_ALL);
            rs = ps.executeQuery();
            while (rs.next()) banks.add(mapRow(rs));
            return banks;
        } catch (SQLException e) {
            throw new RuntimeException("BankDaoImpl.findAll() failed: " + e.getMessage(), e);
        } finally {
            closeResources(rs, ps, conn);
        }
    }

    private Bank mapRow(ResultSet rs) throws SQLException {
        Bank bank = new Bank();
        bank.setBankId(rs.getLong("bank_id"));
        bank.setBankName(rs.getString("bank_name"));
        bank.setBankCode(rs.getString("bank_code"));
        bank.setActive(rs.getBoolean("is_active"));
        return bank;
    }

    private void closeResources(ResultSet rs, PreparedStatement ps, Connection conn) {
        try { if (rs   != null) rs.close();   } catch (SQLException ignored) {}
        try { if (ps   != null) ps.close();   } catch (SQLException ignored) {}
        try { if (conn != null) conn.close();  } catch (SQLException ignored) {}
    }
}
