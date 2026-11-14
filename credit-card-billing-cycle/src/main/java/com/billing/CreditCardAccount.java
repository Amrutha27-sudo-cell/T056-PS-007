package com.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class CreditCardAccount {
    private static final BigDecimal MINIMUM_DUE_PERCENT = new BigDecimal("0.02");
    private static final BigDecimal MINIMUM_DUE_FLOOR = new BigDecimal("25.00");
    private static final BigDecimal MONTHS_PER_YEAR = new BigDecimal("12");

    private final String accountHolder;
    private final String cardNumber;
    private final BigDecimal creditLimit;
    private final BigDecimal annualInterestRate;
    private final BigDecimal lateFeeAmount;
    private final int standardCycleLength;
    private final int gracePeriod;
    private final List<BillingCycle> cycleHistory = new ArrayList<BillingCycle>();
    private BillingCycle currentCycle;
    private BigDecimal currentBalance = BigDecimal.ZERO.setScale(2);
    private BillingCycle lateFeeAssessedFor;

    public CreditCardAccount(String accountHolder, String cardNumber, BigDecimal creditLimit,
            BigDecimal annualInterestRate, BigDecimal lateFeeAmount, int cycleLength,
            int gracePeriod, LocalDate cycleStartDate) {
        this.accountHolder = requireText(accountHolder, "accountHolder");
        this.cardNumber = requireText(cardNumber, "cardNumber");
        this.creditLimit = positiveMoney(creditLimit, "creditLimit");
        this.annualInterestRate = nonNegative(annualInterestRate, "annualInterestRate");
        this.lateFeeAmount = nonNegativeMoney(lateFeeAmount, "lateFeeAmount");
        if (cycleLength < 1) {
            throw new IllegalArgumentException("Cycle length must be at least one day.");
        }
        if (gracePeriod < 0) {
            throw new IllegalArgumentException("Grace period cannot be negative.");
        }
        this.standardCycleLength = cycleLength;
        this.gracePeriod = gracePeriod;
        this.currentCycle = new BillingCycle(
                Objects.requireNonNull(cycleStartDate, "cycleStartDate"), cycleLength, gracePeriod);
    }

    public CreditCardAccount(String accountHolder, String cardNumber, BigDecimal creditLimit,
            BigDecimal annualInterestRate, BigDecimal lateFeeAmount) {
        this(accountHolder, cardNumber, creditLimit, annualInterestRate, lateFeeAmount,
                30, 21, LocalDate.now());
    }

    public Transaction addPurchase(String description, BigDecimal amount) {
        return addPurchase(description, amount, LocalDate.now());
    }

    public Transaction addPurchase(String description, BigDecimal amount, LocalDate date) {
        BigDecimal purchaseAmount = positiveMoney(amount, "amount");
        validateTransactionDate(date);
        if (currentBalance.add(purchaseAmount).compareTo(creditLimit) > 0) {
            throw new IllegalArgumentException("Purchase exceeds the available credit limit.");
        }
        Transaction transaction = new Transaction(
                UUID.randomUUID().toString(), description, purchaseAmount, date, Transaction.Type.PURCHASE);
        currentCycle.addTransaction(transaction);
        currentBalance = money(currentBalance.add(purchaseAmount));
        return transaction;
    }

    public Transaction makePayment(String description, BigDecimal amount) {
        return makePayment(description, amount, LocalDate.now());
    }

    public Transaction makePayment(String description, BigDecimal amount, LocalDate date) {
        BigDecimal paymentAmount = positiveMoney(amount, "amount");
        validateTransactionDate(date);
        if (paymentAmount.compareTo(currentBalance) > 0) {
            throw new IllegalArgumentException("Payment cannot exceed the current account balance.");
        }
        Transaction transaction = new Transaction(
                UUID.randomUUID().toString(), description, paymentAmount, date, Transaction.Type.PAYMENT);
        currentCycle.addTransaction(transaction);
        currentBalance = money(currentBalance.subtract(paymentAmount));
        return transaction;
    }

    public BillingCycle closeCurrentBillingCycle() {
        return closeCurrentBillingCycle(LocalDate.now());
    }

    public BillingCycle closeCurrentBillingCycle(LocalDate closingDate) {
        Objects.requireNonNull(closingDate, "closingDate");
        currentCycle.closeOn(closingDate);

        BigDecimal interest = calculateInterestOnCarriedOverBalance();
        currentBalance = money(currentBalance.add(interest));
        BigDecimal lateFee = applyLateFeeIfMinimumDueWasntPaid();

        BigDecimal minimumDue = currentBalance.signum() == 0
                ? BigDecimal.ZERO.setScale(2)
                : money(currentBalance.multiply(MINIMUM_DUE_PERCENT)
                        .max(MINIMUM_DUE_FLOOR).min(currentBalance));
        currentCycle.setStatementAmounts(money(currentBalance), minimumDue, interest, lateFee);

        BillingCycle closedCycle = currentCycle;
        cycleHistory.add(closedCycle);
        currentCycle = new BillingCycle(closingDate.plusDays(1), standardCycleLength, gracePeriod);
        return closedCycle;
    }

    public BigDecimal calculateInterestOnCarriedOverBalance() {
        if (cycleHistory.isEmpty()) {
            return BigDecimal.ZERO.setScale(2);
        }
        BillingCycle previousCycle = cycleHistory.get(cycleHistory.size() - 1);
        if (currentCycle.getClosingDate().isBefore(previousCycle.getDueDate())) {
            return BigDecimal.ZERO.setScale(2);
        }

        BigDecimal paymentsByDueDate = paymentsMadeBy(previousCycle.getDueDate());
        BigDecimal unpaidBalance = previousCycle.getStatementBalance()
                .subtract(paymentsByDueDate).max(BigDecimal.ZERO);
        return money(unpaidBalance.multiply(annualInterestRate)
                .divide(new BigDecimal("100"), 10, RoundingMode.HALF_UP)
                .divide(MONTHS_PER_YEAR, 10, RoundingMode.HALF_UP));
    }

    public BigDecimal applyLateFeeIfMinimumDueWasntPaid() {
        if (cycleHistory.isEmpty()) {
            return BigDecimal.ZERO.setScale(2);
        }
        BillingCycle previousCycle = cycleHistory.get(cycleHistory.size() - 1);
        if (lateFeeAssessedFor == previousCycle
                || currentCycle.getClosingDate().isBefore(previousCycle.getDueDate())) {
            return BigDecimal.ZERO.setScale(2);
        }

        lateFeeAssessedFor = previousCycle;
        BigDecimal paymentsByDueDate = paymentsMadeBy(previousCycle.getDueDate());
        if (paymentsByDueDate.compareTo(previousCycle.getMinimumDue()) >= 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        currentBalance = money(currentBalance.add(lateFeeAmount));
        return lateFeeAmount;
    }

    public String generateItemizedStatement() {
        return generateItemizedStatement(currentCycle);
    }

    public String generateItemizedStatement(BillingCycle cycle) {
        Objects.requireNonNull(cycle, "cycle");
        StringBuilder statement = new StringBuilder();
        statement.append("Credit Card Statement\n")
                .append("Account holder: ").append(accountHolder).append('\n')
                .append("Card: ").append(maskedCardNumber()).append('\n')
                .append("Cycle: ").append(cycle.getStartDate()).append(" to ")
                .append(cycle.getClosingDate()).append('\n')
                .append("Due date: ").append(cycle.getDueDate()).append('\n')
                .append("----------------------------------------\n");

        if (cycle.getTransactions().isEmpty()) {
            statement.append("No transactions.\n");
        } else {
            for (Transaction transaction : cycle.getTransactions()) {
                statement.append(transaction.getDate()).append(" | ")
                        .append(transaction.getType()).append(" | ")
                        .append(transaction.getDescription()).append(" | ")
                        .append(transaction.getAmount().setScale(2, RoundingMode.HALF_UP))
                        .append('\n');
            }
        }
        if (cycle.getInterestCharge().signum() > 0) {
            statement.append("Interest charge: ")
                    .append(cycle.getInterestCharge().setScale(2, RoundingMode.HALF_UP)).append('\n');
        }
        if (cycle.getLateFeeCharge().signum() > 0) {
            statement.append("Late fee: ")
                    .append(cycle.getLateFeeCharge().setScale(2, RoundingMode.HALF_UP)).append('\n');
        }
        statement.append("----------------------------------------\n");
        if (cycle == currentCycle) {
            statement.append("Current balance: ").append(currentBalance.setScale(2, RoundingMode.HALF_UP))
                    .append('\n')
                    .append("Minimum due: Not yet calculated\n");
        } else {
            statement.append("Statement balance: ")
                    .append(cycle.getStatementBalance().setScale(2, RoundingMode.HALF_UP)).append('\n')
                    .append("Minimum due: ")
                    .append(cycle.getMinimumDue().setScale(2, RoundingMode.HALF_UP)).append('\n');
        }
        return statement.toString();
    }

    private BigDecimal paymentsMadeBy(LocalDate date) {
        BigDecimal payments = BigDecimal.ZERO;
        for (Transaction transaction : currentCycle.getTransactions()) {
            if (transaction.getType() == Transaction.Type.PAYMENT
                    && !transaction.getDate().isAfter(date)) {
                payments = payments.add(transaction.getAmount());
            }
        }
        return payments;
    }

    private void validateTransactionDate(LocalDate date) {
        Objects.requireNonNull(date, "date");
        if (date.isBefore(currentCycle.getStartDate()) || date.isAfter(currentCycle.getClosingDate())) {
            throw new IllegalArgumentException("Transaction date must be within the current billing cycle.");
        }
    }

    private String maskedCardNumber() {
        int visibleDigits = Math.min(4, cardNumber.length());
        StringBuilder masked = new StringBuilder();
        for (int i = visibleDigits; i < cardNumber.length(); i++) {
            masked.append('*');
        }
        return masked.append(cardNumber.substring(cardNumber.length() - visibleDigits)).toString();
    }

    private static BigDecimal positiveMoney(BigDecimal value, String field) {
        BigDecimal amount = Objects.requireNonNull(value, field).setScale(2, RoundingMode.HALF_UP);
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException(field + " must be greater than zero.");
        }
        return amount;
    }

    private static BigDecimal nonNegativeMoney(BigDecimal value, String field) {
        BigDecimal amount = Objects.requireNonNull(value, field).setScale(2, RoundingMode.HALF_UP);
        if (amount.signum() < 0) {
            throw new IllegalArgumentException(field + " cannot be negative.");
        }
        return amount;
    }

    private static BigDecimal nonNegative(BigDecimal value, String field) {
        BigDecimal number = Objects.requireNonNull(value, field);
        if (number.signum() < 0) {
            throw new IllegalArgumentException(field + " cannot be negative.");
        }
        return number;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank.");
        }
        return value.trim();
    }

    public String getAccountHolder() {
        return accountHolder;
    }

    public String getCardNumber() {
        return cardNumber;
    }

    public BigDecimal getCreditLimit() {
        return creditLimit;
    }

    public BigDecimal getAnnualInterestRate() {
        return annualInterestRate;
    }

    public BigDecimal getLateFeeAmount() {
        return lateFeeAmount;
    }

    public BillingCycle getCurrentCycle() {
        return currentCycle;
    }

    public List<BillingCycle> getCycleHistory() {
        return Collections.unmodifiableList(cycleHistory);
    }

    public BigDecimal getCurrentBalance() {
        return currentBalance;
    }
}
