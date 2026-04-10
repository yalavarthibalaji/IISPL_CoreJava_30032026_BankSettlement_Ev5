package com.iispl.service;

import java.util.List;

import com.iispl.entity.NettingPosition;
//
/**
 * NettingService — Interface for the inter-bank netting operation.
 *
 * After the settlement engine settles all transactions, the netting engine:
 *   1. Reads all settled transactions and groups them by bank pairs.
 *   2. Computes gross debit, gross credit, and net amount per bank pair.
 *   3. Saves NettingPosition rows to DB.
 *   4. Applies net positions to NPCI member account balances.
 *   5. Prints the final report: "Bank A must pay Rs.X to Bank B".
 */
public interface NettingService {

    /**
     * Runs the full netting cycle.
     * Returns the list of NettingPosition objects computed and saved to DB.
     */
    List<NettingPosition> runNetting();
}