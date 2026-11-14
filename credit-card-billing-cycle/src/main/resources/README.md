# PS-007 – Credit Card Billing Cycle, Interest and EMI Conversion Engine — Sample Data Pack

Prepared by 10 SECONDS for Team T056.

## Files

| File | What it holds | Row count |
|---|---|---|
| `cards.csv` | 8 cards with tier, limits, billing day and APR (all start 2026-02-01 at zero balance, previous statement paid in full) | 8 data rows |
| `prior_purchases.csv` | Purchases from before the period that still matter (points lots, a late refund) | 5 data rows |
| `points_opening.csv` | Reward-point lots held on 2026-02-01, by accrual month | 10 data rows |
| `holidays.csv` | Bank holidays 2026 (due-date roll) | 15 data rows |
| `commands.txt` | ~180 transaction events (purchases, cash, refunds, fees, payments) over 9 months, 12 EMI/redemption requests, statement and expiry runs, points queries | 269 command lines (excluding comments) |

Row numbers quoted below are 1-based data-row numbers (the header is row 0). Line numbers for `commands.txt` are physical line numbers including comment lines.

## Column reference

Conventions: dates `YYYY-MM-DD`; timestamps `YYYY-MM-DD HH:MM:SS` (24h, IST, no timezone suffix); money in Rs as plain decimals with 2 places (no commas, no symbol); percentages as plain numbers (12.5 means 12.5%); booleans `true`/`false`; IDs uppercase with a fixed prefix. An empty cell means "not applicable" and only occurs in columns whose notes say so.

### `cards.csv`

| Column | Type | Units / format | Allowed values | Notes |
|---|---|---|---|---|
| card_id | string | CARD-nnn | — | primary key |
| holder_name | string | — | — | — |
| holder_mobile | string | 10 digits | — | — |
| tier | enum | — | CLASSIC, GOLD, PLATINUM | PLATINUM +50% points |
| credit_limit | decimal | Rs | — | — |
| cash_advance_limit | decimal | Rs | — | = 40% of credit_limit |
| billing_day | int | day of month | 1-31 | if the month is shorter, the last day of the month |
| apr | decimal | % p.a. | — | daily rate = apr/365 |
| registered_date | date | — | — | — |

### `prior_purchases.csv`

| Column | Type | Units / format | Allowed values | Notes |
|---|---|---|---|---|
| txn_id | string | PRIOR-nnnn | — | may be referenced by REFUND |
| card_id | string | — | cards.csv | — |
| txn_date | date | — | — | before 2026-02-01 |
| amount | decimal | Rs | — | — |
| merchant | string | — | — | — |
| category | enum | — | GROCERY, DINING, TRAVEL, FUEL, SHOPPING, ELECTRONICS, UTILITIES, ENTERTAINMENT, HEALTH | — |
| points_awarded | int | points | — | already in points_opening.csv |

### `points_opening.csv`

| Column | Type | Units / format | Allowed values | Notes |
|---|---|---|---|---|
| lot_id | string | LOT-nnn | — | — |
| card_id | string | — | cards.csv | — |
| accrual_month | string | YYYY-MM | — | expires 24 months after the end of this month |
| points | int | points | — | unredeemed points in the lot |
| source_txn_id | string | — | prior_purchases.csv | empty = several purchases |

### `holidays.csv`

| Column | Type | Units / format | Allowed values | Notes |
|---|---|---|---|---|
| date | date | — | — | — |
| holiday_name | string | — | — | — |

## Command file format

`commands.txt` holds one command per line in the form `COMMAND_NAME|arg1|arg2|...`. Commands must be processed strictly in file order. Lines starting with `#` are comments and blank lines are ignored. Every command used in this pack:

