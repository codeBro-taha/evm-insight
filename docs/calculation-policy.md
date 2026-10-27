# EVM calculation policy (SRS section 6, NFR-02)

Calculation version: `evm-1.0.0` (stored with every saved report so it can be reproduced).

| Topic | Rule |
|---|---|
| Authority | Only the backend class `EvmCalculator` computes metrics. The frontend only formats. |
| Arithmetic | `BigDecimal`; divisions use `DECIMAL128` (34 significant digits, HALF_EVEN). Sums are exact. |
| Planned value | Linear over elapsed calendar days. Before start: 0. On/after finish: full budget. Same-day start/finish: 0 before, full on/after. |
| Earned value | budget x completion% / 100 per leaf work package, summed. Completion must be 0..100 inclusive. |
| Missing progress | If any eligible work package has no recorded progress, EV is **undefined** and says which packages. |
| Missing actual cost | If any eligible work package has no actual cost, AC is **undefined**. A real zero must be entered as 0. |
| Undefined propagation | Any metric that depends on an undefined metric is undefined (`DEPENDS_ON_UNDEFINED`). |
| Zero denominators | A ratio is defined only when its denominator is strictly positive: CPI (AC), SPI (PV), percentages (BAC), EAC (CPI), TCPI (BAC - AC). |
| Composite EAC | Computed only when explicitly selected; otherwise `NOT_SELECTED`. |
| Display rounding | Money: ISO 4217 minor units, HALF_UP. CPI/SPI/TCPI: 2 decimals. Percentages: 1 decimal. Rounding is for display/export only. |
| Schedule variance | SV is a budgeted-value variance (EV - PV) in currency, not a number of days. |
| Limitation | The linear PV profile is a simplifying approximation, not a resource-loaded schedule. The UI must say so. |
