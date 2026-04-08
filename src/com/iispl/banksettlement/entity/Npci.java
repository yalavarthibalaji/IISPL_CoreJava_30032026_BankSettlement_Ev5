package com.iispl.banksettlement.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Npci — Represents the NPCI (National Payments Corporation of India) entity.
 *
 * NPCI is the central clearing house that:
 *  1. Holds settlement accounts for all member banks (List<BankAccount>).
 *  2. After netting is done, it updates each bank's balance based on
 *     net obligations (who pays whom how much).
 *
 * HOW IT WORKS:
 *  - NettingEngine produces a List<NettingResult> — each result says
 *    "Bank A must pay X amount to Bank B".
 *  - NPCI.applyNettingResults(results) is then called.
 *  - For each NettingResult:
 *      fromBank's balance is DEBITED by netAmount  (they pay)
 *      toBank's   balance is CREDITED by netAmount (they receive)
 *
 * HAS-A List<BankAccount> — composition.
 *   NPCI holds all bank settlement accounts.
 */
public class Npci {

    // The name of this NPCI entity
    private String name;

    // HAS-A: List of all member bank settlement accounts
    // This is the composition relationship
    private List<BankAccount> bankAccounts;

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /**
     * Default constructor. Initializes with the standard NPCI name
     * and an empty bank account list.
     */
    public Npci() {
        this.name = "NPCI - National Payments Corporation of India";
        this.bankAccounts = new ArrayList<>();
        initDefaultBankAccounts();
    }

    /**
     * Custom constructor for testing with specific accounts.
     */
    public Npci(String name, List<BankAccount> bankAccounts) {
        this.name = name;
        this.bankAccounts = bankAccounts;
    }

    // -----------------------------------------------------------------------
    // Initialize default bank accounts (the 5 banks in our system)
    // -----------------------------------------------------------------------

    /**
     * Sets up the default NPCI settlement accounts for all 5 member banks
     * that participate in our settlement system.
     *
     * Opening balances are set to represent pre-funded clearing balances.
     */
    private void initDefaultBankAccounts() {
        bankAccounts.add(new BankAccount("HDFC Bank",  new BigDecimal("10000000.00")));
        bankAccounts.add(new BankAccount("SBI Bank",   new BigDecimal("10000000.00")));
        bankAccounts.add(new BankAccount("ICICI Bank", new BigDecimal("10000000.00")));
        bankAccounts.add(new BankAccount("Axis Bank",  new BigDecimal("10000000.00")));
        bankAccounts.add(new BankAccount("Kotak Bank", new BigDecimal("10000000.00")));
    }

    // -----------------------------------------------------------------------
    // Core method — Apply netting results to update bank balances
    // -----------------------------------------------------------------------

    /**
     * Applies all netting results to update the bank settlement account balances.
     *
     * For each NettingResult:
     *   - The fromBank's balance is DEBITED (they are paying)
     *   - The toBank's balance is CREDITED (they are receiving)
     *
     * After this method runs, each BankAccount reflects the post-settlement balance.
     *
     * @param results List of NettingResult from the NettingEngine
     */
    public void applyNettingResults(List<NettingResult> results) {
        System.out.println("\n================================================");
        System.out.println("  NPCI — APPLYING NETTING RESULTS");
        System.out.println("  Settlement Date: " + LocalDate.now());
        System.out.println("================================================");

        if (results == null || results.isEmpty()) {
            System.out.println("[NPCI] No netting results to apply.");
            return;
        }

        System.out.println("\n[NPCI] Pre-settlement balances:");
        printAllBalances();

        System.out.println("\n[NPCI] Applying " + results.size() + " netting result(s)...\n");

        for (NettingResult result : results) {
            applyOneResult(result);
        }

        System.out.println("\n[NPCI] Post-settlement balances:");
        printAllBalances();

        System.out.println("\n[NPCI] Settlement complete.");
        System.out.println("================================================\n");
    }

    /**
     * Applies one netting result:
     *   fromBank pays netAmount → fromBank balance decreases
     *   toBank receives netAmount → toBank balance increases
     */
    private void applyOneResult(NettingResult result) {
        BankAccount fromAccount = findBankAccount(result.getFromBank());
        BankAccount toAccount   = findBankAccount(result.getToBank());

        if (fromAccount == null) {
            System.out.println("[NPCI] WARNING — Bank account not found for: " + result.getFromBank()
                    + " | Skipping this netting result.");
            return;
        }
        if (toAccount == null) {
            System.out.println("[NPCI] WARNING — Bank account not found for: " + result.getToBank()
                    + " | Skipping this netting result.");
            return;
        }

        BigDecimal amount = result.getNetAmount();

        // Debit from fromBank
        fromAccount.debit(amount);

        // Credit to toBank
        toAccount.credit(amount);

        System.out.println("[NPCI] SETTLED: "
                + result.getFromBank() + " → paid Rs. " + amount
                + " → to → " + result.getToBank()
                + " | New " + result.getFromBank() + " balance: " + fromAccount.getBalance()
                + " | New " + result.getToBank() + " balance: " + toAccount.getBalance());
    }

    // -----------------------------------------------------------------------
    // Helper methods
    // -----------------------------------------------------------------------

    /**
     * Finds a BankAccount by bank name. Returns null if not found.
     *
     * @param bankName The bank name to search for
     * @return BankAccount if found, null otherwise
     */
    public BankAccount findBankAccount(String bankName) {
        if (bankName == null) return null;
        for (BankAccount account : bankAccounts) {
            if (bankName.equalsIgnoreCase(account.getBankName())) {
                return account;
            }
        }
        return null;
    }

    /**
     * Adds a new bank account to NPCI's list.
     * Use this if a new member bank joins.
     */
    public void addBankAccount(BankAccount account) {
        if (account != null) {
            bankAccounts.add(account);
        }
    }

    /**
     * Prints all bank account balances in a table format.
     */
    public void printAllBalances() {
        System.out.println(String.format("  %-20s %s", "Bank Name", "Balance (INR)"));
        System.out.println("  " + "-".repeat(40));
        for (BankAccount account : bankAccounts) {
            System.out.println(String.format("  %-20s %,.2f",
                    account.getBankName(), account.getBalance()));
        }
    }

    // -----------------------------------------------------------------------
    // Getters and Setters
    // -----------------------------------------------------------------------

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<BankAccount> getBankAccounts() {
        return bankAccounts;
    }

    public void setBankAccounts(List<BankAccount> bankAccounts) {
        this.bankAccounts = bankAccounts;
    }

    // -----------------------------------------------------------------------
    // toString
    // -----------------------------------------------------------------------

    @Override
    public String toString() {
        return "Npci{name='" + name + "', bankAccounts=" + bankAccounts + '}';
    }
}
