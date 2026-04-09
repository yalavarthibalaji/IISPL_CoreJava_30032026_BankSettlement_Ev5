package com.iispl.banksettlement.dao.impl;

import com.iispl.banksettlement.dao.ReconciliationEntryDao;
import com.iispl.banksettlement.entity.ReconciliationEntry;
import com.iispl.banksettlement.enums.ReconStatus;
import com.iispl.connectionpool.ConnectionPool;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * ReconciliationEntryDaoImpl — JDBC implementation for reconciliation_entry table.
 *
 * TABLE: reconciliation_entry
 *   entry_id, reconciliation_date, account_id, expected_amount, actual_amount,
 *   variance, recon_status, remarks, created_at, updated_at, created_by, version
 *
 * NOTE: account_id here maps to npci_bank_account.npci_account_id
 *       (not the customer account table).
 */
public class ReconciliationEntryDaoImpl implements ReconciliationEntryDao {
    private static final Logger LOGGER = Logger.getLogger(ReconciliationEntryDaoImpl.class.getName());

    private static final String SQL_INSERT =
            "INSERT INTO reconciliation_entry " +
            "(reconciliation_date, account_id, expected_amount, actual_amount, " +
            " variance, recon_status, remarks, created_at, updated_at, created_by, version) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, now(), now(), ?, 0)";

    private static final String SQL_FIND_ALL =
            "SELECT * FROM reconciliation_entry ORDER BY entry_id ASC";

    @Override
    public void save(ReconciliationEntry entry) {
        Connection conn = null;
        PreparedStatement ps = null;
        try {
            conn = ConnectionPool.getConnection();
            ps   = conn.prepareStatement(SQL_INSERT, Statement.RETURN_GENERATED_KEYS);
            ps.setDate(1,       Date.valueOf(entry.getReconciliationDate()));
            ps.setLong(2,       entry.getAccountId());
            ps.setBigDecimal(3, entry.getExpectedAmount());
            ps.setBigDecimal(4, entry.getActualAmount());
            ps.setBigDecimal(5, entry.getVariance());
            ps.setString(6,     entry.getReconStatus().name());
            ps.setString(7,     entry.getRemarks());
            ps.setString(8,     "RECONCILIATION_ENGINE");

            ps.executeUpdate();

            ResultSet keys = ps.getGeneratedKeys();
            if (keys.next()) entry.setEntryId(keys.getLong(1));
            keys.close();

            LOGGER.fine("[ReconciliationEntryDao] Saved | entryId: " + entry.getEntryId()
                    + " | bank: " + entry.getBankName()
                    + " | expected: " + entry.getExpectedAmount()
                    + " | actual: " + entry.getActualAmount()
                    + " | variance: " + entry.getVariance()
                    + " | status: " + entry.getReconStatus());
        } catch (SQLException e) {
            throw new RuntimeException("ReconciliationEntryDaoImpl.save() failed: " + e.getMessage(), e);
        } finally {
            closeResources(null, ps, conn);
        }
    }

    @Override
    public List<ReconciliationEntry> findAll() {
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        List<ReconciliationEntry> list = new ArrayList<>();
        try {
            conn = ConnectionPool.getConnection();
            ps   = conn.prepareStatement(SQL_FIND_ALL);
            rs   = ps.executeQuery();
            while (rs.next()) list.add(mapRow(rs));
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("ReconciliationEntryDaoImpl.findAll() failed: " + e.getMessage(), e);
        } finally {
            closeResources(rs, ps, conn);
        }
    }

    private ReconciliationEntry mapRow(ResultSet rs) throws SQLException {
        ReconciliationEntry e = new ReconciliationEntry();
        e.setEntryId(rs.getLong("entry_id"));
        e.setReconciliationDate(rs.getDate("reconciliation_date").toLocalDate());
        e.setAccountId(rs.getLong("account_id"));
        e.setExpectedAmount(rs.getBigDecimal("expected_amount"));
        e.setActualAmount(rs.getBigDecimal("actual_amount"));
        e.setVariance(rs.getBigDecimal("variance"));
        e.setReconStatus(ReconStatus.valueOf(rs.getString("recon_status")));
        e.setRemarks(rs.getString("remarks"));
        return e;
    }

    private void closeResources(ResultSet rs, PreparedStatement ps, Connection conn) {
        try { if (rs   != null) rs.close();   } catch (SQLException ignored) {}
        try { if (ps   != null) ps.close();   } catch (SQLException ignored) {}
        try { if (conn != null) conn.close();  } catch (SQLException ignored) {}
    }
}