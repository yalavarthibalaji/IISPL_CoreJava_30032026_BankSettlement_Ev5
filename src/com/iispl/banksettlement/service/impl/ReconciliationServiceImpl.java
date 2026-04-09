package com.iispl.banksettlement.service.impl;

import com.iispl.banksettlement.dao.NettingPositionDao;
import com.iispl.banksettlement.dao.NpciMemberAccountDao;
import com.iispl.banksettlement.dao.ReconciliationEntryDao;
import com.iispl.banksettlement.dao.impl.NettingPositionDaoImpl;
import com.iispl.banksettlement.dao.impl.NpciMemberAccountDaoImpl;
import com.iispl.banksettlement.dao.impl.ReconciliationEntryDaoImpl;
import com.iispl.banksettlement.entity.NettingPosition;
import com.iispl.banksettlement.entity.NpciMemberAccount;
import com.iispl.banksettlement.entity.ReconciliationEntry;
import com.iispl.banksettlement.enums.NetDirection;
import com.iispl.banksettlement.enums.ReconStatus;
import com.iispl.banksettlement.service.ReconciliationService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import com.iispl.banksettlement.utility.PhaseLogger;

/**
 * ReconciliationServiceImpl — Implements ReconciliationService.
 *
 * HOW RECONCILIATION WORKS (step by step):
 * ─────────────────────────────────────────────────────────────────────────
 * STEP 1 — Load all NpciMemberAccount rows from DB.
 *   Each row has: bankName, openingBalance, currentBalance.
 *
 * STEP 2 — Load all NettingPosition rows from DB.
 *   Each row says: "fromBank pays netAmount to toBank".
 *
 * STEP 3 — For each bank, compute expectedBalance:
 *   Start from opening_balance.
 *   For every NET_DEBIT position where fromBank = this bank:
 *     expectedBalance -= netAmount  (this bank PAID money out)
 *   For every NET_DEBIT position where toBank = this bank:
 *     expectedBalance += netAmount  (this bank RECEIVED money in)
 *
 * STEP 4 — Compare expectedBalance to actualBalance (current_balance in DB):
 *   variance = actualBalance - expectedBalance
 *   If variance == 0  → MATCHED
 *   If variance != 0  → UNMATCHED
 *
 * STEP 5 — Save one ReconciliationEntry per bank to DB.
 *
 * STEP 6 — Print the reconciliation report.
 *
 * PACKAGE: com.iispl.banksettlement.service.impl
 */
public class ReconciliationServiceImpl implements ReconciliationService {
    private static final Logger LOGGER = Logger.getLogger(ReconciliationServiceImpl.class.getName());

    private final NpciMemberAccountDao  npciAccountDao;
    private final NettingPositionDao    nettingPositionDao;
    private final ReconciliationEntryDao reconciliationEntryDao;

    public ReconciliationServiceImpl() {
        this.npciAccountDao          = new NpciMemberAccountDaoImpl();
        this.nettingPositionDao      = new NettingPositionDaoImpl();
        this.reconciliationEntryDao  = new ReconciliationEntryDaoImpl();
        PhaseLogger.configureReadableConsole(LOGGER);
    }

    @Override
    public List<ReconciliationEntry> runReconciliation() {

        LOGGER.info("Reconciliation engine started on " + LocalDate.now());

        // STEP 1: Load all NPCI member accounts
        List<NpciMemberAccount> accounts = npciAccountDao.findAll();
        if (accounts.isEmpty()) {
            LOGGER.info("No NPCI member accounts found. Run phase3_schema_changes.sql first.");
            return new ArrayList<>();
        }

        // STEP 2: Load all netting positions
        List<NettingPosition> positions = nettingPositionDao.findAll();
        if (positions.isEmpty()) {
            LOGGER.info("No netting positions found. Run netting engine first.");
        }

        List<ReconciliationEntry> entries = new ArrayList<>();

        // STEP 3 + 4 + 5: For each bank compute expected balance, compare, save
        for (NpciMemberAccount account : accounts) {

            BigDecimal openingBalance = account.getOpeningBalance();
            BigDecimal actualBalance  = account.getCurrentBalance();
            String     bankName       = account.getBankName();

            // Compute expected balance by applying all relevant net positions
            BigDecimal expectedBalance = openingBalance;

            for (NettingPosition pos : positions) {
                if (pos.getDirection() != NetDirection.NET_DEBIT) continue;
                if (pos.getNetAmount() == null
                        || pos.getNetAmount().compareTo(BigDecimal.ZERO) == 0) continue;

                if (bankName.equalsIgnoreCase(pos.getFromBankName())) {
                    // This bank PAID → balance decreases
                    expectedBalance = expectedBalance.subtract(pos.getNetAmount());
                } else if (bankName.equalsIgnoreCase(pos.getToBankName())) {
                    // This bank RECEIVED → balance increases
                    expectedBalance = expectedBalance.add(pos.getNetAmount());
                }
            }

            // Variance = actual - expected
            BigDecimal variance = actualBalance.subtract(expectedBalance);

            // Determine status
            ReconStatus status;
            String remarks;

            if (variance.compareTo(BigDecimal.ZERO) == 0) {
                status  = ReconStatus.MATCHED;
                remarks = "Balance matches expected after applying all netting positions. No discrepancy.";
            } else {
                status  = ReconStatus.UNMATCHED;
                remarks = "Discrepancy found. Expected: " + expectedBalance
                        + " | Actual: " + actualBalance
                        + " | Variance: " + variance
                        + ". Investigate netting positions for " + bankName + ".";
            }

            ReconciliationEntry entry = new ReconciliationEntry(
                    account.getNpciAccountId(),
                    bankName,
                    expectedBalance,
                    actualBalance,
                    variance,
                    status,
                    remarks
            );

            // STEP 5: Save to DB
            reconciliationEntryDao.save(entry);
            entries.add(entry);
        }

        // STEP 6: Print reconciliation report
        printReconciliationReport(entries);

        return entries;
    }

    // -----------------------------------------------------------------------
    // Print reconciliation report
    // -----------------------------------------------------------------------

    private void printReconciliationReport(List<ReconciliationEntry> entries) {

        LOGGER.info("RECONCILIATION REPORT - " + LocalDate.now());
        LOGGER.info(String.format("%-20s | %15s | %15s | %15s | %10s", "Bank", "Opening Bal", "Expected", "Actual", "Status"));

        int matched   = 0;
        int unmatched = 0;

        for (ReconciliationEntry entry : entries) {
            LOGGER.info(String.format("%-20s | %15.2f | %15.2f | %15.2f | %10s",
                    entry.getBankName(),
                    entry.getExpectedAmount(),
                    entry.getExpectedAmount(),
                    entry.getActualAmount(),
                    entry.getReconStatus().name()));

            if (entry.getReconStatus() == ReconStatus.MATCHED) {
                matched++;
            } else {
                unmatched++;
                LOGGER.warning("UNMATCHED - Variance: "
                        + String.format("%,.2f", entry.getVariance())
                        + " | " + entry.getRemarks());
            }
        }

        LOGGER.info("Total: " + entries.size()
                + " | Matched: " + matched
                + " | Unmatched: " + unmatched);

        if (unmatched == 0) {
            LOGGER.info("All NPCI accounts reconciled successfully.");
        } else {
            LOGGER.warning(unmatched + " account(s) have discrepancies. Investigation needed.");
        }
    }
}