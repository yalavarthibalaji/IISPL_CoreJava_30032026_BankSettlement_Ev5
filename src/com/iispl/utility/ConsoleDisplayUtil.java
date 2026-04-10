package com.iispl.utility;

import com.iispl.connectionpool.ConnectionPool;
import com.iispl.exception.AccountNotFoundException;
import com.iispl.exception.TransactionNotFoundException;

import java.math.BigDecimal;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ConsoleDisplayUtil — Prints professional, formatted tables to the console.
 *
 * FEATURES:
 *   - Shows all DB tables in neat column-aligned format
 *   - Uses Java Streams for filtering data (filter by source, status, bank, etc.)
 *   - Throws TransactionNotFoundException / AccountNotFoundException when
 *     search finds no matching records
 *
 * ALL METHODS ARE STATIC — no need to create an object to call them.
 *
 * TABLES:
 *   showIncomingTransactions()       — incoming_transaction
 *   showSettlementBatches()          — settlement_batch
 *   showSettlementRecords()          — settlement_record
 *   showCreditTransactions()         — credit_transaction
 *   showDebitTransactions()          — debit_transaction
 *   showInterBankTransactions()      — interbank_transaction
 *   showReversalTransactions()       — reversal_transaction
 *   showNettingPositions()           — netting_position
 *   showReconciliationEntries()      — reconciliation_entry (JOIN bank)
 *   showNpciMemberAccounts()         — npci_bank_account (JOIN bank)
 *
 * STREAM FILTER METHODS:
 *   showIncomingBySource(source)     — filter incoming by CBS/RTGS/NEFT/UPI/FINTECH
 *   showIncomingByStatus(status)     — filter incoming by PROCESSED/QUEUED/DUPLICATE etc.
 *   showTransactionsByType(type)     — filter credit/debit/interbank/reversal by status
 *   showNpciAccountByBank(bankName)  — filter NPCI accounts by partial bank name
 *   showReconByStatus(status)        — filter reconciliation by MATCHED/UNMATCHED
 */
public class ConsoleDisplayUtil {

    // -----------------------------------------------------------------------
    // Inner class to hold one row of data loaded from DB
    // (used for Stream filtering before printing)
    // -----------------------------------------------------------------------

    /**
     * IncomingRow — holds one row from incoming_transaction for Stream operations.
     */
    private static class IncomingRow {
        long   id;
        String source;
        String reference;
        String type;
        BigDecimal amount;
        String status;
        String valueDate;
    }

    /**
     * NpciRow — holds one row from npci_bank_account + bank JOIN.
     */
    private static class NpciRow {
        long   accountId;
        String bankName;
        BigDecimal opening;
        BigDecimal current;
    }

    /**
     * ReconRow — holds one row from reconciliation_entry + bank JOIN.
     */
    private static class ReconRow {
        long   entryId;
        String bankName;
        BigDecimal expected;
        BigDecimal actual;
        BigDecimal variance;
        String status;
        String remarks;
    }

    // -----------------------------------------------------------------------
    // BANNER AND SEPARATOR HELPERS
    // -----------------------------------------------------------------------

    public static void printBanner(String title) {
        int width = 60;
        System.out.println();
        System.out.println("+" + "=".repeat(width) + "+");
        System.out.printf("|  %-" + (width - 2) + "s  |%n", title);
        System.out.println("+" + "=".repeat(width) + "+");
    }

    private static void printSeparator() {
        System.out.println("-".repeat(62));
    }

    private static void printNoData(String message) {
        System.out.println();
        System.out.println("  [INFO] " + message);
        System.out.println();
    }

    private static void printRowCount(int count) {
        System.out.println();
        System.out.println("  Total rows displayed : " + count);
        System.out.println();
    }

    // -----------------------------------------------------------------------
    // 1. INCOMING TRANSACTIONS (full table)
    // -----------------------------------------------------------------------

