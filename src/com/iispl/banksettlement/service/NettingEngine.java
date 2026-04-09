package com.iispl.banksettlement.service;

import com.iispl.banksettlement.dao.BankDao;
import com.iispl.banksettlement.dao.NettingPositionDao;
import com.iispl.banksettlement.dao.NpciMemberAccountDao;
import com.iispl.banksettlement.dao.impl.BankDaoImpl;
import com.iispl.banksettlement.dao.impl.NettingPositionDaoImpl;
import com.iispl.banksettlement.dao.impl.NpciMemberAccountDaoImpl;
import com.iispl.banksettlement.entity.Bank;
import com.iispl.banksettlement.entity.Npci;
import com.iispl.banksettlement.entity.NettingPosition;
import com.iispl.banksettlement.entity.NpciMemberAccount;
import com.iispl.banksettlement.enums.NetDirection;
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
 * NettingEngine — Computes bilateral net positions between banks after settlement.
 *
 * HOW IT WORKS (step by step):
 * ─────────────────────────────────────────────────────────────────────────
 * STEP 1 — Load settled transactions from DB.
 *   Reads all incoming_transaction rows with processing_status = 'PROCESSED'.
 *   Extracts fromBank, toBank, and amount from each row's normalized_payload.
 *
 * STEP 2 — Build bilateral gross position map.
 *   For each pair of banks (BankA, BankB), we track:
 *     grossDebit  → total amount BankA sent TO BankB
 *     grossCredit → total amount BankA received FROM BankB
 *
 *   Map key = "BankA|BankB" (alphabetically sorted so HDFC|SBI and SBI|HDFC
 *   are the same pair).
 *
 * STEP 3 — Convert map to NettingPosition list.
 *   For each bank pair:
 *     netAmount = grossCreditAmount - grossDebitAmount
 *     direction:
 *       netAmount > 0 → NET_CREDIT (BankA receives money)
 *       netAmount < 0 → NET_DEBIT  (BankA pays money, flip direction)
 *       netAmount = 0 → FLAT
 *   counterpartyBankId = bank.bank_id of the toBank (looked up from DB).
 *
 * STEP 4 — Save each NettingPosition to DB (netting_position table).
 *
 * STEP 5 — Apply positions to NPCI member account balances.
 *   For each NET_DEBIT position:
 *     fromBank's NPCI account balance decreases by netAmount.
 *     toBank's   NPCI account balance increases by netAmount.
 *   Updated balances are saved back to npci_bank_account table.
 *
 * STEP 6 — Print the human-readable settlement report.
 *
 * PACKAGE: com.iispl.banksettlement.service
 */
public class NettingEngine {

    // Loads processed incoming transactions for netting computation
    private static final String SQL_LOAD_PROCESSED =
            "SELECT normalized_payload, amount " +
            "FROM incoming_transaction " +
            "WHERE processing_status = 'PROCESSED' " +
            "ORDER BY incoming_txn_id ASC";

    // DAOs
    private final BankDao                bankDao;
    private final NettingPositionDao     nettingPositionDao;
    private final NpciMemberAccountDao   npciAccountDao;

    public NettingEngine() {
        this.bankDao            = new BankDaoImpl();
        this.nettingPositionDao = new NettingPositionDaoImpl();
        this.npciAccountDao     = new NpciMemberAccountDaoImpl();
    }

    // -----------------------------------------------------------------------
    // runNetting — main entry point, called by NettingServiceImpl
    // -----------------------------------------------------------------------

    /**
     * Runs the complete netting cycle and returns all computed NettingPositions.
     */
    public List<NettingPosition> runNetting() {

        System.out.println("\n================================================");
        System.out.println("  NETTING ENGINE — STARTING");
        System.out.println("  Date: " + LocalDate.now());
        System.out.println("================================================\n");

        // STEP 1 + 2: Load transactions and build gross bilateral map
        // Key = "BankA|BankB" (alphabetically sorted)
        // Value = BigDecimal[2] where [0] = grossDebit, [1] = grossCredit
        // (from the perspective of BankA, the first in alphabetical order)
        Map<String, BigDecimal[]> bilateralMap = new HashMap<>();
        loadAndBuildBilateralMap(bilateralMap);

        if (bilateralMap.isEmpty()) {
            System.out.println("[NettingEngine] No PROCESSED transactions found. Run SettlementEngine first.");
            return new ArrayList<>();
        }

        // STEP 3 + 4: Convert map to NettingPosition list and save to DB
        List<NettingPosition> positions = buildAndSavePositions(bilateralMap);

        // STEP 5: Apply positions to NPCI member accounts
        applyToNpciAccounts(positions);

        // STEP 6: Print final report
        printFinalReport(positions);

        return positions;
    }

