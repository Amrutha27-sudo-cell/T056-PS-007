package com.billing;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SimulationEngine {
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 2, 1);
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final BigDecimal GST_RATE = new BigDecimal("0.18");
    private static final BigDecimal CASH_FEE_RATE = new BigDecimal("0.025");
    private static final BigDecimal OVERLIMIT_FEE_RATE = new BigDecimal("0.025");
    private static final BigDecimal MINIMUM_FEE = new BigDecimal("500.00");
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Map<String, Card> cards = new LinkedHashMap<String, Card>();
    private final Map<String, PriorPurchase> priorPurchases = new HashMap<String, PriorPurchase>();
    private final Set<LocalDate> holidays = new HashSet<LocalDate>();
    private final Set<String> seenIds = new HashSet<String>();
    private final List<Decision> decisions = new ArrayList<Decision>();
    private final List<Statement> statements = new ArrayList<Statement>();
    private final List<PointBalance> pointBalances = new ArrayList<PointBalance>();
    private final List<EmiScheduleRow> emiSchedule = new ArrayList<EmiScheduleRow>();
    private LocalDate lastClockDate = PERIOD_START.minusDays(1);
    private int postingSequence;
    private boolean executed;

    public SimulationEngine(InputStream cardsFile, InputStream holidaysFile,
            InputStream pointsFile, InputStream priorPurchasesFile) throws IOException {
        loadCards(cardsFile);
        loadHolidays(holidaysFile);
        loadPriorPurchases(priorPurchasesFile);
        loadOpeningPoints(pointsFile);
    }

    public static SimulationEngine fromDirectory(Path resources) throws IOException {
        return new SimulationEngine(
                Files.newInputStream(resources.resolve("cards.csv")),
                Files.newInputStream(resources.resolve("holidays.csv")),
                Files.newInputStream(resources.resolve("points_opening.csv")),
                Files.newInputStream(resources.resolve("prior_purchases.csv")));
    }

    public Map<String, String> execute(InputStream commandsFile) throws IOException {
        if (executed) {
            throw new IllegalStateException("A simulation engine can only be executed once.");
        }
        executed = true;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(commandsFile, StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                processLine(lineNumber, trimmed);
            }
        }
        return buildOutputs();
    }

    public static void writeOutputs(Path directory, Map<String, String> outputs) throws IOException {
        Files.createDirectories(directory);
        for (Map.Entry<String, String> output : outputs.entrySet()) {
            Files.write(directory.resolve(output.getKey()),
                    output.getValue().getBytes(StandardCharsets.UTF_8));
        }
    }

    private void loadCards(InputStream file) throws IOException {
        for (String[] row : readCsv(file)) {
            if (row[0].equals("card_id")) {
                continue;
            }
            Card card = new Card(row[0], row[1], row[2], row[3],
                    decimal(row[4]), decimal(row[5]), Integer.parseInt(row[6]),
                    decimal(row[7]), LocalDate.parse(row[8]));
            if (cards.put(card.id, card) != null) {
                throw new IOException("Duplicate card id: " + card.id);
            }
        }
    }

    private void loadHolidays(InputStream file) throws IOException {
        for (String[] row : readCsv(file)) {
            if (!row[0].equals("date")) {
                holidays.add(LocalDate.parse(row[0]));
            }
        }
    }

    private void loadPriorPurchases(InputStream file) throws IOException {
        for (String[] row : readCsv(file)) {
            if (row[0].equals("txn_id")) {
                continue;
            }
            PriorPurchase prior = new PriorPurchase(row[0], row[1], decimal(row[3]),
                    row[4], row[5], Integer.parseInt(row[6]));
            priorPurchases.put(prior.id, prior);
        }
    }

    private void loadOpeningPoints(InputStream file) throws IOException {
        for (String[] row : readCsv(file)) {
            if (row[0].equals("lot_id")) {
                continue;
            }
            Card card = requiredCard(row[1]);
            YearMonth month = YearMonth.parse(row[2]);
            PointLot lot = new PointLot(row[0], month, Integer.parseInt(row[3]),
                    row[4], expiryDate(month));
            card.pointLots.add(lot);
            if (!row[4].isEmpty()) {
                PriorPurchase prior = priorPurchases.get(row[4]);
                if (prior != null) {
                    prior.pointLots.add(lot);
                }
            }
        }
        for (Card card : cards.values()) {
            card.pointsOpening = card.availablePoints();
        }
    }

    private static List<String[]> readCsv(InputStream input) throws IOException {
        List<String[]> rows = new ArrayList<String[]>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                rows.add(parseCsv(line));
            }
        }
        return rows;
    }

    private static String[] parseCsv(String line) {
        List<String> fields = new ArrayList<String>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char character = line.charAt(i);
            if (character == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (character == ',' && !quoted) {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(character);
            }
        }
        fields.add(field.toString());
        return fields.toArray(new String[fields.size()]);
    }

    private void processLine(int lineNumber, String line) throws IOException {
        String[] parts = line.split("\\|", -1);
        String command = parts[0];
        advanceClock(commandDate(parts));
        try {
            switch (command) {
                case "PURCHASE":
                    decisions.add(purchase(lineNumber, parts));
                    break;
                case "CASH":
                    decisions.add(cash(lineNumber, parts));
                    break;
                case "REFUND":
                    decisions.add(refund(lineNumber, parts));
                    break;
                case "FEE":
                    decisions.add(fee(lineNumber, parts));
                    break;
                case "PAYMENT":
                    decisions.add(payment(lineNumber, parts));
                    break;
                case "EMI_CONVERT":
                    decisions.add(convertEmi(lineNumber, parts));
                    break;
                case "EMI_FORECLOSE":
                    decisions.add(forecloseEmi(lineNumber, parts));
                    break;
                case "REDEEM":
                    decisions.add(redeem(lineNumber, parts));
                    break;
                case "GENERATE_STATEMENTS":
                    generateStatements(LocalDate.parse(parts[1]));
                    break;
                case "EXPIRE_POINTS":
                    expirePoints(LocalDate.parse(parts[1]));
                    break;
                case "PRINT_POINTS":
                    printPoints(parts[1], LocalDate.parse(parts[2]));
                    break;
                default:
                    throw new IOException("Unknown command at line " + lineNumber + ": " + command);
            }
        } catch (Rejected rejected) {
            String refId = parts.length > 1 ? parts[1] : "";
            String cardId = cardArgument(command, parts);
            decisions.add(new Decision(lineNumber, command, refId, cardId,
                    "REJECTED", rejected.ruleId, ZERO));
        } catch (RuntimeException exception) {
            throw new IOException("Invalid command at line " + lineNumber + ": " + line, exception);
        }
    }

    private static LocalDate commandDate(String[] parts) throws IOException {
        try {
            switch (parts[0]) {
                case "GENERATE_STATEMENTS":
                case "EXPIRE_POINTS":
                    return LocalDate.parse(parts[1]);
                case "PRINT_POINTS":
                    return LocalDate.parse(parts[2]);
                default:
                    return LocalDateTime.parse(parts[2], TIMESTAMP_FORMAT).toLocalDate();
            }
        } catch (RuntimeException exception) {
            throw new IOException("Invalid command date: " + Arrays.toString(parts), exception);
        }
    }

    private static String cardArgument(String command, String[] parts) {
        if ("GENERATE_STATEMENTS".equals(command) || "EXPIRE_POINTS".equals(command)) {
            return "";
        }
        if ("PRINT_POINTS".equals(command)) {
            return parts.length > 1 ? parts[1] : "";
        }
        return parts.length > 3 ? parts[3] : "";
    }

    private void advanceClock(LocalDate date) {
        for (LocalDate day = lastClockDate.plusDays(1); !day.isAfter(date); day = day.plusDays(1)) {
            for (Card card : cards.values()) {
                processDay(card, day);
            }
        }
        if (date.isAfter(lastClockDate)) {
            lastClockDate = date;
        }
    }

    private void processDay(Card card, LocalDate day) {
        for (Statement statement : card.statements) {
            if (!statement.dueDate.plusDays(1).equals(day) || statement.dueProcessed) {
                continue;
            }
            statement.dueProcessed = true;
            BigDecimal paid = paymentsBetween(card, statement.statementDate.plusDays(1), statement.dueDate);
            boolean paidInFull = paid.compareTo(statement.totalDue.max(ZERO)) >= 0;
            card.graceLost = !paidInFull;
            if (!paidInFull) {
                accrueRetroactivePurchaseInterest(card, statement.dueDate);
            }
            if (paid.compareTo(statement.minimumDue) < 0 && statement.totalDue.compareTo(new BigDecimal("1000.00")) > 0) {
                BigDecimal lateFee = lateFeeFor(statement.totalDue);
                if (lateFee.signum() > 0) {
                    postFee(card, statement.dueDate.plusDays(1), "AUTO-" + statement.id + "-LATE",
                            "LATE_FEE", "Late payment fee", lateFee);
                }
            }
        }
        accrueInterestForDay(card, day);
        maybeApplyOverlimitFee(card, day);
    }

    private void accrueRetroactivePurchaseInterest(Card card, LocalDate throughDate) {
        LocalDate first = null;
        for (PurchaseRecord purchase : card.purchases.values()) {
            if (purchase.charge != null && purchase.charge.balance.signum() > 0
                    && purchase.interestThrough == null
                    && (first == null || purchase.date.isBefore(first))) {
                first = purchase.date;
            }
        }
        if (first == null || first.isAfter(throughDate)) {
            return;
        }
        Map<LocalDate, BigDecimal> byDay = new LinkedHashMap<LocalDate, BigDecimal>();
        for (LocalDate day = first; !day.isAfter(throughDate); day = day.plusDays(1)) {
            BigDecimal daily = ZERO;
            for (PurchaseRecord purchase : card.purchases.values()) {
                if (purchase.charge != null && purchase.interestThrough == null
                        && !purchase.date.isAfter(day)) {
                    daily = daily.add(dailyInterest(purchase.charge.balanceAt(day), card.apr));
                }
            }
            byDay.put(day, money(daily));
        }
        for (PurchaseRecord purchase : card.purchases.values()) {
            if (purchase.charge != null && purchase.charge.balance.signum() > 0
                    && purchase.interestThrough == null) {
                purchase.interestThrough = throughDate;
                purchase.interestBearing = true;
            }
        }
        for (Map.Entry<LocalDate, BigDecimal> entry : byDay.entrySet()) {
            postInterest(card, throughDate.plusDays(1),
                    "AUTO-" + card.id + "-RETRO-INT-" + entry.getKey(),
                    entry.getValue());
        }
    }

    private void accrueInterestForDay(Card card, LocalDate day) {
        BigDecimal daily = ZERO;
        for (PurchaseRecord purchase : card.purchases.values()) {
            if (purchase.interestBearing && purchase.charge != null
                    && purchase.charge.balance.signum() > 0
                    && (purchase.interestThrough == null || purchase.interestThrough.isBefore(day))) {
                daily = daily.add(dailyInterest(purchase.charge.balance, card.apr));
                purchase.interestThrough = day;
            }
        }
        for (CashRecord cash : card.cashAdvances) {
            if (cash.charge.balance.signum() > 0
                    && (cash.interestThrough == null || cash.interestThrough.isBefore(day))
                    && !cash.date.isAfter(day)) {
                daily = daily.add(dailyInterest(cash.charge.balance, card.apr));
                cash.interestThrough = day;
            }
        }
        if (daily.signum() > 0) {
            postInterest(card, day, "AUTO-" + card.id + "-INT-" + day, daily);
        }
    }

    private static BigDecimal dailyInterest(BigDecimal principal, BigDecimal apr) {
        return money(principal.multiply(apr)
                .divide(new BigDecimal("36500"), 12, RoundingMode.HALF_UP));
    }

    private BigDecimal paymentsBetween(Card card, LocalDate start, LocalDate end) {
        BigDecimal amount = ZERO;
        for (Posting posting : card.postings) {
            if ("PAYMENT".equals(posting.type) && !posting.date.isBefore(start)
                    && !posting.date.isAfter(end)) {
                amount = amount.subtract(posting.amount);
            }
        }
        return amount;
    }

    private static BigDecimal lateFeeFor(BigDecimal balance) {
        if (balance.compareTo(new BigDecimal("1000.00")) <= 0) {
            return ZERO;
        }
        if (balance.compareTo(new BigDecimal("10000.00")) <= 0) {
            return new BigDecimal("500.00");
        }
        if (balance.compareTo(new BigDecimal("25000.00")) <= 0) {
            return new BigDecimal("950.00");
        }
        return new BigDecimal("1300.00");
    }

    private Decision purchase(int line, String[] p) throws Rejected {
        requireParts(p, 7);
        String id = p[1];
        Card card = requiredCardOrReject(p[3], "BR-02");
        LocalDateTime time = LocalDateTime.parse(p[2], TIMESTAMP_FORMAT);
        BigDecimal amount = positiveAmount(p[4], "BR-02");
        if (time.toLocalDate().isBefore(card.registeredDate)) {
            throw new Rejected("BR-02");
        }
        String category = p[6];
        if (!isPurchaseCategory(category)) {
            throw new Rejected("BR-08");
        }
        if (!registerId(id)) {
            throw new Rejected("BR-13");
        }
        if (card.outstanding().add(amount).compareTo(card.creditLimit) > 0) {
            throw new Rejected("BR-06");
        }
        PurchaseRecord purchase = new PurchaseRecord(id, card.id, time.toLocalDate(), amount, p[5], category);
        Charge charge = postDebit(card, time.toLocalDate(), id, "PURCHASE", p[5] + " (" + category + ")",
                amount, Charge.PURCHASE, null);
        purchase.charge = charge;
        card.purchases.put(id, purchase);
        int points = awardPoints(card, purchase);
        purchase.pointsAwarded = points;
        if (card.graceLost) {
            purchase.interestBearing = true;
            accrueInterestForDay(card, time.toLocalDate());
        }
        maybeApplyOverlimitFee(card, time.toLocalDate());
        return accepted(line, "PURCHASE", id, card.id, "BR-02;BR-08", amount);
    }

    private Decision cash(int line, String[] p) throws Rejected {
        requireParts(p, 6);
        String id = p[1];
        Card card = requiredCardOrReject(p[3], "BR-03");
        LocalDateTime time = LocalDateTime.parse(p[2], TIMESTAMP_FORMAT);
        BigDecimal amount = positiveAmount(p[4], "BR-03");
        if (time.toLocalDate().isBefore(card.registeredDate)) {
            throw new Rejected("BR-03");
        }
        if (!registerId(id)) {
            throw new Rejected("BR-13");
        }
        if (card.cashOutstanding().add(amount).compareTo(card.cashLimit) > 0) {
            throw new Rejected("BR-03");
        }
        BigDecimal fee = amount.multiply(CASH_FEE_RATE).max(MINIMUM_FEE);
        fee = money(fee);
        Charge charge = postDebit(card, time.toLocalDate(), id, "CASH", p[5], amount, Charge.CASH, null);
        card.cashAdvances.add(new CashRecord(time.toLocalDate(), charge));
        postFee(card, time.toLocalDate(), id + "-CASH-FEE", "CASH_FEE", "Cash advance fee", fee);
        accrueInterestForDay(card, time.toLocalDate());
        maybeApplyOverlimitFee(card, time.toLocalDate());
        return accepted(line, "CASH", id, card.id, "BR-03;BR-12", amount);
    }

    private Decision refund(int line, String[] p) throws Rejected {
        requireParts(p, 6);
        String id = p[1];
        Card card = requiredCardOrReject(p[3], "BR-08");
        LocalDateTime time = LocalDateTime.parse(p[2], TIMESTAMP_FORMAT);
        BigDecimal amount = positiveAmount(p[4], "BR-08");
        if (!registerId(id)) {
            throw new Rejected("BR-13");
        }
        PurchaseRecord purchase = card.purchases.get(p[5]);
        PriorPurchase prior = priorPurchases.get(p[5]);
        if (purchase == null && (prior == null || !prior.cardId.equals(card.id))) {
            throw new Rejected("BR-08");
        }
        BigDecimal originalAmount = purchase == null ? prior.amount : purchase.amount;
        BigDecimal alreadyRefunded = purchase == null ? prior.refunded : purchase.refunded;
        if (alreadyRefunded.add(amount).compareTo(originalAmount) > 0) {
            throw new Rejected("BR-08");
        }

        BigDecimal pointBasis = BigDecimal.ZERO;
        List<PointLot> sourceLots;
        int alreadyClawed;
        if (purchase != null) {
            purchase.refunded = purchase.refunded.add(amount);
            pointBasis = BigDecimal.valueOf(purchase.pointsAwarded);
            sourceLots = purchase.pointLots;
            alreadyClawed = purchase.pointsClawedBack;
        } else {
            prior.refunded = prior.refunded.add(amount);
            pointBasis = BigDecimal.valueOf(prior.pointsAwarded);
            sourceLots = prior.pointLots;
            alreadyClawed = prior.pointsClawedBack;
        }
        BigDecimal targetClawback = pointBasis.multiply(alreadyRefunded.add(amount))
                .divide(originalAmount, 0, RoundingMode.DOWN);
        int clawback = Math.max(0, targetClawback.intValue() - alreadyClawed);
        int removed = clawbackPoints(sourceLots, clawback);
        if (purchase != null) {
            purchase.pointsClawedBack += removed;
        } else {
            prior.pointsClawedBack += removed;
        }
        card.pointsClawedBackThisCycle += removed;

        if (purchase != null && purchase.charge != null) {
            applyCreditToCharge(purchase.charge, amount, time.toLocalDate());
        } else {
            card.creditBalance = card.creditBalance.add(amount);
        }
        postCredit(card, time.toLocalDate(), id, "REFUND", "Refund for " + p[5], amount);
        maybeApplyOverlimitFee(card, time.toLocalDate());
        return accepted(line, "REFUND", id, card.id, "BR-08", amount);
    }

    private Decision fee(int line, String[] p) throws Rejected {
        requireParts(p, 6);
        String id = p[1];
        Card card = requiredCardOrReject(p[3], "BR-12");
        LocalDateTime time = LocalDateTime.parse(p[2], TIMESTAMP_FORMAT);
        BigDecimal amount = positiveAmount(p[4], "BR-12");
        if (!registerId(id)) {
            throw new Rejected("BR-13");
        }
        if (!"ANNUAL_FEE".equals(p[5])) {
            throw new Rejected("BR-12");
        }
        postFee(card, time.toLocalDate(), id, "ANNUAL_FEE", "Annual fee", amount);
        maybeApplyOverlimitFee(card, time.toLocalDate());
        return accepted(line, "FEE", id, card.id, "BR-12", amount);
    }

    private Decision payment(int line, String[] p) throws Rejected {
        requireParts(p, 6);
        String id = p[1];
        Card card = requiredCardOrReject(p[3], "BR-07");
        LocalDateTime time = LocalDateTime.parse(p[2], TIMESTAMP_FORMAT);
        if (!registerId(id)) {
            throw new Rejected("BR-13");
        }
        if (!Arrays.asList("UPI", "NEFT", "NETBANKING", "CHEQUE", "AUTO_DEBIT").contains(p[5])) {
            throw new Rejected("BR-07");
        }
        BigDecimal amount;
        if ("STATEMENT_TOTAL".equals(p[4]) || "MIN_DUE".equals(p[4])) {
            if (card.statements.isEmpty()) {
                throw new Rejected("BR-07");
            }
            Statement latest = card.statements.get(card.statements.size() - 1);
            amount = "MIN_DUE".equals(p[4]) ? latest.minimumDue : latest.totalDue.max(ZERO);
        } else {
            amount = positiveAmount(p[4], "BR-07");
        }
        if (amount.signum() <= 0) {
            throw new Rejected("BR-07");
        }
        allocatePayment(card, amount, time.toLocalDate());
        postCredit(card, time.toLocalDate(), id, "PAYMENT", "Payment (" + p[5] + ")", amount);
        return accepted(line, "PAYMENT", id, card.id, "BR-07", amount);
    }

    private Decision convertEmi(int line, String[] p) throws Rejected {
        requireParts(p, 6);
        String id = p[1];
        Card card = requiredCardOrReject(p[3], "BR-10");
        LocalDateTime time = LocalDateTime.parse(p[2], TIMESTAMP_FORMAT);
        if (!registerId(id)) {
            throw new Rejected("BR-13");
        }
        PurchaseRecord purchase = card.purchases.get(p[4]);
        int tenure;
        try {
            tenure = Integer.parseInt(p[5]);
        } catch (NumberFormatException exception) {
            throw new Rejected("BR-10");
        }
        if (purchase == null || purchase.converted || !purchase.cardId.equals(card.id)
                || purchase.amount.compareTo(new BigDecimal("2500.00")) < 0
                || ChronoUnit.DAYS.between(purchase.date, time.toLocalDate()) < 0
                || ChronoUnit.DAYS.between(purchase.date, time.toLocalDate()) > 30
                || purchase.interestBearing
                || !Arrays.asList(3, 6, 9, 12).contains(tenure)) {
            throw new Rejected("BR-10");
        }
        purchase.converted = true;
        if (purchase.charge != null) {
            applyCreditToCharge(purchase.charge, purchase.charge.balance, time.toLocalDate());
        }
        postCredit(card, time.toLocalDate(), id, "EMI_CONVERSION_CREDIT",
                "Purchase converted to EMI: " + purchase.id, purchase.amount);
        BigDecimal processingFee = money(purchase.amount.multiply(new BigDecimal("0.01")))
                .max(new BigDecimal("99.00"));
        postFee(card, time.toLocalDate(), id + "-PROCESSING", "PROCESSING_FEE",
                "EMI processing fee", processingFee);
        Emi emi = new Emi("EMI-" + purchase.id, card.id, purchase.id,
                purchase.amount, time.toLocalDate(), tenure);
        card.emis.put(purchase.id, emi);
        emi.createSchedule(nextStatementDate(card, time.toLocalDate()));
        maybeApplyOverlimitFee(card, time.toLocalDate());
        return accepted(line, "EMI_CONVERT", id, card.id, "BR-10;BR-12", purchase.amount);
    }

    private Decision forecloseEmi(int line, String[] p) throws Rejected {
        requireParts(p, 5);
        String id = p[1];
        Card card = requiredCardOrReject(p[3], "BR-11");
        LocalDateTime time = LocalDateTime.parse(p[2], TIMESTAMP_FORMAT);
        if (!registerId(id)) {
            throw new Rejected("BR-13");
        }
        Emi emi = card.emis.get(p[4]);
        if (emi == null || emi.closed
                || time.toLocalDate().isBefore(emi.conversionDate.plusMonths(3))) {
            throw new Rejected("BR-11");
        }
        BigDecimal outstandingPrincipal = emi.outstandingPrincipal(card);
        if (outstandingPrincipal.signum() <= 0) {
            throw new Rejected("BR-11");
        }
        BigDecimal futurePrincipal = emi.futurePrincipal;
        if (futurePrincipal.signum() > 0) {
            Charge principalCharge = postDebit(card, time.toLocalDate(), id,
                    "EMI_PRINCIPAL", "Foreclosed EMI principal", futurePrincipal,
                    Charge.EMI, emi);
            emi.principalCharges.add(principalCharge);
            emi.futurePrincipal = ZERO;
        }
        BigDecimal foreclosureFee = money(outstandingPrincipal.multiply(new BigDecimal("0.03")));
        postFee(card, time.toLocalDate(), id, "FORECLOSURE_FEE", "EMI foreclosure fee", foreclosureFee);
        emi.closed = true;
        maybeApplyOverlimitFee(card, time.toLocalDate());
        return accepted(line, "EMI_FORECLOSE", id, card.id, "BR-11;BR-12", futurePrincipal);
    }

    private Decision redeem(int line, String[] p) throws Rejected {
        requireParts(p, 5);
        String id = p[1];
        Card card = requiredCardOrReject(p[3], "BR-09");
        LocalDateTime time = LocalDateTime.parse(p[2], TIMESTAMP_FORMAT);
        int points;
        try {
            points = Integer.parseInt(p[4]);
        } catch (NumberFormatException exception) {
            throw new Rejected("BR-09");
        }
        if (!registerId(id)) {
            throw new Rejected("BR-13");
        }
        if (points < 500 || points > card.availablePoints()) {
            throw new Rejected("BR-09");
        }
        redeemPoints(card, points);
        BigDecimal credit = money(new BigDecimal(points).multiply(new BigDecimal("0.25")));
        card.pointsRedeemedThisCycle += points;
        card.creditBalance = card.creditBalance.add(credit);
        postCredit(card, time.toLocalDate(), id, "POINTS_CREDIT", "Reward points redemption", credit);
        maybeApplyOverlimitFee(card, time.toLocalDate());
        return accepted(line, "REDEEM", id, card.id, "BR-09", credit);
    }

    private void generateStatements(LocalDate date) {
        List<Card> ordered = new ArrayList<Card>(cards.values());
        Collections.sort(ordered, Comparator.comparing(card -> card.id));
        for (Card card : ordered) {
            if (effectiveBillingDate(card.billingDay, date.getYear(), date.getMonthValue()) != date.getDayOfMonth()) {
                continue;
            }
            postEmiInstalments(card, date);
            Statement statement = createStatement(card, date);
            statements.add(statement);
            card.statements.add(statement);
            card.periodStart = date.plusDays(1);
            card.pointsOpening = card.availablePoints();
            card.pointsEarnedThisCycle = 0;
            card.pointsRedeemedThisCycle = 0;
            card.pointsExpiredThisCycle = 0;
            card.pointsClawedBackThisCycle = 0;
            card.pointsClosing = card.availablePoints();
            card.overlimitFeeApplied = false;
        }
    }

    private void postEmiInstalments(Card card, LocalDate date) {
        for (Emi emi : card.emis.values()) {
            if (emi.closed || emi.installmentsPosted >= emi.tenure) {
                continue;
            }
            EmiInstallment installment = emi.installments.get(emi.installmentsPosted);
            if (installment.date.isAfter(date)) {
                continue;
            }
            String ref = "EMI-" + emi.purchaseId + "-" + (emi.installmentsPosted + 1);
            Charge principal = postDebit(card, date, ref, "EMI_PRINCIPAL",
                    "EMI principal installment " + (emi.installmentsPosted + 1),
                    installment.principal, Charge.EMI, emi);
            emi.principalCharges.add(principal);
            if (installment.interest.signum() > 0) {
                postDebit(card, date, ref, "EMI_INTEREST",
                        "EMI interest installment " + (emi.installmentsPosted + 1),
                        installment.interest, Charge.EMI, emi);
                postGst(card, date, ref + "-GST", installment.interest, "GST on EMI interest");
            }
            emi.futurePrincipal = money(emi.futurePrincipal.subtract(installment.principal).max(ZERO));
            emi.installmentsPosted++;
        }
    }

    private static LocalDate nextStatementDate(Card card, LocalDate conversionDate) {
        YearMonth month = YearMonth.from(conversionDate);
        LocalDate statementDate = month.atDay(effectiveBillingDate(
                card.billingDay, month.getYear(), month.getMonthValue()));
        if (!statementDate.isAfter(conversionDate)) {
            month = month.plusMonths(1);
            statementDate = month.atDay(effectiveBillingDate(
                    card.billingDay, month.getYear(), month.getMonthValue()));
        }
        return statementDate;
    }

    private Statement createStatement(Card card, LocalDate date) {
        Statement statement = new Statement("STM-" + card.id + "-"
                + YearMonth.from(date).format(DateTimeFormatter.ofPattern("yyyyMM")),
                card.id, date, card.periodStart, date, dueDate(date), card.pointsOpening);
        statement.openingBalance = card.statements.isEmpty()
                ? ZERO : card.statements.get(card.statements.size() - 1).totalDue;

        BigDecimal postingsTotal = ZERO;
        for (Posting posting : card.postings) {
            if (!posting.date.isBefore(statement.periodFrom) && !posting.date.isAfter(date)) {
                statement.postings.add(posting);
                postingsTotal = postingsTotal.add(posting.amount);
                statement.accumulate(posting);
            }
        }
        statement.totalDue = money(statement.openingBalance.add(postingsTotal));
        statement.pointsEarned = card.pointsEarnedThisCycle;
        statement.pointsRedeemed = card.pointsRedeemedThisCycle;
        statement.pointsExpired = card.pointsExpiredThisCycle;
        statement.pointsClawedBack = card.pointsClawedBackThisCycle;
        statement.pointsClosing = card.availablePoints();
        statement.minimumDue = minimumDue(statement);
        card.pointsClosing = statement.pointsClosing;
        return statement;
    }

    private static BigDecimal minimumDue(Statement statement) {
        if (statement.totalDue.signum() <= 0) {
            return ZERO;
        }
        if (statement.totalDue.compareTo(new BigDecimal("200.00")) < 0) {
            return statement.totalDue;
        }
        BigDecimal due = statement.totalDue.multiply(new BigDecimal("0.05"))
                .add(statement.fees)
                .add(statement.interest)
                .add(statement.emiInstalments);
        return money(due.max(new BigDecimal("200.00")).min(statement.totalDue));
    }

    private LocalDate dueDate(LocalDate statementDate) {
        LocalDate due = statementDate.plusDays(20);
        while (due.getDayOfWeek() == DayOfWeek.SUNDAY || holidays.contains(due)) {
            due = due.plusDays(1);
        }
        return due;
    }

    private void expirePoints(LocalDate date) {
        for (Card card : cards.values()) {
            for (PointLot lot : card.pointLots) {
                if (!lot.expired && !lot.expiryDate.isAfter(date)) {
                    card.pointsExpiredThisCycle += lot.remaining;
                    lot.remaining = 0;
                    lot.expired = true;
                }
            }
        }
    }

    private void printPoints(String cardId, LocalDate date) {
        Card card = requiredCard(cardId);
        pointBalances.add(new PointBalance(card.id, date, card.availablePoints()));
    }

    private int awardPoints(Card card, PurchaseRecord purchase) {
        int amountPerHundred = (purchase.category.equals("DINING") || purchase.category.equals("TRAVEL"))
                ? 4 : (purchase.category.equals("FUEL") ? 0 : 2);
        if (amountPerHundred == 0) {
            return 0;
        }
        BigDecimal hundreds = purchase.amount.divideToIntegralValue(new BigDecimal("100"));
        BigDecimal raw = hundreds.multiply(BigDecimal.valueOf(amountPerHundred));
        if ("PLATINUM".equals(card.tier)) {
            raw = raw.multiply(new BigDecimal("1.5"));
        }
        int monthlyRemaining = 5000 - card.pointsEarnedByMonth.getOrDefault(
                YearMonth.from(purchase.date), 0);
        int awarded = Math.max(0, Math.min(raw.setScale(0, RoundingMode.DOWN).intValue(), monthlyRemaining));
        if (awarded > 0) {
            YearMonth month = YearMonth.from(purchase.date);
            card.pointsEarnedByMonth.put(month,
                    card.pointsEarnedByMonth.getOrDefault(month, 0) + awarded);
            card.pointsEarnedThisCycle += awarded;
            PointLot lot = new PointLot("LOT-TXN-" + purchase.id, month, awarded,
                    purchase.id, expiryDate(month));
            card.pointLots.add(lot);
            purchase.pointLots.add(lot);
        }
        return awarded;
    }

    private int clawbackPoints(List<PointLot> lots, int requested) {
        Collections.sort(lots, Comparator.comparing(lot -> lot.expiryDate));
        int remaining = requested;
        for (PointLot lot : lots) {
            if (lot.expired || remaining == 0) {
                continue;
            }
            int removed = Math.min(lot.remaining, remaining);
            lot.remaining -= removed;
            remaining -= removed;
        }
        return requested - remaining;
    }

    private void redeemPoints(Card card, int points) throws Rejected {
        List<PointLot> lots = new ArrayList<PointLot>(card.pointLots);
        Collections.sort(lots, Comparator.comparing((PointLot lot) -> lot.accrualMonth)
                .thenComparing(lot -> lot.id));
        int remaining = points;
        for (PointLot lot : lots) {
            if (lot.expired || remaining == 0) {
                continue;
            }
            int redeemed = Math.min(lot.remaining, remaining);
            lot.remaining -= redeemed;
            remaining -= redeemed;
        }
        if (remaining > 0) {
            throw new Rejected("BR-09");
        }
    }

    private void maybeApplyOverlimitFee(Card card, LocalDate date) {
        if (card.overlimitFeeApplied || card.outstanding().compareTo(card.creditLimit) <= 0) {
            return;
        }
        BigDecimal excess = card.outstanding().subtract(card.creditLimit);
        BigDecimal fee = money(excess.multiply(OVERLIMIT_FEE_RATE)).max(MINIMUM_FEE);
        card.overlimitFeeApplied = true;
        postFee(card, date, "AUTO-" + card.id + "-OVERLIMIT-" + date,
                "OVERLIMIT_FEE", "Over-limit fee", fee);
    }

    private void postFee(Card card, LocalDate date, String ref, String type,
            String description, BigDecimal amount) {
        postDebit(card, date, ref, type, description, amount, Charge.FEE, null);
        postGst(card, date, ref + "-GST", amount, "GST on " + description.toLowerCase());
    }

    private void postInterest(Card card, LocalDate date, String ref, BigDecimal amount) {
        BigDecimal charge = money(amount);
        if (charge.signum() <= 0) {
            return;
        }
        postDebit(card, date, ref, "INTEREST", "Daily interest", charge, Charge.INTEREST, null);
        postGst(card, date, ref + "-GST", charge, "GST on interest");
    }

    private void postGst(Card card, LocalDate date, String ref, BigDecimal taxable,
            String description) {
        BigDecimal amount = gst(taxable);
        if (amount.signum() > 0) {
            postDebit(card, date, ref, "GST", description, amount, Charge.FEE, null);
        }
    }

    private Charge postDebit(Card card, LocalDate date, String ref, String type,
            String description, BigDecimal amount, int priority, Emi emi) {
        BigDecimal posted = money(amount);
        Posting posting = new Posting(card, date, ref, type, description, posted, postingSequence++);
        card.postings.add(posting);
        Charge charge = new Charge(posting, posted, priority, emi);
        card.charges.add(charge);
        return charge;
    }

    private void postCredit(Card card, LocalDate date, String ref, String type,
            String description, BigDecimal amount) {
        card.postings.add(new Posting(card, date, ref, type, description,
                money(amount).negate(), postingSequence++));
    }

    private void allocatePayment(Card card, BigDecimal amount, LocalDate date) {
        List<Charge> charges = new ArrayList<Charge>(card.charges);
        Collections.sort(charges, Comparator.comparingInt((Charge charge) -> charge.priority)
                .thenComparingInt(charge -> charge.posting.sequence));
        BigDecimal remaining = amount;
        for (Charge charge : charges) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal applied = remaining.min(charge.balance);
            charge.balance = money(charge.balance.subtract(applied));
            charge.recordBalance(date);
            remaining = money(remaining.subtract(applied));
        }
        if (remaining.signum() > 0) {
            card.creditBalance = money(card.creditBalance.add(remaining));
        }
    }

    private void applyCreditToCharge(Charge charge, BigDecimal amount, LocalDate date) {
        BigDecimal applied = amount.min(charge.balance);
        charge.balance = money(charge.balance.subtract(applied));
        charge.recordBalance(date);
        BigDecimal excess = money(amount.subtract(applied));
        if (excess.signum() > 0) {
            charge.card.creditBalance = money(charge.card.creditBalance.add(excess));
        }
    }

    private boolean registerId(String id) {
        return seenIds.add(id);
    }

    private Card requiredCardOrReject(String id, String rule) throws Rejected {
        Card card = cards.get(id);
        if (card == null) {
            throw new Rejected(rule);
        }
        return card;
    }

    private Card requiredCard(String id) {
        Card card = cards.get(id);
        if (card == null) {
            throw new IllegalArgumentException("Unknown card: " + id);
        }
        return card;
    }

    private static BigDecimal positiveAmount(String value, String rule) throws Rejected {
        try {
            BigDecimal amount = money(new BigDecimal(value));
            if (amount.signum() <= 0) {
                throw new Rejected(rule);
            }
            return amount;
        } catch (NumberFormatException exception) {
            throw new Rejected(rule);
        }
    }

    private static boolean isPurchaseCategory(String category) {
        return Arrays.asList("GROCERY", "DINING", "TRAVEL", "FUEL", "SHOPPING",
                "ELECTRONICS", "UTILITIES", "ENTERTAINMENT", "HEALTH").contains(category);
    }

    private static int effectiveBillingDate(int billingDay, int year, int month) {
        return Math.min(billingDay, YearMonth.of(year, month).lengthOfMonth());
    }

    private static LocalDate expiryDate(YearMonth accrualMonth) {
        YearMonth expiryMonth = accrualMonth.plusMonths(24);
        return expiryMonth.atEndOfMonth();
    }

    private static BigDecimal gst(BigDecimal amount) {
        return money(amount.multiply(GST_RATE));
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    private static void requireParts(String[] parts, int expected) throws Rejected {
        if (parts.length != expected) {
            throw new Rejected("BR-13");
        }
    }

    private Decision accepted(int line, String command, String ref,
            String card, String rules, BigDecimal amount) {
        return new Decision(line, command, ref, card, "ACCEPTED", rules, money(amount));
    }

    private Map<String, String> buildOutputs() {
        Collections.sort(statements, Comparator.comparing((Statement item) -> item.statementDate)
                .thenComparing(item -> item.cardId));
        Map<String, String> outputs = new LinkedHashMap<String, String>();
        outputs.put("decisions.csv", decisionsCsv());
        outputs.put("statements.csv", statementsCsv());
        outputs.put("statement_lines.csv", statementLinesCsv());
        outputs.put("emi_schedule.csv", emiScheduleCsv());
        outputs.put("points_balance.csv", pointsBalanceCsv());
        return outputs;
    }

    private String decisionsCsv() {
        StringBuilder csv = new StringBuilder("line_no,command,ref_id,card_id,decision,rule_ids,amount_posted\n");
        for (Decision decision : decisions) {
            appendCsvRow(csv, decision.line, decision.command, decision.refId, decision.cardId,
                    decision.outcome, decision.ruleIds, formatMoney(decision.amount));
        }
        return csv.toString();
    }

    private String statementsCsv() {
        StringBuilder csv = new StringBuilder("statement_id,card_id,statement_date,period_from,period_to,opening_balance,purchases,cash_advances,credits,payments,interest,fees,gst,emi_instalments,total_due,minimum_due,due_date,points_opening,points_earned,points_redeemed,points_expired,points_clawed_back,points_closing\n");
        for (Statement statement : statements) {
            appendCsvRow(csv, statement.id, statement.cardId, statement.statementDate,
                    statement.periodFrom, statement.periodTo, formatMoney(statement.openingBalance),
                    formatMoney(statement.purchases), formatMoney(statement.cashAdvances),
                    formatMoney(statement.credits), formatMoney(statement.payments),
                    formatMoney(statement.interest), formatMoney(statement.fees),
                    formatMoney(statement.gst), formatMoney(statement.emiInstalments),
                    formatMoney(statement.totalDue), formatMoney(statement.minimumDue),
                    statement.dueDate, statement.pointsOpening, statement.pointsEarned,
                    statement.pointsRedeemed, statement.pointsExpired,
                    statement.pointsClawedBack, statement.pointsClosing);
        }
        return csv.toString();
    }

    private String statementLinesCsv() {
        StringBuilder csv = new StringBuilder("statement_id,line_no,posting_date,ref_id,line_type,description,amount\n");
        for (Statement statement : statements) {
            List<Posting> postings = new ArrayList<Posting>(statement.postings);
            Collections.sort(postings, Comparator.comparingInt(posting -> posting.sequence));
            int line = 1;
            for (Posting posting : postings) {
                appendCsvRow(csv, statement.id, line++, posting.date, posting.refId,
                        posting.type, posting.description, formatMoney(posting.amount));
            }
        }
        return csv.toString();
    }

    private String emiScheduleCsv() {
        StringBuilder csv = new StringBuilder("emi_id,card_id,source_txn_id,instalment_no,statement_date,principal,interest,gst,closing_principal\n");
        Collections.sort(emiSchedule, Comparator.comparing((EmiScheduleRow row) -> row.cardId)
                .thenComparing(row -> row.emiId).thenComparingInt(row -> row.installment));
        for (EmiScheduleRow row : emiSchedule) {
            appendCsvRow(csv, row.emiId, row.cardId, row.sourceId, row.installment,
                    row.date, formatMoney(row.principal), formatMoney(row.interest),
                    formatMoney(row.gst), formatMoney(row.closingPrincipal));
        }
        return csv.toString();
    }

    private String pointsBalanceCsv() {
        StringBuilder csv = new StringBuilder("card_id,as_of_date,points_balance\n");
        for (PointBalance balance : pointBalances) {
            appendCsvRow(csv, balance.cardId, balance.date, balance.points);
        }
        return csv.toString();
    }

    private static String formatMoney(BigDecimal amount) {
        return money(amount).toPlainString();
    }

    private static void appendCsvRow(StringBuilder output, Object... fields) {
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                output.append(',');
            }
            output.append(csvField(String.valueOf(fields[i])));
        }
        output.append('\n');
    }

    private static String csvField(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private final class Card {
        private final String id;
        private final String holderName;
        private final String mobile;
        private final String tier;
        private final BigDecimal creditLimit;
        private final BigDecimal cashLimit;
        private final int billingDay;
        private final BigDecimal apr;
        private final LocalDate registeredDate;
        private final List<Posting> postings = new ArrayList<Posting>();
        private final List<Charge> charges = new ArrayList<Charge>();
        private final List<CashRecord> cashAdvances = new ArrayList<CashRecord>();
        private final Map<String, PurchaseRecord> purchases = new LinkedHashMap<String, PurchaseRecord>();
        private final List<PointLot> pointLots = new ArrayList<PointLot>();
        private final Map<YearMonth, Integer> pointsEarnedByMonth = new HashMap<YearMonth, Integer>();
        private final Map<String, Emi> emis = new LinkedHashMap<String, Emi>();
        private final List<Statement> statements = new ArrayList<Statement>();
        private LocalDate periodStart = PERIOD_START;
        private BigDecimal creditBalance = ZERO;
        private int pointsOpening;
        private int pointsClosing;
        private int pointsEarnedThisCycle;
        private int pointsRedeemedThisCycle;
        private int pointsExpiredThisCycle;
        private int pointsClawedBackThisCycle;
        private boolean graceLost;
        private boolean overlimitFeeApplied;

        private Card(String id, String holderName, String mobile, String tier,
                BigDecimal creditLimit, BigDecimal cashLimit, int billingDay,
                BigDecimal apr, LocalDate registeredDate) {
            this.id = id;
            this.holderName = holderName;
            this.mobile = mobile;
            this.tier = tier;
            this.creditLimit = creditLimit;
            this.cashLimit = cashLimit;
            this.billingDay = billingDay;
            this.apr = apr;
            this.registeredDate = registeredDate;
        }

        private BigDecimal outstanding() {
            BigDecimal amount = creditBalance.negate();
            for (Charge charge : charges) {
                amount = amount.add(charge.balance);
            }
            for (Emi emi : emis.values()) {
                amount = amount.add(emi.futurePrincipal);
            }
            return amount.max(ZERO);
        }

        private BigDecimal cashOutstanding() {
            BigDecimal amount = ZERO;
            for (CashRecord cash : cashAdvances) {
                amount = amount.add(cash.charge.balance);
            }
            return amount;
        }

        private int availablePoints() {
            int amount = 0;
            for (PointLot lot : pointLots) {
                if (!lot.expired) {
                    amount += lot.remaining;
                }
            }
            return amount;
        }
    }

    private final class Posting {
        private final Card card;
        private final LocalDate date;
        private final String refId;
        private final String type;
        private final String description;
        private final BigDecimal amount;
        private final int sequence;

        private Posting(Card card, LocalDate date, String refId, String type,
                String description, BigDecimal amount, int sequence) {
            this.card = card;
            this.date = date;
            this.refId = refId;
            this.type = type;
            this.description = description;
            this.amount = amount;
            this.sequence = sequence;
        }
    }

    private final class Charge {
        private static final int FEE = 0;
        private static final int INTEREST = 1;
        private static final int EMI = 2;
        private static final int CASH = 3;
        private static final int PURCHASE = 4;
        private final Posting posting;
        private final Card card;
        private BigDecimal balance;
        private final int priority;
        private final Emi emi;
        private final List<BalancePoint> balanceHistory = new ArrayList<BalancePoint>();

        private Charge(Posting posting, BigDecimal balance, int priority, Emi emi) {
            this.posting = posting;
            this.card = posting.card;
            this.balance = balance;
            this.priority = priority;
            this.emi = emi;
            recordBalance(posting.date);
        }

        private void recordBalance(LocalDate date) {
            balanceHistory.add(new BalancePoint(date, balance));
        }

        private BigDecimal balanceAt(LocalDate date) {
            BigDecimal result = ZERO;
            for (BalancePoint point : balanceHistory) {
                if (point.date.isAfter(date)) {
                    break;
                }
                result = point.balance;
            }
            return result;
        }
    }

    private final class BalancePoint {
        private final LocalDate date;
        private final BigDecimal balance;

        private BalancePoint(LocalDate date, BigDecimal balance) {
            this.date = date;
            this.balance = balance;
        }
    }

    private final class PurchaseRecord {
        private final String id;
        private final String cardId;
        private final LocalDate date;
        private final BigDecimal amount;
        private final String merchant;
        private final String category;
        private final List<PointLot> pointLots = new ArrayList<PointLot>();
        private Charge charge;
        private int pointsAwarded;
        private int pointsClawedBack;
        private BigDecimal refunded = ZERO;
        private LocalDate interestThrough;
        private boolean interestBearing;
        private boolean converted;

        private PurchaseRecord(String id, String cardId, LocalDate date, BigDecimal amount,
                String merchant, String category) {
            this.id = id;
            this.cardId = cardId;
            this.date = date;
            this.amount = amount;
            this.merchant = merchant;
            this.category = category;
        }
    }

    private final class CashRecord {
        private final LocalDate date;
        private final Charge charge;
        private LocalDate interestThrough;

        private CashRecord(LocalDate date, Charge charge) {
            this.date = date;
            this.charge = charge;
        }
    }

    private final class PriorPurchase {
        private final String id;
        private final String cardId;
        private final BigDecimal amount;
        private final String merchant;
        private final String category;
        private final int pointsAwarded;
        private final List<PointLot> pointLots = new ArrayList<PointLot>();
        private BigDecimal refunded = ZERO;
        private int pointsClawedBack;

        private PriorPurchase(String id, String cardId, BigDecimal amount,
                String merchant, String category, int pointsAwarded) {
            this.id = id;
            this.cardId = cardId;
            this.amount = amount;
            this.merchant = merchant;
            this.category = category;
            this.pointsAwarded = pointsAwarded;
        }
    }

    private final class PointLot {
        private final String id;
        private final YearMonth accrualMonth;
        private final String sourceId;
        private final LocalDate expiryDate;
        private int remaining;
        private boolean expired;

        private PointLot(String id, YearMonth accrualMonth, int points,
                String sourceId, LocalDate expiryDate) {
            this.id = id;
            this.accrualMonth = accrualMonth;
            this.remaining = points;
            this.sourceId = sourceId;
            this.expiryDate = expiryDate;
        }
    }

    private final class Emi {
        private final String id;
        private final String cardId;
        private final String purchaseId;
        private final BigDecimal originalPrincipal;
        private final LocalDate conversionDate;
        private final int tenure;
        private final BigDecimal monthlyRate;
        private final List<Charge> principalCharges = new ArrayList<Charge>();
        private final List<EmiInstallment> installments = new ArrayList<EmiInstallment>();
        private BigDecimal futurePrincipal;
        private int installmentsPosted;
        private boolean closed;

        private Emi(String id, String cardId, String purchaseId, BigDecimal principal,
                LocalDate conversionDate, int tenure) {
            this.id = id;
            this.cardId = cardId;
            this.purchaseId = purchaseId;
            this.originalPrincipal = principal;
            this.futurePrincipal = principal;
            this.conversionDate = conversionDate;
            this.tenure = tenure;
            this.monthlyRate = new BigDecimal("14").divide(new BigDecimal("1200"),
                    12, RoundingMode.HALF_UP);
        }

        private void createSchedule(LocalDate firstDate) {
            BigDecimal opening = originalPrincipal;
            double rate = monthlyRate.doubleValue();
            double factor = Math.pow(1.0 + rate, tenure);
            BigDecimal payment = money(originalPrincipal.multiply(monthlyRate)
                    .multiply(BigDecimal.valueOf(factor))
                    .divide(BigDecimal.valueOf(factor - 1.0), 12, RoundingMode.HALF_UP));
            for (int i = 0; i < tenure; i++) {
                BigDecimal interest = money(opening.multiply(monthlyRate));
                BigDecimal principal = i == tenure - 1
                        ? opening : money(payment.subtract(interest)).min(opening);
                BigDecimal closing = money(opening.subtract(principal).max(ZERO));
                YearMonth month = YearMonth.from(firstDate).plusMonths(i);
                LocalDate date = month.atDay(effectiveBillingDate(
                        cards.get(cardId).billingDay, month.getYear(), month.getMonthValue()));
                EmiInstallment installment = new EmiInstallment(date, principal, interest, closing);
                installments.add(installment);
                emiSchedule.add(new EmiScheduleRow(id, cardId, purchaseId,
                        i + 1, date, principal, interest,
                        gst(interest), closing));
                opening = closing;
            }
        }

        private BigDecimal outstandingPrincipal(Card card) {
            BigDecimal amount = futurePrincipal;
            for (Charge charge : principalCharges) {
                amount = amount.add(charge.balance);
            }
            return money(amount);
        }
    }

    private final class EmiInstallment {
        private final LocalDate date;
        private final BigDecimal principal;
        private final BigDecimal interest;
        private final BigDecimal closingPrincipal;

        private EmiInstallment(LocalDate date, BigDecimal principal,
                BigDecimal interest, BigDecimal closingPrincipal) {
            this.date = date;
            this.principal = principal;
            this.interest = interest;
            this.closingPrincipal = closingPrincipal;
        }
    }

    private final class Statement {
        private final String id;
        private final String cardId;
        private final LocalDate statementDate;
        private final LocalDate periodFrom;
        private final LocalDate periodTo;
        private final LocalDate dueDate;
        private final List<Posting> postings = new ArrayList<Posting>();
        private BigDecimal openingBalance = ZERO;
        private BigDecimal purchases = ZERO;
        private BigDecimal cashAdvances = ZERO;
        private BigDecimal credits = ZERO;
        private BigDecimal payments = ZERO;
        private BigDecimal interest = ZERO;
        private BigDecimal fees = ZERO;
        private BigDecimal gst = ZERO;
        private BigDecimal emiInstalments = ZERO;
        private BigDecimal totalDue = ZERO;
        private BigDecimal minimumDue = ZERO;
        private int pointsOpening;
        private int pointsEarned;
        private int pointsRedeemed;
        private int pointsExpired;
        private int pointsClawedBack;
        private int pointsClosing;
        private boolean dueProcessed;

        private Statement(String id, String cardId, LocalDate statementDate,
                LocalDate periodFrom, LocalDate periodTo, LocalDate dueDate, int pointsOpening) {
            this.id = id;
            this.cardId = cardId;
            this.statementDate = statementDate;
            this.periodFrom = periodFrom;
            this.periodTo = periodTo;
            this.dueDate = dueDate;
            this.pointsOpening = pointsOpening;
        }

        private void accumulate(Posting posting) {
            switch (posting.type) {
                case "PURCHASE":
                    purchases = purchases.add(posting.amount);
                    break;
                case "CASH":
                    cashAdvances = cashAdvances.add(posting.amount);
                    break;
                case "PAYMENT":
                    break;
                case "REFUND":
                case "POINTS_CREDIT":
                case "EMI_CONVERSION_CREDIT":
                    credits = credits.add(posting.amount);
                    break;
                case "INTEREST":
                    interest = interest.add(posting.amount);
                    break;
                case "GST":
                    gst = gst.add(posting.amount);
                    break;
                case "EMI_PRINCIPAL":
                case "EMI_INTEREST":
                    emiInstalments = emiInstalments.add(posting.amount);
                    break;
                case "CASH_FEE":
                case "LATE_FEE":
                case "OVERLIMIT_FEE":
                case "ANNUAL_FEE":
                case "PROCESSING_FEE":
                case "FORECLOSURE_FEE":
                    fees = fees.add(posting.amount);
                    break;
                default:
                    break;
            }
            if ("PAYMENT".equals(posting.type)) {
                payments = payments.add(posting.amount);
            }
        }
    }

    private final class Decision {
        private final int line;
        private final String command;
        private final String refId;
        private final String cardId;
        private final String outcome;
        private final String ruleIds;
        private final BigDecimal amount;

        private Decision(int line, String command, String refId, String cardId,
                String outcome, String ruleIds, BigDecimal amount) {
            this.line = line;
            this.command = command;
            this.refId = refId;
            this.cardId = cardId;
            this.outcome = outcome;
            this.ruleIds = ruleIds;
            this.amount = amount;
        }
    }

    private final class PointBalance {
        private final String cardId;
        private final LocalDate date;
        private final int points;

        private PointBalance(String cardId, LocalDate date, int points) {
            this.cardId = cardId;
            this.date = date;
            this.points = points;
        }
    }

    private final class EmiScheduleRow {
        private final String emiId;
        private final String cardId;
        private final String sourceId;
        private final int installment;
        private final LocalDate date;
        private final BigDecimal principal;
        private final BigDecimal interest;
        private final BigDecimal gst;
        private final BigDecimal closingPrincipal;

        private EmiScheduleRow(String emiId, String cardId, String sourceId, int installment,
                LocalDate date, BigDecimal principal, BigDecimal interest,
                BigDecimal gst, BigDecimal closingPrincipal) {
            this.emiId = emiId;
            this.cardId = cardId;
            this.sourceId = sourceId;
            this.installment = installment;
            this.date = date;
            this.principal = principal;
            this.interest = interest;
            this.gst = gst;
            this.closingPrincipal = closingPrincipal;
        }
    }

    private static final class Rejected extends Exception {
        private static final long serialVersionUID = 1L;
        private final String ruleId;

        private Rejected(String ruleId) {
            this.ruleId = ruleId;
        }
    }
}