    /**
     * Displays all records from incoming_transaction table.
     */
    public static void showIncomingTransactions() {

        String sql =
            "SELECT it.incoming_txn_id, ss.system_code, it.source_ref, " +
            "it.txn_type, it.amount, it.processing_status, it.value_date " +
            "FROM incoming_transaction it " +
            "LEFT JOIN source_system ss ON it.source_system_id = ss.source_system_id " +
            "ORDER BY it.incoming_txn_id ASC LIMIT 100";

        // Load all rows from DB into a List
        List<IncomingRow> rows = loadIncomingRows(sql, null);

        printBanner("INCOMING TRANSACTIONS  [Total loaded: " + rows.size() + "]");
        printIncomingHeader();

        // Use Stream to print each row (no filter — print all)
        rows.stream().forEach(r -> printIncomingRow(r));

        if (rows.isEmpty()) {
            printNoData("No incoming transactions found. Run Ingestion (Pipeline Step 1) first.");
        } else {
            printRowCount(rows.size());
        }
    }

    // -----------------------------------------------------------------------
    // 1a. INCOMING TRANSACTIONS — FILTER BY SOURCE (Stream example)
    // -----------------------------------------------------------------------

    /**
     * Filters incoming transactions by source system using Java Streams.
     *
     * Example: showIncomingBySource("CBS") shows only CBS transactions.
     *
     * HOW STREAM IS USED HERE:
     *   1. Load all rows from DB into a List<IncomingRow>
     *   2. Use stream().filter() to keep only rows matching the source
     *   3. Use collect(Collectors.toList()) to get the filtered result
     *   4. Print the filtered list
     *
     * @param source  Source name to filter by (CBS, RTGS, NEFT, UPI, FINTECH)
     * @throws TransactionNotFoundException if no transactions found for that source
     */
    public static void showIncomingBySource(String source) throws TransactionNotFoundException {

        String sql =
            "SELECT it.incoming_txn_id, ss.system_code, it.source_ref, " +
            "it.txn_type, it.amount, it.processing_status, it.value_date " +
            "FROM incoming_transaction it " +
            "LEFT JOIN source_system ss ON it.source_system_id = ss.source_system_id " +
            "ORDER BY it.incoming_txn_id ASC LIMIT 500";

        // Step 1 — Load all rows into a List
        List<IncomingRow> allRows = loadIncomingRows(sql, null);

        // Step 2 — Use Stream filter: keep only rows where source matches (case insensitive)
        List<IncomingRow> filtered = allRows.stream()
                .filter(row -> source.equalsIgnoreCase(row.source))
                .collect(Collectors.toList());

        // Step 3 — If no records found, throw our custom exception
        if (filtered.isEmpty()) {
            throw new TransactionNotFoundException(
                "No incoming transactions found for source: " + source.toUpperCase(),
                source
            );
        }

        // Step 4 — Print the filtered list
        printBanner("INCOMING TRANSACTIONS — SOURCE: " + source.toUpperCase()
                + "  [" + filtered.size() + " records]");
        printIncomingHeader();

        // Stream again to print each filtered row
        filtered.stream().forEach(r -> printIncomingRow(r));
        printRowCount(filtered.size());
    }

    // -----------------------------------------------------------------------
    // 1b. INCOMING TRANSACTIONS — FILTER BY STATUS (Stream example)
    // -----------------------------------------------------------------------

