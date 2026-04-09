package com.iispl.banksettlement.dao.impl;

import com.iispl.banksettlement.dao.NpciMemberAccountDao;
import com.iispl.banksettlement.entity.NpciMemberAccount;
import com.iispl.connectionpool.ConnectionPool;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * NpciMemberAccountDaoImpl — JDBC implementation for npci_bank_account table.
 *
 * TABLE: npci_bank_account
 *   npci_account_id, bank_id, opening_balance, current_balance, currency,
 *   created_at, updated_at
 *
 * We JOIN with bank to get bank_name in findAll() and findByBankId().
 */
public class NpciMemberAccountDaoImpl implements NpciMemberAccountDao {

    private static final String SQL_FIND_ALL =
            "SELECT na.npci_account_id, na.bank_id, b.bank_name, " +
            "na.opening_balance, na.current_balance, na.currency " +
            "FROM npci_bank_account na " +
            "JOIN bank b ON b.bank_id = na.bank_id " +
            "ORDER BY na.npci_account_id ASC";

    private static final String SQL_FIND_BY_BANK_ID =
            "SELECT na.npci_account_id, na.bank_id, b.bank_name, " +
            "na.opening_balance, na.current_balance, na.currency " +
            "FROM npci_bank_account na " +
            "JOIN bank b ON b.bank_id = na.bank_id " +
            "WHERE na.bank_id = ?";

    private static final String SQL_UPDATE_BALANCES =
            "UPDATE npci_bank_account " +
            "SET opening_balance = ?, current_balance = ?, updated_at = now() " +
            "WHERE npci_account_id = ?";

    @Override
    public List<NpciMemberAccount> findAll() {
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        List<NpciMemberAccount> list = new ArrayList<>();
        try {
            conn = ConnectionPool.getConnection();
            ps   = conn.prepareStatement(SQL_FIND_ALL);
            rs   = ps.executeQuery();
            while (rs.next()) list.add(mapRow(rs));
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("NpciMemberAccountDaoImpl.findAll() failed: " + e.getMessage(), e);
        } finally {
            closeResources(rs, ps, conn);
        }
    }

    @Override
    public NpciMemberAccount findByBankId(Long bankId) {
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = ConnectionPool.getConnection();
            ps   = conn.prepareStatement(SQL_FIND_BY_BANK_ID);
            ps.setLong(1, bankId);
            rs = ps.executeQuery();
            if (rs.next()) return mapRow(rs);
            return null;
        } catch (SQLException e) {
            throw new RuntimeException("NpciMemberAccountDaoImpl.findByBankId() failed: " + e.getMessage(), e);
        } finally {
            closeResources(rs, ps, conn);
        }
    }

    @Override
    public void updateBalances(NpciMemberAccount account) {
        Connection conn = null;
        PreparedStatement ps = null;
        try {
            conn = ConnectionPool.getConnection();
            ps   = conn.prepareStatement(SQL_UPDATE_BALANCES);
            ps.setBigDecimal(1, account.getOpeningBalance());
            ps.setBigDecimal(2, account.getCurrentBalance());
            ps.setLong(3, account.getNpciAccountId());
            int rows = ps.executeUpdate();
            if (rows == 0) {
                throw new RuntimeException("NpciMemberAccountDaoImpl.updateBalances() — no row found for id: "
                        + account.getNpciAccountId());
            }
            System.out.println("[NpciMemberAccountDao] Updated | bank: " + account.getBankName()
                    + " | opening: " + account.getOpeningBalance()
                    + " | current: " + account.getCurrentBalance());
        } catch (SQLException e) {
            throw new RuntimeException("NpciMemberAccountDaoImpl.updateBalances() failed: " + e.getMessage(), e);
        } finally {
            closeResources(null, ps, conn);
        }
    }

    private NpciMemberAccount mapRow(ResultSet rs) throws SQLException {
        NpciMemberAccount acc = new NpciMemberAccount();
        acc.setNpciAccountId(rs.getLong("npci_account_id"));
        acc.setBankId(rs.getLong("bank_id"));
        acc.setBankName(rs.getString("bank_name"));
        acc.setOpeningBalance(rs.getBigDecimal("opening_balance"));
        acc.setCurrentBalance(rs.getBigDecimal("current_balance"));
        acc.setCurrency(rs.getString("currency"));
        return acc;
    }

    private void closeResources(ResultSet rs, PreparedStatement ps, Connection conn) {
        try { if (rs   != null) rs.close();   } catch (SQLException ignored) {}
        try { if (ps   != null) ps.close();   } catch (SQLException ignored) {}
        try { if (conn != null) conn.close();  } catch (SQLException ignored) {}
    }
}