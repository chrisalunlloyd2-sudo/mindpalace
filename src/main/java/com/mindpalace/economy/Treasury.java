package com.mindpalace.economy;

/** The local system fund used to seed and back job bounties. */
public final class Treasury {
    public static final String ACCOUNT = "treasury";
    public static final double DEFAULT_INITIAL_BALANCE = 1000.0;
    private static final String INITIAL_BALANCE_PROPERTY = "mindpalace.economy.treasury.initial";

    private final EconomyLedger ledger;

    Treasury(EconomyLedger ledger, double initialBalance) {
        this.ledger = ledger;
        ledger.openAccount(ACCOUNT, initialBalance, "Treasury funding");
    }

    public double balance() {
        return ledger.balance(ACCOUNT);
    }

    public boolean canFund(double amount) {
        long cents = EconomyLedger.toCents(amount);
        return cents > 0 && balance() >= cents / 100.0;
    }

    boolean transferTo(String account, double amount, String reason, String reference) {
        return ledger.transfer(ACCOUNT, account, amount, reason, reference);
    }

    static double configuredInitialBalance() {
        String configured = System.getProperty(INITIAL_BALANCE_PROPERTY);
        if (configured == null || configured.trim().isEmpty()) return DEFAULT_INITIAL_BALANCE;
        try {
            double amount = Double.parseDouble(configured.trim());
            if (!Double.isFinite(amount) || amount < 0 || EconomyLedger.toCents(amount) < 0) {
                throw new IllegalArgumentException();
            }
            return amount;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(INITIAL_BALANCE_PROPERTY
                + " must be a finite, non-negative amount", e);
        }
    }
}
