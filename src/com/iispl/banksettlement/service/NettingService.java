package com.iispl.banksettlement.service;

import com.iispl.banksettlement.entity.NettingResult;

import java.util.List;

/**
 * NettingService — Interface for the inter-bank netting operation.
 *
 * Declares WHAT the netting service does.
 * NettingServiceImpl decides HOW.
 */
public interface NettingService {

    /**
     * Computes bilateral net positions from all settled transactions.
     * Returns a list of payment obligations — each says which bank
     * must pay how much to which other bank.
     *
     * @return List of NettingResult
     */
    List<NettingResult> runNetting();
}