    // -----------------------------------------------------------------------
    // STEP 1 + 2: Load transactions from DB and build bilateral gross map
    // -----------------------------------------------------------------------

    private void loadAndBuildBilateralMap(Map<String, BigDecimal[]> bilateralMap) {

        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        int count   = 0;
        int skipped = 0;

        try {
            conn = ConnectionPool.getConnection();
            ps   = conn.prepareStatement(SQL_LOAD_PROCESSED);
            rs   = ps.executeQuery();

            while (rs.next()) {
                String normalizedPayload = rs.getString("normalized_payload");
                BigDecimal amount        = rs.getBigDecimal("amount");

                String fromBank = extractJsonField(normalizedPayload, "fromBank");
                String toBank   = extractJsonField(normalizedPayload, "toBank");

                // Skip if bank names are missing or the same bank on both sides
                if (fromBank == null || fromBank.trim().isEmpty()
                        || toBank == null || toBank.trim().isEmpty()) {
                    skipped++;
                    continue;
                }

                fromBank = fromBank.trim();
                toBank   = toBank.trim();

                // Same bank on both sides — internal transaction, no inter-bank net movement
                if (fromBank.equalsIgnoreCase(toBank)) {
                    count++;
                    continue;
                }

                // Sort alphabetically to create a consistent key for both directions
                String bankA, bankB;
                if (fromBank.compareToIgnoreCase(toBank) <= 0) {
                    bankA = fromBank;
                    bankB = toBank;
                } else {
                    bankA = toBank;
                    bankB = fromBank;
                }

                String mapKey = bankA + "|" + bankB;

                // Get or create the array: [0] = grossDebit for bankA, [1] = grossCredit for bankA
                BigDecimal[] amounts = bilateralMap.getOrDefault(mapKey, new BigDecimal[]{
                        BigDecimal.ZERO, BigDecimal.ZERO
                });

                if (fromBank.equalsIgnoreCase(bankA)) {
                    // fromBank = bankA → bankA is SENDING money → add to bankA grossDebit
                    amounts[0] = amounts[0].add(amount);
                } else {
                    // fromBank = bankB → bankA is RECEIVING money → add to bankA grossCredit
                    amounts[1] = amounts[1].add(amount);
                }

                bilateralMap.put(mapKey, amounts);
                count++;
            }

            System.out.println("[NettingEngine] Loaded " + count + " transaction(s) for netting.");
            if (skipped > 0) {
                System.out.println("[NettingEngine] Skipped " + skipped
                        + " transaction(s) — missing fromBank/toBank in normalizedPayload.");
            }

        } catch (SQLException e) {
            throw new RuntimeException("[NettingEngine] Failed to load transactions: " + e.getMessage(), e);
        } finally {
            closeResources(rs, ps, conn);
        }
    }

    // -----------------------------------------------------------------------
    // STEP 3 + 4: Convert bilateral map to NettingPosition list and save to DB
    // -----------------------------------------------------------------------

