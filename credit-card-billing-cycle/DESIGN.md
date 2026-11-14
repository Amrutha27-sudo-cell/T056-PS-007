# Credit Card Billing Cycle Engine — Design Document

**Project:** PS-007, Team T056  
**Team members:** Amrutha R and Ananya Shankar  
**Document scope:** Dataset-driven billing, interest, rewards, and EMI command processor

**Runtime:** Java 17+

## 1. Purpose and scope

The application replays an ordered card-event stream, applies billing and rewards rules as the command clock advances, and emits evaluation-ready CSVs. It is an in-memory batch engine: the input CSVs and command stream are the source of truth for a run; there is no database or external service.

The principal entry point is [`App.java`](src/main/java/com/billing/App.java). The implementation and rule orchestration are in [`SimulationEngine.java`](src/main/java/com/billing/SimulationEngine.java). Dataset schemas, command syntax, and the complete list of bundled cases are in [`src/main/resources/README.md`](src/main/resources/README.md).

## 2. Architecture

### 2.1 Runtime flow

1. `App` resolves the resource directory, command file, and output directory. With no arguments, it reads `src/main/resources/commands.txt` and writes the result files to `output/`.
2. `SimulationEngine` loads `cards.csv`, `holidays.csv`, `prior_purchases.csv`, and `points_opening.csv`. It creates an in-memory card state for each card, associates prior purchases with their opening points lots, and records the holiday calendar.
3. `execute` reads the command file in physical line order. Blank lines and lines whose first non-whitespace character is `#` are ignored.
4. Before a command is applied, the engine advances its date clock to that command’s date. Time-driven events are processed during this advance, then the command is validated and posted.
5. Statement, expiry, and points-query commands run at their position in the same stream. A statement run processes eligible cards in card-ID order.
6. The engine serializes decisions, statements, statement lines, EMI schedules, and points queries to UTF-8 CSV files.

The engine preserves posting sequence numbers so statement lines can be emitted in posting order. Statements are ordered by statement date and then card ID; decisions stay in source-file order.

### 2.2 Inputs and outputs

Inputs:

- [`cards.csv`](src/main/resources/cards.csv): card identity, tier, limits, billing day, APR, and registration date.
- [`holidays.csv`](src/main/resources/holidays.csv): dates that move a due date forward.
- [`prior_purchases.csv`](src/main/resources/prior_purchases.csv): earlier purchases that can be refunded and whose points were already awarded.
- [`points_opening.csv`](src/main/resources/points_opening.csv): unredeemed opening points, grouped as expiry lots.
- [`commands.txt`](src/main/resources/commands.txt): ordered financial events and run/query commands.

Outputs:

- `decisions.csv`: one result for every command with a transaction or request ID.
- `statements.csv`: one summary for each statement generated.
- `statement_lines.csv`: itemized postings in statement-period order.
- `emi_schedule.csv`: an amortization row for each installment of every accepted EMI.
- `points_balance.csv`: one points snapshot per `PRINT_POINTS` command.

The CSV headers and field order are defined in the output-format section of the README.

### 2.3 Domain model

The runtime simulator uses private state types nested in `SimulationEngine`:

| Type | Responsibility |
|---|---|
| `Card` | Card configuration and mutable account state: postings, open charges, purchases, cash advances, point lots, EMIs, statements, credit, grace-period state, and cycle-level over-limit state. |
| `Posting` | Immutable ledger entry: date, reference ID, line type, description, signed amount, and sequence. Debits are positive; credits/payments are negative. |
| `Charge` | Unpaid portion of a debit posting. Its priority determines payment allocation; balance snapshots support retrospective daily-interest calculation. |
| `PurchaseRecord` / `CashRecord` | Purchase and cash-advance source data, including outstanding charge and interest state. Purchase records also retain point and refund information. |
| `PriorPurchase` | Earlier purchase data used to validate refunds and calculate proportional point clawbacks. |
| `PointLot` | Point amount, source, accrual month, expiry date, and remaining/expired state. Redemption and expiry operate on lots. |
| `Emi` / `EmiInstallment` | EMI principal, tenure, conversion date, reducing-rate amortization schedule, future principal, and foreclosure state. |
| `Statement` | Statement-period bounds, due date, included postings, financial totals, minimum due, and point movement totals. |
| `Decision`, `PointBalance`, `EmiScheduleRow` | Output records for command decisions, points snapshots, and amortization output. |

`SimulationEngine` also owns the reference indexes, command-ID set, holidays, clock, and output collections. `BalancePoint` is an internal history entry used to determine the unpaid principal on each day when a grace period is lost.

