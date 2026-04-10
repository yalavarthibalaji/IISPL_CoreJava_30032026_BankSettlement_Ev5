package com.iispl.dao;

import java.util.List;

import com.iispl.entity.NettingPosition;

/**
 * NettingPositionDao — Interface for netting_position table operations.
 */
public interface NettingPositionDao {
    void save(NettingPosition position);
    List<NettingPosition> findAll();
}