    private List<NettingPosition> buildAndSavePositions(Map<String, BigDecimal[]> bilateralMap) {

        List<NettingPosition> positions = new ArrayList<>();

        System.out.println("\n[NettingEngine] Computing bilateral net positions...\n");

        for (Map.Entry<String, BigDecimal[]> entry : bilateralMap.entrySet()) {

            String[] banks    = entry.getKey().split("\\|");
            String bankAName  = banks[0];  // alphabetically first
            String bankBName  = banks[1];  // alphabetically second

            BigDecimal grossDebitA  = entry.getValue()[0]; // bankA sent to bankB
            BigDecimal grossCreditA = entry.getValue()[1]; // bankA received from bankB

            // Net = what bankA received - what bankA sent
            BigDecimal netAmount = grossCreditA.subtract(grossDebitA);

            // Determine who pays whom and what direction to use
            String fromBankName;
            String toBankName;
            BigDecimal absNet;
            NetDirection direction;

            if (netAmount.compareTo(BigDecimal.ZERO) > 0) {
                // bankA is the NET CREDITOR — bankB must pay bankA
                fromBankName = bankBName;
                toBankName   = bankAName;
                absNet       = netAmount;
                direction    = NetDirection.NET_DEBIT; // bankB is NET_DEBIT (pays)
            } else if (netAmount.compareTo(BigDecimal.ZERO) < 0) {
                // bankA is the NET DEBTOR — bankA must pay bankB
                fromBankName = bankAName;
                toBankName   = bankBName;
                absNet       = netAmount.abs();
                direction    = NetDirection.NET_DEBIT; // bankA is NET_DEBIT (pays)
            } else {
                // Perfectly balanced — FLAT
                fromBankName = bankAName;
                toBankName   = bankBName;
                absNet       = BigDecimal.ZERO;
                direction    = NetDirection.FLAT;
            }

            // Look up counterpartyBankId (toBank's bank_id in the bank table)
            Bank toBank = bankDao.findByName(toBankName);
            Long counterpartyBankId = (toBank != null) ? toBank.getBankId() : 0L;

            if (toBank == null) {
                System.out.println("[NettingEngine] WARNING: Bank not found in DB for name: "
                        + toBankName + " | using counterpartyBankId = 0");
            }

            NettingPosition position = new NettingPosition(
                    counterpartyBankId,
                    fromBankName,
                    toBankName,
                    grossDebitA,
                    grossCreditA,
                    absNet,
                    direction,
                    LocalDate.now()
            );

            // Save to DB
            nettingPositionDao.save(position);
            positions.add(position);
        }

        System.out.println("[NettingEngine] " + positions.size() + " netting position(s) saved to DB.");
        return positions;
    }

    // -----------------------------------------------------------------------
    // STEP 5: Apply net positions to NPCI member account balances
    // -----------------------------------------------------------------------

    private void applyToNpciAccounts(List<NettingPosition> positions) {

        // Load all NPCI member accounts from DB
        List<NpciMemberAccount> allAccounts = npciAccountDao.findAll();

        if (allAccounts.isEmpty()) {
            System.out.println("[NettingEngine] WARNING: No NPCI member accounts found in DB.");
            System.out.println("[NettingEngine] Run phase3_schema_changes.sql first.");
            return;
        }

        // Save current balance as the opening balance BEFORE applying netting
        // This is needed for reconciliation later
        for (NpciMemberAccount account : allAccounts) {
            account.setOpeningBalance(account.getCurrentBalance());
        }

        // Create NPCI entity and load the accounts into it
        Npci npci = new Npci(allAccounts);

        // Apply all net positions to the in-memory accounts
        npci.applyNettingPositions(positions);

        // Persist the updated balances back to DB
        System.out.println("[NettingEngine] Saving updated NPCI balances to DB...");
        for (NpciMemberAccount account : allAccounts) {
            npciAccountDao.updateBalances(account);
        }
        System.out.println("[NettingEngine] NPCI balances updated in DB.");
    }

    // -----------------------------------------------------------------------
    // STEP 6: Print the final human-readable settlement report
    // -----------------------------------------------------------------------

    private void printFinalReport(List<NettingPosition> positions) {

        System.out.println("\n================================================");
        System.out.println("  NETTING ENGINE — INTER-BANK PAYMENT REPORT");
        System.out.println("  Date: " + LocalDate.now());
        System.out.println("================================================");

        boolean hasPayments = false;

        for (NettingPosition pos : positions) {
            if (pos.getDirection() == NetDirection.NET_DEBIT
                    && pos.getNetAmount().compareTo(BigDecimal.ZERO) > 0) {

                System.out.println("\n  " + pos.getFromBankName()
                        + "  →  MUST PAY  →  Rs. "
                        + String.format("%,.2f", pos.getNetAmount())
                        + "  →  TO  →  " + pos.getToBankName());

                System.out.println("    Gross amount " + pos.getFromBankName()
                        + " sent:     Rs. " + String.format("%,.2f", pos.getGrossDebitAmount()));
                System.out.println("    Gross amount " + pos.getFromBankName()
                        + " received: Rs. " + String.format("%,.2f", pos.getGrossCreditAmount()));
                System.out.println("    Net payable:              Rs. "
                        + String.format("%,.2f", pos.getNetAmount()));

                hasPayments = true;

            } else if (pos.getDirection() == NetDirection.FLAT) {
                System.out.println("\n  " + pos.getFromBankName() + "  ↔  " + pos.getToBankName()
                        + "  →  FLAT (no net payment needed)");
            }
        }

        if (!hasPayments && positions.stream()
                .noneMatch(p -> p.getDirection() == NetDirection.NET_DEBIT)) {
            System.out.println("\n  No inter-bank payments needed — all positions are FLAT.");
        }

        System.out.println("\n================================================\n");
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