package com.iispl.banksettlement.service.impl;

import com.iispl.banksettlement.entity.Npci;
import com.iispl.banksettlement.entity.NettingResult;
import com.iispl.banksettlement.service.NettingEngine;
import com.iispl.banksettlement.service.NettingService;

import java.util.List;

/**
 * NettingServiceImpl — Implements NettingService.
 *
 * Orchestrates the full post-settlement netting cycle:
 *   1. NettingEngine computes bilateral net obligations.
 *   2. NPCI applies those obligations to update bank settlement account balances.
 *
 * HOW TO USE:
 *   NettingService nettingService = new NettingServiceImpl();
 *   nettingService.runNetting();
 */
public class NettingServiceImpl implements NettingService {

    private final NettingEngine nettingEngine;
    private final Npci npci;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public NettingServiceImpl() {
        this.nettingEngine = new NettingEngine();
        // Create NPCI with default member bank accounts
        this.npci = new Npci();
    }

    // -----------------------------------------------------------------------
    // runNetting — main entry point
    // -----------------------------------------------------------------------

    /**
     * Full netting pipeline:
     *   Step 1 — NettingEngine reads settled transactions, computes net obligations.
     *   Step 2 — NPCI applies those obligations, updates each bank's balance.
     *
     * @return List of NettingResult (inter-bank payment obligations)
     */
    @Override
    public List<NettingResult> runNetting() {

        System.out.println("\n================================================");
        System.out.println("  POST-SETTLEMENT NETTING — STARTING");
        System.out.println("================================================\n");

        // Step 1: Compute netting
        List<NettingResult> nettingResults = nettingEngine.computeNetting();

        // Step 2: Apply to NPCI bank accounts
        npci.applyNettingResults(nettingResults);

        System.out.println("\n================================================");
        System.out.println("  POST-SETTLEMENT NETTING — COMPLETE");
        System.out.println("================================================\n");

        return nettingResults;
    }

    // -----------------------------------------------------------------------
    // Getter (for testing if needed)
    // -----------------------------------------------------------------------

    public Npci getNpci() {
        return npci;
    }
}