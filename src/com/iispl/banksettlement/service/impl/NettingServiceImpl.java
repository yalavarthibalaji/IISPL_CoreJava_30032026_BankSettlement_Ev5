package com.iispl.banksettlement.service.impl;

import com.iispl.banksettlement.entity.NettingPosition;
import com.iispl.banksettlement.service.NettingEngine;
import com.iispl.banksettlement.service.NettingService;

import java.util.List;

/**
 * NettingServiceImpl — Implements NettingService.
 *
 * Delegates all logic to NettingEngine.
 * This class exists to follow the Service / ServiceImpl pattern
 * used throughout the project.
 *
 * HOW TO USE:
 *   NettingService nettingService = new NettingServiceImpl();
 *   List<NettingPosition> positions = nettingService.runNetting();
 */
public class NettingServiceImpl implements NettingService {

    private final NettingEngine nettingEngine;

    public NettingServiceImpl() {
        this.nettingEngine = new NettingEngine();
    }

    @Override
    public List<NettingPosition> runNetting() {
        return nettingEngine.runNetting();
    }
}