The package also retains the earlier standalone [`Transaction.java`](src/main/java/com/billing/Transaction.java), [`BillingCycle.java`](src/main/java/com/billing/BillingCycle.java), and [`CreditCardAccount.java`](src/main/java/com/billing/CreditCardAccount.java) object model. These types support a simple account/billing-cycle API but are not used by the dataset-driven `App` command flow; the active event-sourced model is the private model described above.

### 2.4 Financial and time representation

- Dates use `LocalDate`; command timestamps are parsed as local date-times in the dataset’s stated IST convention, with no timezone suffix.
- Currency uses `BigDecimal`, rounded to two decimal places with half-up rounding when posted.
- The simulator processes days between command dates, applying due-date events and daily interest as its clock advances.
- Daily purchase and cash interest use the card APR divided by 365. EMI interest uses a 14% reducing annual rate divided into monthly periods.
- GST is a separate positive posting. Refunds, statement credits, and payments appear as negative ledger postings.

## 3. Business rules

The following are the twelve business rules supplied for this project. They correspond to the numbered rules documented in the README.

### BR-01 — Billing calendar and due date

- Generate a statement on a card’s billing day.
- If that day does not exist in a month, use that month’s last day.
- The base due date is statement date plus 20 days.
- Move the due date forward over Sundays and listed holidays; Saturday is a working day.
- A `GENERATE_STATEMENTS` command runs at end of day for cards whose effective billing date matches the command date.

### BR-02 — Interest-free period and purchase interest

- A purchase remains interest-free when the preceding statement’s total due is paid by that statement’s due date.
- If that total is not paid in full, accrue interest at APR/365 on each unpaid purchase from its transaction date.
- Cash advances accrue interest starting on the withdrawal date regardless of the purchase grace-period status.
- Interest and its GST are posted as the engine clock advances; missed-grace interest is calculated from the affected purchase dates when the due-date result becomes known.

### BR-03 — Cash advances

- The cash-advance limit is 40% of the card’s credit limit; the engine checks outstanding cash-advance principal against this cap.
- Each withdrawal incurs a fee equal to the greater of 2.5% of the withdrawal or Rs 500.
- Cash principal and its fee/GST are distinct ledger lines. Cash does not earn reward points.

### BR-04 — Minimum due

- Minimum due is 5% of the statement balance plus fees, interest, and EMI installments due.
- The minimum is Rs 200.
- If the statement balance is below Rs 200, the full balance is due.
- GST is included in the statement balance used for the 5% calculation; it is not added again as a separate full-amount component.

### BR-05 — Late fees

If the minimum due has not been received by the due date, the statement-balance tier determines the fee:

| Statement balance | Late fee |
|---|---:|
| Up to and including Rs 1,000 | Nil |
| Rs 1,001–10,000 | Rs 500 |
| Rs 10,001–25,000 | Rs 950 |
| Above Rs 25,000 | Rs 1,300 |

The late fee and its 18% GST are posted separately on the day after the missed due date.

### BR-06 — Over-limit fees

- If account outstanding exceeds the credit limit on any day, charge the greater of 2.5% of the excess or Rs 500.
- Apply this fee at most once in a billing cycle; add GST as a separate line.
- Outstanding EMI principal counts toward the credit limit.

### BR-07 — Payment allocation

Allocate each payment in this order:

1. Fees
2. Interest
3. EMI installments
4. Cash advances
5. Purchases

Within each group, reduce the oldest outstanding charge first. `STATEMENT_TOTAL` and `MIN_DUE` resolve to the latest generated statement’s corresponding amount; payment mode must be one of UPI, NEFT, NETBANKING, CHEQUE, or AUTO_DEBIT.

### BR-08 — Reward points and refunds

- Earn 2 points per Rs 100 on eligible purchases, or 4 points per Rs 100 for DINING and TRAVEL.
- FUEL purchases, cash advances, and fees earn no points.
- PLATINUM cards receive 50% additional points.
- Cap purchase earnings at 5,000 points per card per calendar month.
- A refund is linked to its original purchase. The engine claws back the corresponding points from available, unexpired points for that source.

### BR-09 — Points expiry and redemption

- A lot expires 24 months after its accrual month end, when an `EXPIRE_POINTS` run is processed on or after the expiry date.
- Redeem oldest unexpired points first.
- The minimum redemption is 500 points; each point produces a Rs 0.25 statement credit.
- `PRINT_POINTS` reports the balance as of its position in the command stream, so a query before an expiry run on the same day can differ from one after it.

### BR-10 — EMI conversion

- The purchase must be at least Rs 2,500 and the request must be made within 30 days of the purchase.
- The purchase must not yet be interest-bearing.
- Supported tenures are 3, 6, 9, and 12 months at 14% reducing annual interest.
- Charge a processing fee equal to 1% of the converted amount, with a Rs 99 minimum. GST is a separate line.
- The conversion removes the converted amount from the revolving purchase balance; installments begin on the next applicable statement date.

