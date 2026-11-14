package com.billing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class AppTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void bundledDatasetExercisesBillingPointsInterestAndEmiRules() throws Exception {
        SimulationEngine engine = new SimulationEngine(
                resource("cards.csv"),
                resource("holidays.csv"),
                resource("points_opening.csv"),
                resource("prior_purchases.csv"));
        Map<String, String> outputs;
        try (InputStream commands = resource("commands.txt")) {
            outputs = engine.execute(commands);
        }

        assertEquals(Arrays.asList("decisions.csv", "statements.csv", "statement_lines.csv",
                "emi_schedule.csv", "points_balance.csv"), Arrays.asList(
                outputs.keySet().toArray(new String[outputs.size()])));
        assertEquals(187, dataRowCount(outputs.get("decisions.csv")));
        assertEquals(72, dataRowCount(outputs.get("statements.csv")));
        assertEquals(10, dataRowCount(outputs.get("points_balance.csv")));
        assertTrue(outputs.get("decisions.csv").startsWith(
                "line_no,command,ref_id,card_id,decision,rule_ids,amount_posted\n"));
        assertTrue(outputs.get("statements.csv").startsWith(
                "statement_id,card_id,statement_date,period_from,period_to,opening_balance,purchases,cash_advances,credits,payments,interest,fees,gst,emi_instalments,total_due,minimum_due,due_date,points_opening,points_earned,points_redeemed,points_expired,points_clawed_back,points_closing\n"));
        assertTrue(outputs.get("statement_lines.csv").startsWith(
                "statement_id,line_no,posting_date,ref_id,line_type,description,amount\n"));
        assertTrue(outputs.get("points_balance.csv").startsWith(
                "card_id,as_of_date,points_balance\n"));

        String[] decisions = lines(outputs.get("decisions.csv"));
        assertDecision(decisions, 53, "ACCEPTED", "BR-07");
        assertDecision(decisions, 86, "ACCEPTED", "BR-07");
        assertDecision(decisions, 76, "REJECTED", "BR-09");
        assertDecision(decisions, 129, "REJECTED", "BR-03");
        assertDecision(decisions, 116, "ACCEPTED", "BR-10;BR-12");
        assertDecision(decisions, 142, "REJECTED", "BR-10");
        assertDecision(decisions, 154, "REJECTED", "BR-11");
        assertDecision(decisions, 164, "REJECTED", "BR-13");
        assertDecision(decisions, 174, "ACCEPTED", "BR-09");
        assertDecision(decisions, 182, "REJECTED", "BR-10");
        assertDecision(decisions, 186, "ACCEPTED", "BR-10;BR-12");
        assertDecision(decisions, 224, "ACCEPTED", "BR-11;BR-12");
        assertEquals("125.00", decisionAt(decisions, 174)[6]);
        assertEquals("20000.00", decisionAt(decisions, 22)[6]);

        String[] statements = lines(outputs.get("statements.csv"));
        String[] card3February = statementById(statements, "STM-CARD-003-202602");
        assertEquals("1000.00", card3February[14]);
        assertEquals("200.00", card3February[15]);
        String[] card4February = statementById(statements, "STM-CARD-004-202602");
        assertEquals("1001.00", card4February[14]);
        assertEquals("200.00", card4February[15]);
        String[] feb28Card2 = statementById(statements, "STM-CARD-002-202602");
        assertEquals("2026-03-21", feb28Card2[16]);
        String[] card6March = statementById(statements, "STM-CARD-006-202603");
        assertEquals("2026-04-15", card6March[16]);
        assertTrue(Double.parseDouble(
                statementById(statements, "STM-CARD-006-202604")[10]) > 0);
        String[] card8April = statementById(statements, "STM-CARD-008-202604");
        assertEquals("2026-05-02", card8April[16]);
        assertEquals("0", statementById(statements, "STM-CARD-008-202603")[21]);
        assertEquals("5000", statementById(statements, "STM-CARD-005-202607")[18]);

        String[] points = lines(outputs.get("points_balance.csv"));
        assertTrue(containsRow(points, "CARD-007,2026-04-30,499"));
        assertTrue(containsRow(points, "CARD-007,2026-05-01,299"));

        String statementLines = outputs.get("statement_lines.csv");
        assertTrue(statementLines.contains(
                "STM-CARD-001-202603,5,2026-02-24,TXN-00015-CASH-FEE,CASH_FEE,Cash advance fee,500.00"));
        assertTrue(statementLines.contains(
                "STM-CARD-001-202603,6,2026-02-24,TXN-00015-CASH-FEE-GST,GST,GST on cash advance fee,90.00"));
        assertTrue(statementLines.contains("OVERLIMIT_FEE,Over-limit fee,500.00"));
        assertTrue(statementLines.contains(
                "AUTO-STM-CARD-004-202602-LATE,LATE_FEE,Late payment fee,500.00"));
        assertFalse(statementLines.contains("AUTO-STM-CARD-003-202602-LATE,LATE_FEE"));
        assertTrue(statementLines.contains("AUTO-STM-CARD-008-202604-LATE,LATE_FEE,Late payment fee,950.00"));
        assertTrue(statementLines.contains("TXN-00034-GST,GST,GST on annual fee,179.82"));
        assertTrue(statementLines.contains(
                "2026-04-16,AUTO-CARD-006-RETRO-INT-2026-03-01,INTEREST,Daily interest,12.02"));
        assertTrue(statementLines.contains("2026-04-01,TXN-00035,PAYMENT"));
        assertTrue(statementLines.contains("2026-05-03,TXN-00057,PAYMENT"));

        String[] emiRows = lines(outputs.get("emi_schedule.csv"));
        assertTrue(containsRowStartingWith(emiRows, "EMI-TXN-00060,CARD-005,TXN-00060,1,2026-06-20,"));
        assertTrue(containsRowStartingWith(emiRows, "EMI-TXN-00120,CARD-006,TXN-00120,1,2026-08-25,"));
        assertEquals(6, countRowsStartingWith(emiRows, "EMI-TXN-00060,"));
        assertEquals(30, dataRowCount(outputs.get("emi_schedule.csv")));
        assertEquals("758.32", rowStartingWith(emiRows, "EMI-TXN-00060,CARD-005,TXN-00060,1,")[6]);
        assertTrue(outputs.get("emi_schedule.csv").startsWith(
                "emi_id,card_id,source_txn_id,instalment_no,statement_date,principal,interest,gst,closing_principal\n"));

        Path outputDirectory = temporaryFolder.newFolder("engine-output").toPath();
        SimulationEngine.writeOutputs(outputDirectory, outputs);
        for (String file : outputs.keySet()) {
            Path written = outputDirectory.resolve(file);
            assertTrue(Files.exists(written));
            assertEquals(outputs.get(file),
                    new String(Files.readAllBytes(written), StandardCharsets.UTF_8));
        }
    }

    @Test
    public void openingPointLotsAreIncludedInBalanceQueries() throws Exception {
        SimulationEngine engine = new SimulationEngine(
                resource("cards.csv"),
                resource("holidays.csv"),
                resource("points_opening.csv"),
                resource("prior_purchases.csv"));
        String[] cardIds = {
            "CARD-001", "CARD-002", "CARD-003", "CARD-004",
            "CARD-005", "CARD-006", "CARD-007", "CARD-008"
        };
        StringBuilder commands = new StringBuilder();
        for (String cardId : cardIds) {
            commands.append("PRINT_POINTS|").append(cardId)
                    .append("|2026-02-01\n");
        }

        Map<String, String> outputs;
        try (InputStream commandInput = input(commands.toString())) {
            outputs = engine.execute(commandInput);
        }

        String[] points = lines(outputs.get("points_balance.csv"));
        assertEquals(9, points.length);
        assertTrue(containsRow(points, "CARD-001,2026-02-01,380"));
        assertTrue(containsRow(points, "CARD-002,2026-02-01,1200"));
        assertTrue(containsRow(points, "CARD-003,2026-02-01,340"));
        assertTrue(containsRow(points, "CARD-004,2026-02-01,320"));
        assertTrue(containsRow(points, "CARD-005,2026-02-01,1200"));
        assertTrue(containsRow(points, "CARD-006,2026-02-01,360"));
        assertTrue(containsRow(points, "CARD-007,2026-02-01,499"));
        assertTrue(containsRow(points, "CARD-008,2026-02-01,4500"));
    }

    @Test
    public void minimumDueExcludesSeparateGstAndPaymentCoversFeesFirst() throws Exception {
        SimulationEngine engine = new SimulationEngine(
                input("card_id,holder_name,holder_mobile,tier,credit_limit,cash_advance_limit,billing_day,apr,registered_date\n"
                        + "CARD-TEST,Test,0000000000,CLASSIC,10000.00,4000.00,1,36.5,2026-02-01\n"),
                input("date,holiday_name\n"),
                input("lot_id,card_id,accrual_month,points,source_txn_id\n"),
                input("txn_id,card_id,txn_date,amount,merchant,category,points_awarded\n"));
        String commands = "PURCHASE|P-001|2026-02-01 09:00:00|CARD-TEST|1000.00|Store|GROCERY\n"
                + "FEE|F-001|2026-02-01 10:00:00|CARD-TEST|100.00|ANNUAL_FEE\n"
                + "PURCHASE|P-002|2026-02-01 11:00:00|CARD-TEST|1000.00|Store|GROCERY\n"
                + "GENERATE_STATEMENTS|2026-02-01\n"
                + "PAYMENT|PAY-001|2026-02-21 23:59:00|CARD-TEST|MIN_DUE|UPI\n"
                + "GENERATE_STATEMENTS|2026-03-01\n";
        Map<String, String> outputs;
        try (InputStream input = input(commands)) {
            outputs = engine.execute(input);
        }

        String[] statements = lines(outputs.get("statements.csv"));
        assertEquals("2118.00", statementById(statements, "STM-CARD-TEST-202602")[14]);
        assertEquals("205.90", statementById(statements, "STM-CARD-TEST-202602")[15]);
        assertEquals("2026-02-21", statementById(statements, "STM-CARD-TEST-202602")[16]);
        assertTrue(outputs.get("statement_lines.csv").contains(
                "AUTO-CARD-TEST-RETRO-INT-2026-02-21,INTEREST,Daily interest,1.91"));
    }

    private static InputStream input(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private static InputStream resource(String name) {
        InputStream input = AppTest.class.getResourceAsStream("/" + name);
        assertNotNull("Missing test resource " + name, input);
        return input;
    }

    private static int dataRowCount(String csv) {
        return lines(csv).length - 1;
    }

    private static String[] lines(String csv) {
        return csv.trim().split("\\n");
    }

    private static void assertDecision(String[] decisions, int lineNumber,
            String outcome, String ruleIds) {
        String[] row = decisionAt(decisions, lineNumber);
        assertEquals(outcome, row[4]);
        assertEquals(ruleIds, row[5]);
    }

    private static String[] decisionAt(String[] decisions, int lineNumber) {
        for (int i = 1; i < decisions.length; i++) {
            String[] row = decisions[i].split(",", -1);
            if (Integer.parseInt(row[0]) == lineNumber) {
                return row;
            }
        }
        throw new AssertionError("Missing decision for command line " + lineNumber);
    }

    private static String[] statementById(String[] statements, String id) {
        for (int i = 1; i < statements.length; i++) {
            String[] row = statements[i].split(",", -1);
            if (row[0].equals(id)) {
                return row;
            }
        }
        throw new AssertionError("Missing statement " + id);
    }

    private static boolean containsRow(String[] rows, String expected) {
        for (String row : rows) {
            if (row.equals(expected)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsRowStartingWith(String[] rows, String prefix) {
        return countRowsStartingWith(rows, prefix) > 0;
    }

    private static String[] rowStartingWith(String[] rows, String prefix) {
        for (String row : rows) {
            if (row.startsWith(prefix)) {
                return row.split(",", -1);
            }
        }
        throw new AssertionError("Missing row starting with " + prefix);
    }

    private static int countRowsStartingWith(String[] rows, String prefix) {
        int count = 0;
        for (String row : rows) {
            if (row.startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }
}
