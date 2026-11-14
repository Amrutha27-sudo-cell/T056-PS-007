package com.billing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class BillingCycle {
    private final LocalDate startDate;
    private LocalDate closingDate;
    private LocalDate dueDate;
    private int cycleLength;
    private final int gracePeriod;
    private BigDecimal statementBalance = BigDecimal.ZERO.setScale(2);
    private BigDecimal minimumDue = BigDecimal.ZERO.setScale(2);
    private BigDecimal interestCharge = BigDecimal.ZERO.setScale(2);
    private BigDecimal lateFeeCharge = BigDecimal.ZERO.setScale(2);
    private final List<Transaction> transactions = new ArrayList<Transaction>();

    public BillingCycle(LocalDate startDate, int cycleLength, int gracePeriod) {
        this.startDate = Objects.requireNonNull(startDate, "startDate");
        if (cycleLength < 1) {
            throw new IllegalArgumentException("Cycle length must be at least one day.");
        }
        if (gracePeriod < 0) {
            throw new IllegalArgumentException("Grace period cannot be negative.");
        }
        this.cycleLength = cycleLength;
        this.gracePeriod = gracePeriod;
        this.closingDate = startDate.plusDays(cycleLength - 1L);
        this.dueDate = closingDate.plusDays(gracePeriod);
    }

    void addTransaction(Transaction transaction) {
        transactions.add(Objects.requireNonNull(transaction, "transaction"));
    }

    void closeOn(LocalDate date) {
        if (date.isBefore(startDate)) {
            throw new IllegalArgumentException("Closing date cannot be before the cycle start date.");
        }
        closingDate = date;
        cycleLength = (int) (date.toEpochDay() - startDate.toEpochDay()) + 1;
        dueDate = closingDate.plusDays(gracePeriod);
    }

    void setStatementAmounts(BigDecimal balance, BigDecimal minimum,
            BigDecimal interest, BigDecimal lateFee) {
        statementBalance = balance;
        minimumDue = minimum;
        interestCharge = interest;
        lateFeeCharge = lateFee;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getClosingDate() {
        return closingDate;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public int getCycleLength() {
        return cycleLength;
    }

    public int getGracePeriod() {
        return gracePeriod;
    }

    public BigDecimal getStatementBalance() {
        return statementBalance;
    }

    public BigDecimal getMinimumDue() {
        return minimumDue;
    }

    public BigDecimal getInterestCharge() {
        return interestCharge;
    }

    public BigDecimal getLateFeeCharge() {
        return lateFeeCharge;
    }

    public List<Transaction> getTransactions() {
        return Collections.unmodifiableList(transactions);
    }
}