### BR-11 — EMI foreclosure

- Foreclosure is rejected during the first three months after conversion.
- After that period, charge 3% of outstanding principal plus 18% GST.
- Accepted foreclosure stops future installment postings; installments already posted remain part of the account ledger.

### BR-12 — GST

Post 18% GST separately on each fee and interest charge on the same posting date. This includes cash, late, over-limit, annual, processing, and foreclosure fees and daily/EMI interest.

### Supplemental command rule — duplicate IDs

Repeated transaction/request IDs are rejected as `DUPLICATE` with `BR-13`. This is an output/command-integrity rule in addition to the twelve financial rules above.

## 4. Explicit edge cases

These are the eight named cases in the README. Each includes the input trigger and the expected engine behavior.

### EC-01 — Payment at 23:59 on the due date vs. 00:01 the following day

- **On time:** CARD-008’s 2026-03-12 statement is due 2026-04-01. `TXN-00035` pays `STATEMENT_TOTAL` at 23:59 on 2026-04-01; it preserves the interest-free result and avoids a late fee.
- **Late:** CARD-008’s 2026-04-12 statement is due 2026-05-02, a Saturday that does not roll. `TXN-00057` arrives at 00:01 on 2026-05-03; assess the applicable late fee and treat the grace period as lost.
- **References:** `commands.txt` lines 53 and 86.

### EC-02 — Refund after the source points have expired

CARD-008’s `PRIOR-0001` earned 1,500 points in the February 2024 lot. The 2026-02-28 expiry run expires that lot before `TXN-00020` refunds Rs 5,000 on 2026-03-06. Accept the refund, but do not claw back points from the expired lot.

### EC-03 — Late-fee balance boundary at Rs 1,000 / Rs 1,001

- CARD-003’s 2026-02-15 statement is exactly Rs 1,000.00. With the minimum unpaid by 2026-03-07, its late fee is nil.
- CARD-004’s corresponding statement is Rs 1,001.00. With the minimum unpaid by the same date, charge Rs 500 plus Rs 90 GST.

### EC-04 — EMI conversion on day 30

CARD-005 purchase `TXN-00060` is Rs 64,999 on 2026-05-08. Request `REQ-002` on 2026-06-07 is exactly 30 days later and is accepted because it is within the conversion window and the purchase has not become interest-bearing.

### EC-05 — Partial payment loses the grace period

CARD-006’s 2026-03-25 statement contains Rs 24,350 in purchases. Only Rs 10,000 is paid before the adjusted 2026-04-15 due date. The statement is not paid in full, so unpaid purchases accrue daily interest from their own transaction dates, including new purchases in that cycle.

### EC-06 — Cash withdrawal produces Rs 1 of excess

CARD-001 has a Rs 50,000 credit limit. Rs 29,411 of purchases plus the Rs 20,000 cash withdrawal, Rs 500 cash fee, and Rs 90 GST produces Rs 50,001 outstanding. Accept the cash withdrawal within its cash limit and charge one over-limit fee of Rs 500 plus Rs 90 GST for that billing cycle.

### EC-07 — Billing day 30 in February

CARD-002 has billing day 30. Generate its February statement on 2026-02-28, the last day of the month. Its base due date is 20 days later. Since 2026-03-20 is listed as a holiday, BR-01 rolls the due date to 2026-03-21; this follows the general holiday rule even though the EC-07 note names the unadjusted date.

### EC-08 — 499 points at the expiry run

CARD-007 has 200 points in the 2024-04 lot and 299 points in the 2025-03 lot. At the 2026-04-30 pre-expiry query, the balance is 499. A redemption request for 499 is rejected because it is below the 500 minimum. The expiry run then removes the 200-point lot, leaving 299 for the subsequent query.

## 5. Validation strategy

[`AppTest.java`](src/test/java/com/billing/AppTest.java) runs the full bundled command stream through `SimulationEngine` and verifies:

- output names, headers, and record counts;
- opening reward-point balances for all eight cards, covering all ten bundled lots;
- key decisions and rule IDs for payments, cash limits, EMI eligibility/foreclosure, redemptions, and duplicate IDs;
- shortened-month billing, holiday/Saturday due-date handling, late-fee boundaries, expiry balances, and monthly points cap;
- cash, over-limit, late-fee, GST, and retrospective interest postings;
- the minimum-due formula and fee-first payment allocation when a due-date payment is partial;
- the 14% EMI schedule, including its installment dates and total schedule rows; and
- that all five outputs can be written and read back unchanged.

Run the suite from the Maven project directory:

```text
mvn test
```

Run the application against the bundled dataset:

```text
java -cp target/classes com.billing.App
```

The executable accepts optional arguments in this order: resources directory, command-file path, output directory.