| Command | Arguments (in order) | Meaning |
|---|---|---|
| `PURCHASE` | `txn_id|timestamp|card_id|amount|merchant|category` | Card purchase. category one of GROCERY, DINING, TRAVEL, FUEL, SHOPPING, ELECTRONICS, UTILITIES, ENTERTAINMENT, HEALTH |
| `CASH` | `txn_id|timestamp|card_id|amount|location` | Cash withdrawal (cash advance) |
| `REFUND` | `txn_id|timestamp|card_id|amount|original_txn_id` | Merchant refund of an earlier purchase (TXN-… or PRIOR-…) |
| `FEE` | `txn_id|timestamp|card_id|amount|fee_code` | Issuer fee (fee_code ANNUAL_FEE); GST is posted by your engine |
| `PAYMENT` | `txn_id|timestamp|card_id|amount|mode` | Payment. amount is a decimal, or STATEMENT_TOTAL / MIN_DUE = the total / minimum due of the latest generated statement; mode UPI, NEFT, NETBANKING, CHEQUE, AUTO_DEBIT |
| `EMI_CONVERT` | `request_id|timestamp|card_id|purchase_txn_id|tenure_months` | Convert a purchase to EMI (3/6/9/12) |
| `EMI_FORECLOSE` | `request_id|timestamp|card_id|purchase_txn_id` | Foreclose the EMI created from that purchase |
| `REDEEM` | `request_id|timestamp|card_id|points` | Redeem points as a statement credit |
| `GENERATE_STATEMENTS` | `date` | End of day: generate statements for every card whose billing date is this date |
| `EXPIRE_POINTS` | `date` | Month-end run: expire every lot whose expiry date is on or before this date |
| `PRINT_POINTS` | `card_id|date` | Print the points balance as at the end of that date (as processed so far) |

Time-driven postings (late fee and its GST on the day after a missed due date, over-limit fee, daily interest accrual) are made by your engine as its clock passes them; the clock is the timestamp of the command being processed. txn_id values are unique except for one planted duplicate.

## Planted scenarios

Every edge case in the brief, mapped to the exact record where it occurs, followed by extra traps.

- **EC-01 Payment received on due date at 23:59 vs 00:01 next day**
  - CARD-008: statement 2026-03-12 is due 2026-04-01; STATEMENT_TOTAL paid 2026-04-01 23:59:00 = on time (interest-free kept, no late fee) — `commands.txt line 53 (TXN-00035)`
  - CARD-008: statement 2026-04-12 is due 2026-05-02 (Saturday — only Sundays/holidays roll); paid 2026-05-03 00:01:00 = late: late fee tier applies and the next cycle loses the interest-free period — `commands.txt line 86 (TXN-00057)`
- **EC-02 Refund posted after points already expired for that month**
  - CARD-008: PRIOR-0001 (2024-02-18, Rs 25,000 TRAVEL) earned 1,500 points in lot 2024-02, which expires at the 2026-02-28 run (24 months from accrual-month end); a Rs 5,000 refund of that purchase is posted 2026-03-06 — the points to claw back have already expired — `prior_purchases.csv PRIOR-0001 (row 1); points_opening.csv LOT-009 (row 9); commands.txt line 28 (expiry run), line 31 (TXN-00020)`
- **EC-03 Statement balance exactly Rs 1,000 and Rs 1,001 for late fee tiers**
  - CARD-003: three purchases totalling exactly Rs 1,000.00 make the 2026-02-15 statement balance Rs 1,000.00; nothing paid by 2026-03-07 -> late fee tier 'balance <= Rs 1,000' = nil — `commands.txt lines 3, 6, 12; late payment line 33`
  - CARD-004: three purchases totalling exactly Rs 1,001.00 -> 2026-02-15 statement balance Rs 1,001.00; unpaid by 2026-03-07 -> late fee Rs 500 + 18% GST — `commands.txt lines 4, 7, 13; late payment line 34`
- **EC-04 EMI conversion requested on day 30 exactly after purchase**
  - CARD-005: purchase TXN-00060 of Rs 64,999.00 on 2026-05-08; EMI_CONVERT (6 months) on 2026-06-07 = day 30 exactly -> within window (card pays in full, so the purchase was never interest-bearing) — `commands.txt line 90, line 116`
- **EC-05 Partial payment of previous statement making all new purchases interest-bearing from their own dates**
  - CARD-006: statement 2026-03-25 carries Rs 24,350.00 of purchases; only Rs 10,000.00 is paid before the due date 2026-04-15 -> previous statement not paid in full, so the new purchases after it are interest-bearing from their own transaction dates — `commands.txt lines 29, 32, 39 (statement purchases), line 63 (partial payment), lines 54, 61 (new purchases)`