    /**
     * Filters incoming transactions by processing status using Java Streams.
     *
     * Example: showIncomingByStatus("PROCESSED") shows all processed transactions.
     *
     * @param status  Status to filter by (PROCESSED, QUEUED, DUPLICATE, FAILED etc.)
     * @throws TransactionNotFoundException if no transactions found for that status
     */
    public static void showIncomingByStatus(String status) throws TransactionNotFoundException {

        String sql =
            "SELECT it.incoming_txn_id, ss.system_code, it.source_ref, " +
            "it.txn_type, it.amount, it.processing_status, it.value_date " +
            "FROM incoming_transaction it " +
            "LEFT JOIN source_system ss ON it.source_system_id = ss.source_system_id " +
            "ORDER BY it.incoming_txn_id ASC LIMIT 500";

        List<IncomingRow> allRows = loadIncomingRows(sql, null);

        // Stream filter — keep rows where processing_status matches
        List<IncomingRow> filtered = allRows.stream()
                .filter(row -> status.equalsIgnoreCase(row.status))
                .collect(Collectors.toList());

        if (filtered.isEmpty()) {
            throw new TransactionNotFoundException(
                "No incoming transactions found with status: " + status.toUpperCase(),
                status
            );
        }

        printBanner("INCOMING TRANSACTIONS — STATUS: " + status.toUpperCase()
                + "  [" + filtered.size() + " records]");
        printIncomingHeader();
        filtered.stream().forEach(r -> printIncomingRow(r));
        printRowCount(filtered.size());
    }

    // -----------------------------------------------------------------------
    // INCOMING ROWS — shared helpers
    // -----------------------------------------------------------------------

