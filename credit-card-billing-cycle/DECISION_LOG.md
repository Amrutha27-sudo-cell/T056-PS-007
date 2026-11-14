# Decision Log

**Project:** PS-007, Team T056  
**Members:** Amrutha R and Ananya Shankar

This log records implementation choices made where the capstone brief or sample
pack leaves room for interpretation.

| ID | Decision | Rationale and effect |
|---|---|---|
| DL-01 | Use Java 17 with Maven. | The assignment permits Java 17+ or Python 3.10+; Java is the chosen language and Maven runs the build and JUnit suite. |
| DL-02 | Keep the runtime command-file driven, without an interactive menu. | The capstone explicitly specifies a nightly batch engine with no UI. The ordered command file is the interface. |
| DL-03 | Process valid commands strictly in physical file order. | This preserves timestamp and same-day command semantics, including points queries before or after an expiry run. Blank lines and comment lines are ignored. |
| DL-04 | Use `LocalDate` for calendar dates, `LocalDateTime` for command timestamps, and `BigDecimal` for money. | Inputs are local IST values without a timezone suffix. Monetary postings are rounded to two decimal places using half-up rounding. |
| DL-05 | Roll statement due dates forward over Sundays and listed holidays; Saturdays remain working days. | This follows BR-01 and the sample-pack rule. CARD-002's February 2026 due date therefore rolls from the listed March 20 holiday to March 21. |
| DL-06 | Calculate minimum due as 5% of statement total plus fees, interest, and EMI installments due, subject to the Rs 200 floor and total-due cap. | GST is already part of statement total, so its 5% component is included there; GST is not added a second time as a separate 100% component. This follows the capstone's stated formula. |
| DL-07 | Retain opening and earned reward points as expiry lots. | Lot-level balances are necessary to redeem oldest points first, expire by accrual month, and claw back points only from unexpired lots tied to a refunded purchase. |
| DL-08 | Use the event-stream model inside `SimulationEngine` for the executable. | It owns mutable account state and output generation. The original public `Transaction`, `BillingCycle`, and `CreditCardAccount` classes remain as legacy domain APIs and are not the active command-processing model. |
| DL-09 | Emit the five CSV files and field order documented in the sample-pack README. | Stable output schemas make results reproducible and suitable for automated evaluation. Ledger debits are positive and credits are negative. |
| DL-10 | Reject repeated transaction/request IDs using BR-13. | The sample pack includes a deliberate duplicate and explicitly defines its expected rejection behavior in addition to BR-01–BR-12. |
| DL-11 | For EC-07, apply the general due-date holiday rule even though the edge-case note gives the unadjusted date. | CARD-002's base due date is March 20, 2026, which is listed as a holiday; the resulting due date is March 21. |
| DL-12 | Leave the demo-video URL as a placeholder until the team has recorded and shared the video. | The project files do not contain a valid team video link; no link is fabricated. |

