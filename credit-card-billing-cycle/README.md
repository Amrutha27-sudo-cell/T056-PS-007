# T056-PS-007: Credit Card Billing Cycle Engine

**Team:** T056  
**Members:** Amrutha R and Ananya Shankar  
**Domain:** BFSI — credit-card billing, interest, rewards, and EMI conversion

## Overview

This Java command-file application simulates card events in input order and writes
deterministic CSV reports for decisions, statements, statement lines, EMI schedules,
and reward-point balances. It follows the capstone brief's batch-processing model;
there is no interactive menu or external database.

## Requirements

- Java 17 or newer
- Maven

## Build, test, and run

From this directory:

```text
mvn test
java -cp target/classes com.billing.App
```

By default, the application reads `src/main/resources/commands.txt` and the
associated CSV files, then writes five CSV files to `output/`.

The command-line arguments can override the resources directory, command file,
and output directory, in that order:

```text
java -cp target/classes com.billing.App <resources-directory> <commands-file> <output-directory>
```

For example:

```text
java -cp target/classes com.billing.App src/main/resources src/main/resources/commands.txt output
```

## Input data and commands

The bundled input files are in [`src/main/resources/`](src/main/resources/):

- `cards.csv` — card, tier, limits, billing day, APR, and registration date
- `holidays.csv` — holidays used to adjust due dates
- `points_opening.csv` — unredeemed opening-point lots
- `prior_purchases.csv` — earlier purchases that may be refunded
- `commands.txt` — ordered event and reporting commands

Commands use pipe-separated fields. Blank lines and lines beginning with `#` are
ignored; every other command is processed in file order. The input schemas,
command arguments, business rules, edge cases, and exact output columns are
specified in [`src/main/resources/README.md`](src/main/resources/README.md).

## Output files

The run creates:

- `decisions.csv` — accepted/rejected transaction and request decisions
- `statements.csv` — statement totals, due dates, minimums, and points movement
- `statement_lines.csv` — itemized ledger postings
- `emi_schedule.csv` — installment principal, interest, GST, and remaining principal
- `points_balance.csv` — one snapshot for each `PRINT_POINTS` command

## Project documentation and validation

- [Design document](DESIGN.md) — architecture, domain model, BR-01–BR-12,
  duplicate-ID handling, and EC-01–EC-08
- [Decision log](DECISION_LOG.md) — implementation choices and their rationale
- [`AppTest.java`](src/test/java/com/billing/AppTest.java) — bundled-data
  integration coverage, boundary scenarios, opening point lots, minimum due,
  output formats, and CSV writing

The test suite replays the bundled command file and also checks opening-point
balances for all eight cards, covering all ten bundled lots. Run `mvn test` to
execute the suite.

## Demo video

**5-minute demo video:** _Add the team's shareable video link here before submission._
