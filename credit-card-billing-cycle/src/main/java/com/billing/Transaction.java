package com.billing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

public class Transaction {
    public enum Type {
        PURCHASE,
        PAYMENT
    }

    private final String id;
    private final String description;
    private final BigDecimal amount;
    private final LocalDate date;
    private final Type type;

    public Transaction(String id, String description, BigDecimal amount, LocalDate date, Type type) {
        this.id = requireText(id, "id");
        this.description = requireText(description, "description");
        this.amount = Objects.requireNonNull(amount, "amount");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Transaction amount must be greater than zero.");
        }
        this.date = Objects.requireNonNull(date, "date");
        this.type = Objects.requireNonNull(type, "type");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank.");
        }
        return value.trim();
    }

    public String getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public LocalDate getDate() {
        return date;
    }

    public Type getType() {
        return type;
    }
}
