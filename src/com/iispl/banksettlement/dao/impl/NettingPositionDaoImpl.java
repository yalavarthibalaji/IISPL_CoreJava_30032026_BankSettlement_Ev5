package com.iispl.banksettlement.dao.impl;

import com.iispl.banksettlement.dao.NettingPositionDao;
import com.iispl.banksettlement.entity.NettingPosition;
import com.iispl.banksettlement.enums.NetDirection;
import com.iispl.connectionpool.ConnectionPool;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * NettingPositionDaoImpl — JDBC implementation for netting_position table.
 *
 * TABLE: netting_position
 *   position_id, counterparty_bank_id, currency, gross_debit_amount,
 *   gross_credit_amount, net_amount, direction, position_date,
 *   from_bank_name, to_bank_name, created_at, updated_at, created_by, version
 */
public class NettingPositionDaoImpl implements NettingPositionDao {

    private static final String SQL_INSERT =
            "INSERT INTO netting_position " +
            "(counterparty_bank_id, currency, gross_debit_amount, gross_credit_amount, " +
            " net_amount, direction, position_date, from_bank_name, to_bank_name, " +
            " created_at, updated_at, created_by, version) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now(), ?, 0)";

    private static final String SQL_FIND_ALL =
            "SELECT * FROM netting_position ORDER BY position_id ASC";

    @Override
    public void save(NettingPosition position) {
        Connection conn = null;
        PreparedStatement ps = null;
        try {
            conn = ConnectionPool.getConnection();
            ps   = conn.prepareStatement(SQL_INSERT, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1,       position.getCounterpartyBankId());
            ps.setString(2,     position.getCurrency());
            ps.setBigDecimal(3, position.getGrossDebitAmount());
            ps.setBigDecimal(4, position.getGrossCreditAmount());
            ps.setBigDecimal(5, position.getNetAmount());
            ps.setString(6,     position.getDirection().name());
            ps.setDate(7,       Date.valueOf(position.getPositionDate()));
            ps.setString(8,     position.getFromBankName());
            ps.setString(9,     position.getToBankName());
            ps.setString(10,    "NETTING_ENGINE");

            ps.executeUpdate();

            ResultSet keys = ps.getGeneratedKeys();
            if (keys.next()) position.setPositionId(keys.getLong(1));
            keys.close();

            System.out.println("[NettingPositionDao] Saved | positionId: " + position.getPositionId()
                    + " | " + position.getFromBankName() + " → " + position.getToBankName()
                    + " | net: " + position.getNetAmount() + " | dir: " + position.getDirection());
        } catch (SQLException e) {
            throw new RuntimeException("NettingPositionDaoImpl.save() failed: " + e.getMessage(), e);
        } finally {
            closeResources(null, ps, conn);
        }
    }

    @Override
    public List<NettingPosition> findAll() {
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        List<NettingPosition> list = new ArrayList<>();
        try {
            conn = ConnectionPool.getConnection();
            ps   = conn.prepareStatement(SQL_FIND_ALL);
            rs   = ps.executeQuery();
            while (rs.next()) list.add(mapRow(rs));
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("NettingPositionDaoImpl.findAll() failed: " + e.getMessage(), e);
        } finally {
            closeResources(rs, ps, conn);
        }
    }

    private NettingPosition mapRow(ResultSet rs) throws SQLException {
        NettingPosition p = new NettingPosition();
        p.setPositionId(rs.getLong("position_id"));
        p.setCounterpartyBankId(rs.getLong("counterparty_bank_id"));
        p.setCurrency(rs.getString("currency"));
        p.setGrossDebitAmount(rs.getBigDecimal("gross_debit_amount"));
        p.setGrossCreditAmount(rs.getBigDecimal("gross_credit_amount"));
        p.setNetAmount(rs.getBigDecimal("net_amount"));
        p.setDirection(NetDirection.valueOf(rs.getString("direction")));
        p.setPositionDate(rs.getDate("position_date").toLocalDate());
        p.setFromBankName(rs.getString("from_bank_name"));
        p.setToBankName(rs.getString("to_bank_name"));
        return p;
    }

    private void closeResources(ResultSet rs, PreparedStatement ps, Connection conn) {
        try { if (rs   != null) rs.close();   } catch (SQLException ignored) {}
        try { if (ps   != null) ps.close();   } catch (SQLException ignored) {}
        try { if (conn != null) conn.close();  } catch (SQLException ignored) {}
    }
}