- **EC-06 Cash withdrawal that pushes outstanding over limit by Rs 1**
  - CARD-001 (limit Rs 50,000, cash limit Rs 20,000): purchases Rs 29,411.00, then CASH Rs 20,000.00 on 2026-02-24 with its fee Rs 500 (2.5% = 500, min 500) + GST Rs 90 -> outstanding Rs 50,001.00 = limit + Rs 1 -> over-limit fee 2.5% of excess, min Rs 500 (+GST), once in the cycle — `commands.txt lines 8, 14, 18, line 22 (TXN-00015)`
- **EC-07 February billing day 30 on a card registered with billing day 30**
  - CARD-002 is registered with billing_day 30; February 2026 has no 30th, so its statement is generated on 2026-02-28 (due 2026-03-20) — `cards.csv CARD-002 (row 2); commands.txt line 27`
- **EC-08 Points balance 499 at expiry run**
  - CARD-007: only FUEL purchases (no points) until April, opening lots 200 (2024-04) + 299 (2025-03) = balance 499 at the 2026-04-30 expiry run; REDEEM of 499 that morning is below the 500 minimum (reject); the run expires the 200-point lot leaving 299 — `points_opening.csv LOT-007 (row 7), LOT-008 (row 8); commands.txt line 76, line 82, queries line 80, line 83`
- **Extra traps (not in the brief, but realistic):**
  - CARD-005: EMI_FORECLOSE 36 days after conversion must be REJECTED (first 3 months); the second request after month 3 is accepted at 3% of outstanding principal + GST — `commands.txt line 154, line 224`
  - CARD-006: due date of statement 2026-03-25 = +20 days = 2026-04-14 (Ambedkar Jayanti, holiday) -> rolls to 2026-04-15 — `holidays.csv 2026-04-14; commands.txt line 63`
  - CARD-008: REDEEM of exactly 500 points (the minimum) must be accepted = Rs 125.00 statement credit — `commands.txt line 174`
  - CARD-005 (PLATINUM): one TRAVEL purchase of Rs 90,000 = 900 x 4 x 1.5 = 5,400 points -> capped at 5,000 for the month — `commands.txt line 140 (TXN-00092)`
  - EMI_CONVERT on day 31 after purchase must be REJECTED (window is 30 days) — `commands.txt line 114, line 142`
  - EMI boundary: Rs 2,499.00 purchase -> EMI_CONVERT REJECTED (< Rs 2,500); Rs 2,500.00 purchase -> accepted — `commands.txt line 179/line 182; line 181/line 186`
  - CARD-007: CASH Rs 12,500 exceeds the 40% cash-advance cap (Rs 12,000) -> REJECTED — `commands.txt line 129`
  - CARD-004: the same PURCHASE (same txn_id, amount, merchant) is presented twice one second apart — post once, reject the second as duplicate — `commands.txt line 163, line 164`

## What is NOT in this pack

Expected outputs are not provided for this set. Validate your engine with your own test suite against the rules in your brief (BR-01 … BR-nn). A separate hidden input set with the same schema will be used at final evaluation; outputs are compared field-for-field, so follow the column formats above exactly in your own output.

## Engine rules

The engine applies the following billing rules in command order:

