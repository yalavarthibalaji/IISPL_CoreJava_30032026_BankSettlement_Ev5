package com.iispl.banksettlement.service;

import com.iispl.banksettlement.entity.NettingResult;
import com.iispl.connectionpool.ConnectionPool;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * NettingEngine — Computes inter-bank bilateral net positions after settlement.
 *
 * WHAT IS NETTING?
 * ─────────────────────────────────────────────────────────────────────────
 * Instead of settling each transaction individually between banks, we
 * NET all the transactions:
 *
 *   Example:
 *     HDFC → SBI: Rs. 5,00,000
 *     SBI → HDFC: Rs. 2,00,000
 *
 *   Instead of two transfers, we net it:
 *     HDFC pays SBI the net: Rs. 5,00,000 - Rs. 2,00,000 = Rs. 3,00,000
 *
 * HOW THIS CLASS WORKS:
 * ─────────────────────────────────────────────────────────────────────────
 * 1. Read all SETTLED incoming_transaction records from the DB.
 *    (Each settled record has fromBank and toBank in normalized_payload)
 *
 * 2. Build a bilateralMap: Map where key = "BankA|BankB" and value = net amount.
 *    For each transaction:
 *      - fromBank is debited (sent money)
 *      - toBank is credited (received money)
 *
 *    Key is always sorted alphabetically so "HDFC|SBI" and "SBI|HDFC"
 *    don't create two separate entries.
 *
 * 3. Convert the bilateralMap to a List<NettingResult>.
 *    Each NettingResult says: "Bank X must pay Y amount to Bank Z".
 *    (If net is negative, the direction flips.)
 *
 * PACKAGE: com.iispl.banksettlement.service
 */
public class NettingEngine {

    // Load all settled incoming transactions and their fromBank/toBank from normalized_payload
    private static final String SQL_LOAD_SETTLED =
            "SELECT normalized_payload, amount " +
            "FROM incoming_transaction " +
            "WHERE processing_status = 'PROCESSED' " +
            "ORDER BY incoming_txn_id ASC";

    // -----------------------------------------------------------------------
    // Main entry point
    // -----------------------------------------------------------------------

    /**
     * Computes all bilateral net positions from settled transactions.
     *
     * Steps:
     * 1. Load all PROCESSED incoming_transaction rows from DB.
     * 2. Parse fromBank and toBank from each normalized_payload.
     * 3. Build bilateral net positions.
     * 4. Convert to List<NettingResult> and return.
     *
     * @return List of NettingResult — each entry is one bank-to-bank payment obligation
     */
    public List<NettingResult> computeNetting() {

        System.out.println("\n================================================");
        System.out.println("  NETTING ENGINE — COMPUTING BILATERAL POSITIONS");
        System.out.println("  Date: " + LocalDate.now());
        System.out.println("================================================\n");

        // bilateralMap key = "BankA|BankB" (always alphabetical)
        // bilateralMap value = net amount (positive means BankA pays BankB)
        Map<String, BigDecimal> bilateralMap = new HashMap<>();

        loadAndAccumulateTransactions(bilateralMap);

        List<NettingResult> results = convertToNettingResults(bilateralMap);

        printNettingReport(results);

        return results;
    }

    // -----------------------------------------------------------------------
    // Step 1 + 2: Load transactions and build bilateral map
    // -----------------------------------------------------------------------

    private void loadAndAccumulateTransactions(Map<String, BigDecimal> bilateralMap) {
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;

        int count = 0;
        int skipped = 0;

        try {
            conn = ConnectionPool.getConnection();
            ps = conn.prepareStatement(SQL_LOAD_SETTLED);
            rs = ps.executeQuery();

            while (rs.next()) {
                String normalizedPayload = rs.getString("normalized_payload");
                BigDecimal amount = rs.getBigDecimal("amount");

                String fromBank = extractJsonField(normalizedPayload, "fromBank");
                String toBank   = extractJsonField(normalizedPayload, "toBank");

                // Skip if fromBank or toBank is missing or blank
                if (fromBank == null || fromBank.trim().isEmpty()
                        || toBank == null || toBank.trim().isEmpty()) {
                    skipped++;
                    continue;
                }

                // Skip self-transfers (same bank on both sides — no net movement)
                if (fromBank.equalsIgnoreCase(toBank)) {
                    // Still count it, but no inter-bank obligation
                    count++;
                    continue;
                }

                // Build the map key — always sorted alphabetically so both directions
                // map to the same key
                String bankA = fromBank.trim();
                String bankB = toBank.trim();

                String mapKey;
                BigDecimal netDelta;

                if (bankA.compareToIgnoreCase(bankB) <= 0) {
                    // Key = "bankA|bankB", bankA pays bankB → positive
                    mapKey = bankA + "|" + bankB;
                    netDelta = amount;
                } else {
                    // Key = "bankB|bankA", bankA pays bankB means bankB receives → negative from bankB's perspective
                    // This means bankA owes bankB, so from bankB→bankA perspective it is NEGATIVE
                    mapKey = bankB + "|" + bankA;
                    netDelta = amount.negate();
                }

                BigDecimal existing = bilateralMap.getOrDefault(mapKey, BigDecimal.ZERO);
                bilateralMap.put(mapKey, existing.add(netDelta));
                count++;
            }

            System.out.println("[NettingEngine] Loaded " + count + " transaction(s) for netting.");
            if (skipped > 0) {
                System.out.println("[NettingEngine] Skipped " + skipped
                        + " transaction(s) — missing fromBank/toBank in normalizedPayload.");
            }

        } catch (SQLException e) {
            throw new RuntimeException("[NettingEngine] Failed to load settled transactions: " + e.getMessage(), e);
        } finally {
            closeResources(rs, ps, conn);
        }
    }