    /**
     * Loads incoming transaction rows from DB into a List.
     * The extraWhere param is currently unused (kept for future use).
     */
    private static List<IncomingRow> loadIncomingRows(String sql, String unused) {
        List<IncomingRow> rows = new ArrayList<>();
        try (Connection conn = ConnectionPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                IncomingRow row = new IncomingRow();
                row.id        = rs.getLong("incoming_txn_id");
                row.source    = safe(rs.getString("system_code"));
                row.reference = safe(rs.getString("source_ref"));
                row.type      = safe(rs.getString("txn_type"));
                row.amount    = rs.getBigDecimal("amount");
                row.status    = safe(rs.getString("processing_status"));
                Date d        = rs.getDate("value_date");
                row.valueDate = (d != null) ? d.toString() : "N/A";
                rows.add(row);
            }
        } catch (SQLException e) {
            System.out.println("  [ERROR] Could not load incoming transactions: " + e.getMessage());
        }
        return rows;
    }

    private static void printIncomingHeader() {
        System.out.printf("%-6s  %-8s  %-22s  %-10s  %14s  %-12s  %-12s%n",
                "ID", "SOURCE", "REFERENCE", "TYPE", "AMOUNT (INR)", "STATUS", "VALUE DATE");
        printSeparator();
        printSeparator();
    }

    private static void printIncomingRow(IncomingRow r) {
        System.out.printf("%-6d  %-8s  %-22s  %-10s  %14s  %-12s  %-12s%n",
                r.id,
                truncate(r.source, 8),
                truncate(r.reference, 22),
                truncate(r.type, 10),
                formatAmount(r.amount),
                truncate(r.status, 12),
                r.valueDate);
    }

    // -----------------------------------------------------------------------
    // 2. SETTLEMENT BATCHES
    // -----------------------------------------------------------------------

    public static void showSettlementBatches() {
        printBanner("SETTLEMENT BATCHES");

        String sql =
            "SELECT batch_id, batch_date, batch_status, total_transactions, " +
            "total_amount, run_by, run_at " +
            "FROM settlement_batch ORDER BY run_at DESC LIMIT 50";

        System.out.printf("%-22s  %-12s  %-12s  %8s  %16s  %-15s  %-16s%n",
                "BATCH ID", "BATCH DATE", "STATUS", "TXN CNT", "TOTAL AMOUNT", "RUN BY", "RUN AT");
        printSeparator();
        printSeparator();

        int count = 0;
        try (Connection conn = ConnectionPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                System.out.printf("%-22s  %-12s  %-12s  %8d  %16s  %-15s  %-16s%n",
                        truncate(safe(rs.getString("batch_id")), 22),
                        safe(rs.getString("batch_date")),
                        safe(rs.getString("batch_status")),
                        rs.getInt("total_transactions"),
                        formatAmount(rs.getBigDecimal("total_amount")),
                        truncate(safe(rs.getString("run_by")), 15),
                        safe(rs.getString("run_at")));
                count++;
            }
        } catch (SQLException e) {
            System.out.println("  [ERROR] Could not load settlement batches: " + e.getMessage());
        }

        if (count == 0) {
            printNoData("No settlement batches found. Run Settlement Engine (Pipeline Step 3).");
        } else {
            printRowCount(count);
        }
    }

    // -----------------------------------------------------------------------
    // 3. SETTLEMENT RECORDS
    // -----------------------------------------------------------------------

    public static void showSettlementRecords() {
        printBanner("SETTLEMENT RECORDS");

        String sql =
            "SELECT record_id, batch_id, incoming_txn_id, settled_amount, " +
            "settled_status, settled_date, failure_reason " +
            "FROM settlement_record ORDER BY record_id ASC LIMIT 100";

        System.out.printf("%-10s  %-22s  %-14s  %14s  %-12s  %-18s  %-20s%n",
                "RECORD ID", "BATCH ID", "INCOMING TXN", "SETTLED AMT", "STATUS", "SETTLED DATE", "FAILURE REASON");
        printSeparator();
        printSeparator();

        int count = 0;
        try (Connection conn = ConnectionPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                String reason = rs.getString("failure_reason");
                System.out.printf("%-10d  %-22s  %-14d  %14s  %-12s  %-18s  %-20s%n",
                        rs.getLong("record_id"),
                        truncate(safe(rs.getString("batch_id")), 22),
                        rs.getLong("incoming_txn_id"),
                        formatAmount(rs.getBigDecimal("settled_amount")),
                        safe(rs.getString("settled_status")),
                        safe(rs.getString("settled_date")),
                        reason != null ? truncate(reason, 20) : "-");
                count++;
            }
        } catch (SQLException e) {
            System.out.println("  [ERROR] Could not load settlement records: " + e.getMessage());
        }

        if (count == 0) {
            printNoData("No settlement records found. Run Settlement Engine (Pipeline Step 3).");
        } else {
            printRowCount(count);
        }
    }

    // -----------------------------------------------------------------------
    // 4. CREDIT TRANSACTIONS
    // -----------------------------------------------------------------------

    public static void showCreditTransactions() {
        printBanner("CREDIT TRANSACTIONS");

        String sql =
            "SELECT txn_id, credit_account_number, amount, currency, status, " +
            "value_date, from_bank, to_bank " +
            "FROM credit_transaction ORDER BY txn_id ASC LIMIT 100";

        printTransactionHeader("CREDIT ACCOUNT");
        int count = 0;

        try (Connection conn = ConnectionPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                System.out.printf("%-8d  %-18s  %14s  %-5s  %-12s  %-12s  %-14s  %-14s%n",
                        rs.getLong("txn_id"),
                        truncate(safe(rs.getString("credit_account_number")), 18),
                        formatAmount(rs.getBigDecimal("amount")),
                        safe(rs.getString("currency")),
                        safe(rs.getString("status")),
                        safe(rs.getString("value_date")),
                        truncate(safe(rs.getString("from_bank")), 14),
                        truncate(safe(rs.getString("to_bank")), 14));
                count++;
            }
        } catch (SQLException e) {
            System.out.println("  [ERROR] Could not load credit transactions: " + e.getMessage());
        }

        if (count == 0) {
            printNoData("No credit transactions. Run Dispatch (Pipeline Step 2) first.");
        } else {
            printRowCount(count);
        }
    }

    // -----------------------------------------------------------------------
    // 5. DEBIT TRANSACTIONS
    // -----------------------------------------------------------------------

    public static void showDebitTransactions() {
        printBanner("DEBIT TRANSACTIONS");

        String sql =
            "SELECT txn_id, debit_account_number, amount, currency, status, " +
            "value_date, from_bank, to_bank " +
            "FROM debit_transaction ORDER BY txn_id ASC LIMIT 100";

        printTransactionHeader("DEBIT ACCOUNT");
        int count = 0;

        try (Connection conn = ConnectionPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                System.out.printf("%-8d  %-18s  %14s  %-5s  %-12s  %-12s  %-14s  %-14s%n",
                        rs.getLong("txn_id"),
                        truncate(safe(rs.getString("debit_account_number")), 18),
                        formatAmount(rs.getBigDecimal("amount")),
                        safe(rs.getString("currency")),
                        safe(rs.getString("status")),
                        safe(rs.getString("value_date")),
                        truncate(safe(rs.getString("from_bank")), 14),
                        truncate(safe(rs.getString("to_bank")), 14));
                count++;
            }
        } catch (SQLException e) {
            System.out.println("  [ERROR] Could not load debit transactions: " + e.getMessage());
        }

        if (count == 0) {
            printNoData("No debit transactions. Run Dispatch (Pipeline Step 2) first.");
        } else {
            printRowCount(count);
        }
    }

    // -----------------------------------------------------------------------
    // 6. INTERBANK TRANSACTIONS
    // -----------------------------------------------------------------------

    public static void showInterBankTransactions() {
        printBanner("INTERBANK TRANSACTIONS");

        String sql =
            "SELECT txn_id, correspondent_bank_code, amount, currency, status, " +
            "value_date, from_bank, to_bank " +
            "FROM interbank_transaction ORDER BY txn_id ASC LIMIT 100";

        printTransactionHeader("CORRESPONDENT BANK");
        int count = 0;

        try (Connection conn = ConnectionPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                System.out.printf("%-8d  %-18s  %14s  %-5s  %-12s  %-12s  %-14s  %-14s%n",
                        rs.getLong("txn_id"),
                        truncate(safe(rs.getString("correspondent_bank_code")), 18),
                        formatAmount(rs.getBigDecimal("amount")),
                        safe(rs.getString("currency")),
                        safe(rs.getString("status")),
                        safe(rs.getString("value_date")),
                        truncate(safe(rs.getString("from_bank")), 14),
                        truncate(safe(rs.getString("to_bank")), 14));
                count++;
            }
        } catch (SQLException e) {
            System.out.println("  [ERROR] Could not load interbank transactions: " + e.getMessage());
        }

        if (count == 0) {
            printNoData("No interbank transactions. Run Dispatch (Pipeline Step 2) first.");
        } else {
            printRowCount(count);
        }
    }

    // -----------------------------------------------------------------------
    // 7. REVERSAL TRANSACTIONS
    // -----------------------------------------------------------------------

    public static void showReversalTransactions() {
        printBanner("REVERSAL TRANSACTIONS");

        String sql =
            "SELECT txn_id, original_txn_ref, reversal_reason, amount, " +
            "currency, status, value_date " +
            "FROM reversal_transaction ORDER BY txn_id ASC LIMIT 100";

        System.out.printf("%-8s  %-22s  %-22s  %14s  %-5s  %-12s  %-12s%n",
                "TXN ID", "ORIGINAL TXN REF", "REVERSAL REASON", "AMOUNT (INR)", "CCY", "STATUS", "VALUE DATE");
        printSeparator();
        printSeparator();
        int count = 0;

        try (Connection conn = ConnectionPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                System.out.printf("%-8d  %-22s  %-22s  %14s  %-5s  %-12s  %-12s%n",
                        rs.getLong("txn_id"),
                        truncate(safe(rs.getString("original_txn_ref")), 22),
                        truncate(safe(rs.getString("reversal_reason")), 22),
                        formatAmount(rs.getBigDecimal("amount")),
                        safe(rs.getString("currency")),
                        safe(rs.getString("status")),
                        safe(rs.getString("value_date")));
                count++;
            }
        } catch (SQLException e) {
            System.out.println("  [ERROR] Could not load reversal transactions: " + e.getMessage());
        }

        if (count == 0) {
            printNoData("No reversal transactions. Run Dispatch (Pipeline Step 2) first.");
        } else {
            printRowCount(count);
        }
    }

    // -----------------------------------------------------------------------
    // 8. NETTING POSITIONS
    // -----------------------------------------------------------------------

    public static void showNettingPositions() {
        printBanner("NETTING POSITIONS");

        String sql =
            "SELECT position_id, from_bank_name, to_bank_name, " +
            "gross_debit_amount, gross_credit_amount, net_amount, direction, position_date " +
            "FROM netting_position ORDER BY position_id ASC";

        System.out.printf("%-6s  %-14s  %-14s  %16s  %16s  %14s  %-10s  %-12s%n",
                "POS ID", "FROM BANK", "TO BANK",
                "GROSS DEBIT", "GROSS CREDIT", "NET AMOUNT", "DIRECTION", "DATE");
        printSeparator();
        printSeparator();
        int count = 0;

        try (Connection conn = ConnectionPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                System.out.printf("%-6d  %-14s  %-14s  %16s  %16s  %14s  %-10s  %-12s%n",
                        rs.getLong("position_id"),
                        truncate(safe(rs.getString("from_bank_name")), 14),
                        truncate(safe(rs.getString("to_bank_name")), 14),
                        formatAmount(rs.getBigDecimal("gross_debit_amount")),
                        formatAmount(rs.getBigDecimal("gross_credit_amount")),
                        formatAmount(rs.getBigDecimal("net_amount")),
                        safe(rs.getString("direction")),
                        safe(rs.getString("position_date")));
                count++;
            }
        } catch (SQLException e) {
            System.out.println("  [ERROR] Could not load netting positions: " + e.getMessage());
        }

        if (count == 0) {
            printNoData("No netting positions found. Run Netting Engine (Pipeline Step 4).");
        } else {
            printRowCount(count);
        }
    }

    // -----------------------------------------------------------------------
    // 9. RECONCILIATION ENTRIES  [FIX: JOIN bank table to get bank_name]
    // -----------------------------------------------------------------------

    /**
     * Displays all reconciliation entries.
     *
     * FIX: reconciliation_entry has account_id (FK to npci_bank_account).
     *      bank_name does NOT live in reconciliation_entry directly —
     *      we JOIN npci_bank_account and then bank to get the bank_name.
     */
    public static void showReconciliationEntries() {

        // Load all rows using JOIN
        List<ReconRow> rows = loadReconRows();

        printBanner("RECONCILIATION ENTRIES  [" + rows.size() + " records]");
        printReconHeader();

        // Use Stream to print each row
        rows.stream().forEach(r -> printReconRow(r));

        if (rows.isEmpty()) {
            printNoData("No reconciliation entries. Run Reconciliation (Pipeline Step 5).");
        } else {
            printRowCount(rows.size());
        }
    }

    // -----------------------------------------------------------------------
    // 9a. RECONCILIATION — FILTER BY STATUS (Stream example)
    // -----------------------------------------------------------------------

    /**
     * Filters reconciliation entries by recon_status using Java Streams.
     *
     * Example: showReconByStatus("UNMATCHED") shows only failed reconciliations.
     *
     * @param status  MATCHED or UNMATCHED
     * @throws AccountNotFoundException if no entries found for that status
     */
    public static void showReconByStatus(String status) throws AccountNotFoundException {

        List<ReconRow> allRows = loadReconRows();

        // Stream filter — keep rows where recon_status matches
        List<ReconRow> filtered = allRows.stream()
                .filter(row -> status.equalsIgnoreCase(row.status))
                .collect(Collectors.toList());

        if (filtered.isEmpty()) {
            throw new AccountNotFoundException(
                "No reconciliation entries found with status: " + status.toUpperCase(),
                status
            );
        }

        printBanner("RECONCILIATION — STATUS: " + status.toUpperCase()
                + "  [" + filtered.size() + " records]");
        printReconHeader();
        filtered.stream().forEach(r -> printReconRow(r));
        printRowCount(filtered.size());
    }

    /**
     * Loads reconciliation rows from DB using JOIN to get bank_name.
     *
     * TABLE STRUCTURE:
     *   reconciliation_entry.account_id → npci_bank_account.npci_account_id
     *   npci_bank_account.bank_id       → bank.bank_id
     *   bank.bank_name                  ← this is what we need
     */
    private static List<ReconRow> loadReconRows() {
        List<ReconRow> rows = new ArrayList<>();

        // JOIN: reconciliation_entry → npci_bank_account → bank
        String sql =
            "SELECT re.entry_id, b.bank_name, re.expected_amount, re.actual_amount, " +
            "re.variance, re.recon_status, re.remarks " +
            "FROM reconciliation_entry re " +
            "JOIN npci_bank_account na ON na.npci_account_id = re.account_id " +
            "JOIN bank b ON b.bank_id = na.bank_id " +
            "ORDER BY re.entry_id ASC";

        try (Connection conn = ConnectionPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                ReconRow row    = new ReconRow();
                row.entryId     = rs.getLong("entry_id");
                row.bankName    = safe(rs.getString("bank_name"));
                row.expected    = rs.getBigDecimal("expected_amount");
                row.actual      = rs.getBigDecimal("actual_amount");
                row.variance    = rs.getBigDecimal("variance");
                row.status      = safe(rs.getString("recon_status"));
                row.remarks     = safe(rs.getString("remarks"));
                rows.add(row);
            }
        } catch (SQLException e) {
            System.out.println("  [ERROR] Could not load reconciliation entries: " + e.getMessage());
        }
        return rows;
    }

    private static void printReconHeader() {
        System.out.printf("%-8s  %-14s  %16s  %16s  %14s  %-14s  %-25s%n",
                "ENTRY ID", "BANK NAME",
                "EXPECTED AMT", "ACTUAL AMT", "VARIANCE", "STATUS", "REMARKS");
        printSeparator();
        printSeparator();
    }

    private static void printReconRow(ReconRow r) {
        // Flag UNMATCHED rows with a "!" so they stand out
        String statusDisplay = "UNMATCHED".equals(r.status) ? r.status + " !" : r.status;
        System.out.printf("%-8d  %-14s  %16s  %16s  %14s  %-14s  %-25s%n",
                r.entryId,
                truncate(r.bankName, 14),
                formatAmount(r.expected),
                formatAmount(r.actual),
                formatAmount(r.variance),
                statusDisplay,
                truncate(r.remarks, 25));
    }

    // -----------------------------------------------------------------------
    // 10. NPCI MEMBER ACCOUNTS  [FIX: JOIN bank table to get bank_name]
    // -----------------------------------------------------------------------

    /**
     * Displays NPCI member bank accounts.
     *
     * FIX: npci_bank_account does NOT have a bank_name column directly.
     *      It has bank_id (FK) → JOIN with bank table to get bank_name.
     */
    public static void showNpciMemberAccounts() {

        // Load all NPCI rows using JOIN
        List<NpciRow> rows = loadNpciRows();

        printBanner("NPCI MEMBER BANK ACCOUNTS  [" + rows.size() + " banks]");
        printNpciHeader();

        // Use Stream to print each row
        rows.stream().forEach(r -> printNpciRow(r));

        if (rows.isEmpty()) {
            printNoData("No NPCI member accounts found.");
        } else {
            printRowCount(rows.size());
        }
    }

    // -----------------------------------------------------------------------
    // 10a. NPCI ACCOUNTS — FILTER BY BANK NAME (Stream example)
    // -----------------------------------------------------------------------

    /**
     * Filters NPCI accounts by partial bank name using Java Streams.
     *
     * Example: showNpciAccountByBank("HDFC") shows HDFC Bank account.
     *
     * HOW STREAM IS USED HERE:
     *   - Load all NPCI rows
     *   - stream().filter() keeps rows where bankName contains the search text
     *   - Case insensitive match using toLowerCase()
     *
     * @param bankName  Partial bank name to search (e.g. "HDFC", "SBI")
     * @throws AccountNotFoundException if no account found matching that bank name
     */
    public static void showNpciAccountByBank(String bankName) throws AccountNotFoundException {

        List<NpciRow> allRows = loadNpciRows();

        // Stream filter — keep rows where bank name contains the search text
        List<NpciRow> filtered = allRows.stream()
                .filter(row -> row.bankName.toLowerCase().contains(bankName.toLowerCase()))
                .collect(Collectors.toList());

        if (filtered.isEmpty()) {
            throw new AccountNotFoundException(
                "No NPCI account found matching bank name: " + bankName,
                bankName
            );
        }

        printBanner("NPCI ACCOUNTS — BANK SEARCH: " + bankName.toUpperCase()
                + "  [" + filtered.size() + " found]");
        printNpciHeader();
        filtered.stream().forEach(r -> printNpciRow(r));
        printRowCount(filtered.size());
    }

    /**
     * Loads NPCI account rows from DB using JOIN to get bank_name.
     *
     * TABLE STRUCTURE:
     *   npci_bank_account.bank_id → bank.bank_id → bank.bank_name
     */
    private static List<NpciRow> loadNpciRows() {
        List<NpciRow> rows = new ArrayList<>();

        // JOIN: npci_bank_account → bank  (to get bank_name)
        String sql =
            "SELECT na.npci_account_id, b.bank_name, " +
            "na.opening_balance, na.current_balance " +
            "FROM npci_bank_account na " +
            "JOIN bank b ON b.bank_id = na.bank_id " +
            "ORDER BY na.npci_account_id ASC";

        try (Connection conn = ConnectionPool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                NpciRow row    = new NpciRow();
                row.accountId  = rs.getLong("npci_account_id");
                row.bankName   = safe(rs.getString("bank_name"));
                row.opening    = rs.getBigDecimal("opening_balance");
                row.current    = rs.getBigDecimal("current_balance");
                rows.add(row);
            }
        } catch (SQLException e) {
            System.out.println("  [ERROR] Could not load NPCI member accounts: " + e.getMessage());
        }
        return rows;
    }

    private static void printNpciHeader() {
        System.out.printf("%-6s  %-16s  %18s  %18s  %18s%n",
                "ID", "BANK NAME", "OPENING BALANCE", "CURRENT BALANCE", "CHANGE (+/-)");
        printSeparator();
        printSeparator();
    }

    private static void printNpciRow(NpciRow r) {
        BigDecimal change = BigDecimal.ZERO;
        if (r.opening != null && r.current != null) {
            change = r.current.subtract(r.opening);
        }
        System.out.printf("%-6d  %-16s  %18s  %18s  %18s%n",
                r.accountId,
                truncate(r.bankName, 16),
                formatAmount(r.opening),
                formatAmount(r.current),
                formatAmount(change));
    }

    // -----------------------------------------------------------------------
    // SHARED TRANSACTION TABLE HEADER (Credit/Debit/InterBank share same format)
    // -----------------------------------------------------------------------

    private static void printTransactionHeader(String accountColumnLabel) {
        System.out.printf("%-8s  %-18s  %14s  %-5s  %-12s  %-12s  %-14s  %-14s%n",
                "TXN ID", accountColumnLabel, "AMOUNT (INR)",
                "CCY", "STATUS", "VALUE DATE", "FROM BANK", "TO BANK");
        printSeparator();
        printSeparator();
    }

    // -----------------------------------------------------------------------
    // PRIVATE HELPER METHODS
    // -----------------------------------------------------------------------

    /**
     * Returns "N/A" if value is null. Prevents NullPointerException in printf.
     */
    private static String safe(String value) {
        return value != null ? value : "N/A";
    }

    /**
     * Truncates a string to maxLength. Adds ".." at end if it was longer.
     * Keeps table columns aligned neatly.
     */
    private static String truncate(String value, int maxLength) {
        if (value == null || value.isEmpty()) return "N/A";
        if (value.length() <= maxLength) return value;
        return value.substring(0, maxLength - 2) + "..";
    }

    /**
     * Formats BigDecimal with comma grouping and 2 decimal places.
     * Example: 1234567.89 → "1,234,567.89"
     */
    private static String formatAmount(BigDecimal amount) {
        if (amount == null) return "0.00";
        return String.format("%,.2f", amount);
    }
}