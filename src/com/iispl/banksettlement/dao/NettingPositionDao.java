package com.iispl.banksettlement.dao;

import com.iispl.banksettlement.entity.NettingPosition;
import java.util.List;

/**
 * NettingPositionDao — Interface for netting_position table operations.
 */
public interface NettingPositionDao {
    void save(NettingPosition position);
    List<NettingPosition> findAll();
}