1. **BR-01 — Billing calendar:** Generate statements on each card's billing day, clamped to the last day of shorter months. The due date is 20 days after the statement date; roll Sundays and listed holidays forward to the next non-Sunday, non-holiday day. Saturdays are working days.
2. **BR-02 — Purchase interest:** Purchases remain interest-free when the previous statement total due is paid by its due date. Otherwise, accrue interest at card APR / 365 on each unpaid purchase from its transaction date. Cash advances accrue interest from their withdrawal date regardless of payment history.
3. **BR-03 — Cash advances:** The outstanding cash-advance cap is 40% of the credit limit. Charge 2.5% of each withdrawal, with a Rs 500 minimum.
4. **BR-04 — Minimum due:** The minimum is 5% of the statement balance plus all fees, interest and EMI instalments due, with a Rs 200 minimum. If the total balance is below Rs 200, the full balance is due. GST is already included in the statement balance and is not added again as a separate full-amount component.
5. **BR-05 — Late fees:** If the minimum due is unpaid by the due date, charge nil for balances up to Rs 1,000; Rs 500 for Rs 1,001–10,000; Rs 950 for Rs 10,001–25,000; and Rs 1,300 above Rs 25,000.
6. **BR-06 — Over-limit fees:** If outstanding exceeds the credit limit on any day, charge 2.5% of the excess, with a Rs 500 minimum, once per billing cycle. Unpaid EMI principal counts toward the limit.
7. **BR-07 — Payment allocation:** Apply payments to fees, interest, EMI instalments, cash advances, then purchases; within each group, pay the oldest balance first.
8. **BR-08 — Reward points:** Earn 2 points per Rs 100 on purchases, or 4 points per Rs 100 for DINING and TRAVEL. FUEL, cash and fees earn none. PLATINUM earns 50% extra; cap earnings at 5,000 points per card per month. Refunds claw back points from the refunded purchase while unexpired points remain.
9. **BR-09 — Points expiry and redemption:** Lots expire 24 months after their accrual month end. Redeem oldest points first, with a 500-point minimum and a Rs 0.25 statement credit per point.
10. **BR-10 — EMI conversion:** Convert purchases of at least Rs 2,500 within 30 days, while they are not interest-bearing, into 3/6/9/12-month EMIs at 14% reducing annual interest. Charge a 1% processing fee, minimum Rs 99.
11. **BR-11 — EMI foreclosure:** Foreclosure is unavailable in the first three months. Thereafter charge 3% of outstanding principal.
12. **BR-12 — GST:** Post 18% GST as a separate line on every fee, interest and processing charge on the same day.
13. **BR-13 — Duplicate IDs:** Reject a repeated transaction or request ID.

The executable reads `cards.csv`, `holidays.csv`, `points_opening.csv` and `prior_purchases.csv` from the resources directory and processes the specified command file. From the Maven project directory, run `mvn test` to verify the dataset, then `java -cp target/classes com.billing.App`. By default, the five CSV outputs are written to `output/`. Optional arguments are `[resources-directory] [commands-file] [output-directory]`.

## Output format expected at evaluation

Produce these CSV files (header row, same conventions as above, rows sorted as stated). Where the brief is silent on a format detail the choice below is binding for evaluation.

### `decisions.csv`

One row per command line that carries a txn_id/request_id, in file order.

Columns, in order: `line_no`, `command`, `ref_id`, `card_id`, `decision`, `rule_ids`, `amount_posted`

### `statements.csv`

One row per generated statement, ordered by statement_date then card_id.

Columns, in order: `statement_id`, `card_id`, `statement_date`, `period_from`, `period_to`, `opening_balance`, `purchases`, `cash_advances`, `credits`, `payments`, `interest`, `fees`, `gst`, `emi_instalments`, `total_due`, `minimum_due`, `due_date`, `points_opening`, `points_earned`, `points_redeemed`, `points_expired`, `points_clawed_back`, `points_closing`

### `statement_lines.csv`

Every posting on each statement in posting order.

Columns, in order: `statement_id`, `line_no`, `posting_date`, `ref_id`, `line_type`, `description`, `amount`

### `emi_schedule.csv`

One row per instalment of each accepted EMI.

Columns, in order: `emi_id`, `card_id`, `source_txn_id`, `instalment_no`, `statement_date`, `principal`, `interest`, `gst`, `closing_principal`

### `points_balance.csv`

One row per PRINT_POINTS command.

Columns, in order: `card_id`, `as_of_date`, `points_balance`

Binding choices: `statement_id` = STM-<card_id>-<YYYYMM> of the statement date; `decision` ACCEPTED or REJECTED (DUPLICATE counts as REJECTED with its own rule id); `rule_ids` are your BR-nn ids separated by `;`; `line_type` one of PURCHASE, CASH, CASH_FEE, REFUND, PAYMENT, INTEREST, LATE_FEE, OVERLIMIT_FEE, ANNUAL_FEE, PROCESSING_FEE, FORECLOSURE_FEE, GST, EMI_PRINCIPAL, EMI_INTEREST, EMI_CONVERSION_CREDIT, POINTS_CREDIT; debits positive, credits negative; `emi_id` = EMI-<purchase_txn_id>.
