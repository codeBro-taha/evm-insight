# Independent reference calculations (NFR-02)

Hand-derived, not produced by the engine.

## REF-1 (status date 2026-01-06)
| WP | Budget | Start | Finish | Completion | AC |
|----|-------:|-------|--------|-----------:|---:|
| A | 1000 | 2026-01-01 | 2026-01-11 (10 days) | 0.50 | 600 |
| B | 2000 | 2026-01-06 | 2026-02-05 (30 days) | 0.25 | 700 |

- PV: A = 1000 x 5/10 = 500; B = 0 (on start) -> **500**
- BAC = 3000; EV = 500 + 500 = **1000**; AC = **1300**
- CV = 1000 - 1300 = **-300**; SV = 1000 - 500 = **500**
- CPI = 1000/1300 = **0.7692307692**; SPI = 1000/500 = **2**
- Completion = 1000/3000 x 100 = **33.3333333333 %**; Budget consumed = 1300/3000 x 100 = **43.3333333333 %**
- CPI method: EAC = 3000/0.76923... = **3900**; ETC = 2600; VAC = -900
- TCPI = (3000-1000)/(3000-1300) = 2000/1700 = **1.1764705882**
- Composite: CPI x SPI = 1.538461...; EAC = 1300 + 2000/1.538461... = **2600**; ETC = 1300; VAC = 400

## Rounding policy
Sums are exact. Divisions use scale 10, HALF_EVEN. EAC is computed as BAC x AC / EV (mathematically equal to BAC / CPI)
to avoid compounding the rounded CPI. Display rounding (currency minor units) is applied only at presentation.