    // -----------------------------------------------------------------------
    // Step 3: Convert bilateral map to NettingResult list
    // -----------------------------------------------------------------------

    private List<NettingResult> convertToNettingResults(Map<String, BigDecimal> bilateralMap) {
        List<NettingResult> results = new ArrayList<>();

        for (Map.Entry<String, BigDecimal> entry : bilateralMap.entrySet()) {
            String[] banks = entry.getKey().split("\\|");
            String bankA = banks[0]; // alphabetically first bank
            String bankB = banks[1]; // alphabetically second bank
            BigDecimal netAmount = entry.getValue();

            if (netAmount.compareTo(BigDecimal.ZERO) == 0) {
                // Perfectly netted — no payment needed
                continue;
            }

            NettingResult result;

            if (netAmount.compareTo(BigDecimal.ZERO) > 0) {
                // Positive → bankA owes bankB
                result = new NettingResult(bankA, bankB, netAmount, LocalDate.now());
            } else {
                // Negative → bankB owes bankA (flip direction, use absolute value)
                result = new NettingResult(bankB, bankA, netAmount.abs(), LocalDate.now());
            }

            results.add(result);
        }

        return results;
    }

    // -----------------------------------------------------------------------
    // Print netting report
    // -----------------------------------------------------------------------

    private void printNettingReport(List<NettingResult> results) {
        System.out.println("\n[NettingEngine] ══════════════════════════════════");
        System.out.println("[NettingEngine]  INTER-BANK PAYMENT OBLIGATIONS");
        System.out.println("[NettingEngine] ══════════════════════════════════");

        if (results.isEmpty()) {
            System.out.println("[NettingEngine] No inter-bank net obligations found.");
            System.out.println("[NettingEngine] (All transactions may be within the same bank, or no data.)");
        } else {
            System.out.println();
            for (NettingResult result : results) {
                System.out.println("[NettingEngine]  " + result.getFromBank()
                        + "  →  MUST PAY  →  Rs. " + String.format("%,.2f", result.getNetAmount())
                        + "  →  TO  →  " + result.getToBank());
            }
        }

        System.out.println("[NettingEngine] ══════════════════════════════════\n");
    }

    // -----------------------------------------------------------------------
    // Pure Java JSON field extractor (no external libraries)
    // -----------------------------------------------------------------------

    private String extractJsonField(String json, String fieldName) {
        if (json == null || fieldName == null) return null;

        String searchKey = "\"" + fieldName + "\":";
        int keyIndex = json.indexOf(searchKey);
        if (keyIndex == -1) return null;

        int valueStart = keyIndex + searchKey.length();
        while (valueStart < json.length() && json.charAt(valueStart) == ' ') {
            valueStart++;
        }

        boolean isQuoted = (valueStart < json.length() && json.charAt(valueStart) == '"');

        if (isQuoted) {
            int openQuote  = valueStart;
            int closeQuote = json.indexOf('"', openQuote + 1);
            if (closeQuote == -1) return null;
            return json.substring(openQuote + 1, closeQuote);
        } else {
            int endIndex = json.indexOf(',', valueStart);
            if (endIndex == -1) endIndex = json.indexOf('}', valueStart);
            if (endIndex == -1) endIndex = json.length();
            return json.substring(valueStart, endIndex).trim();
        }
    }

    // -----------------------------------------------------------------------
    // Close JDBC resources safely
    // -----------------------------------------------------------------------

    private void closeResources(ResultSet rs, PreparedStatement ps, Connection conn) {
        try { if (rs   != null) rs.close();   } catch (SQLException ignored) {}
        try { if (ps   != null) ps.close();   } catch (SQLException ignored) {}
        try { if (conn != null) conn.close();  } catch (SQLException ignored) {}
    }
}