#!/usr/bin/env python3
"""
Independent reference implementation of the EVM formulas in SRS section 6 (NFR-02).

It is deliberately written separately from the Java engine: it uses exact rational arithmetic
(fractions.Fraction) instead of BigDecimal, and shares no code with it. It generates:

  backend/src/test/resources/reference/datasets.csv   input cases (hand-made edge cases + seeded random)
  backend/src/test/resources/reference/expected.csv   expected metric values / undefined states

The Java test ReferenceCrossCheckTest recomputes every case with the real engine and compares.
Run:  python3 docs/reference/evm_reference.py
"""
import csv
import random
from datetime import date, timedelta
from decimal import Decimal, getcontext
from fractions import Fraction
from pathlib import Path

getcontext().prec = 60
OUT = Path(__file__).resolve().parents[2] / "backend" / "src" / "test" / "resources" / "reference"
METRICS = ["BAC", "PV", "EV", "AC", "CV", "SV", "CPI", "SPI", "COMPLETION_PCT", "BUDGET_CONSUMED_PCT",
           "EAC_CPI", "ETC_CPI", "VAC_CPI", "TCPI_BAC", "EAC_COMPOSITE"]


def d(s):
    return date.fromisoformat(s)


def planned_value(budget, start, finish, status):
    if status < start:
        return Fraction(0)
    if status >= finish:
        return budget
    return budget * Fraction((status - start).days, (finish - start).days)


def compute(case):
    wps, status, composite = case["wps"], case["status"], case["composite"]
    r = dict.fromkeys(METRICS)  # None == undefined
    r["BAC"] = sum((Fraction(w["budget"]) for w in wps), Fraction(0))
    r["PV"] = sum((planned_value(Fraction(w["budget"]), w["start"], w["finish"], status) for w in wps), Fraction(0))
    if all(w["pct"] is not None for w in wps):
        r["EV"] = sum((Fraction(w["budget"]) * Fraction(w["pct"]) / 100 for w in wps), Fraction(0))
    if all(w["ac"] is not None for w in wps):
        r["AC"] = sum((Fraction(w["ac"]) for w in wps), Fraction(0))
    bac, pv, ev, ac = r["BAC"], r["PV"], r["EV"], r["AC"]

    def sub(a, b):
        return None if a is None or b is None else a - b

    def div(a, b, k=1):
        return None if a is None or b is None or b <= 0 else a * k / b

    r["CV"], r["SV"] = sub(ev, ac), sub(ev, pv)
    r["CPI"], r["SPI"] = div(ev, ac), div(ev, pv)
    r["COMPLETION_PCT"], r["BUDGET_CONSUMED_PCT"] = div(ev, bac, 100), div(ac, bac, 100)
    r["EAC_CPI"] = div(bac, r["CPI"])
    r["ETC_CPI"], r["VAC_CPI"] = sub(r["EAC_CPI"], ac), sub(bac, r["EAC_CPI"])
    r["TCPI_BAC"] = div(sub(bac, ev), sub(bac, ac))
    if composite and None not in (ev, ac, r["CPI"], r["SPI"]):
        prod = r["CPI"] * r["SPI"]
        if prod > 0:
            r["EAC_COMPOSITE"] = ac + (bac - ev) / prod
    return r


def fmt(fr):
    return format(Decimal(fr.numerator) / Decimal(fr.denominator), "f")


def hand_cases():
    def wp(i, b, s, f, p, a):
        return {"id": i, "budget": b, "start": d(s), "finish": d(f), "pct": p, "ac": a}

    return [
        {"id": "A_mixed", "status": d("2026-01-16"), "composite": True, "wps": [
            wp("WP1", "1000", "2026-01-01", "2026-01-11", "100", "1200"),
            wp("WP2", "2000", "2026-01-06", "2026-01-26", "25", "400"),
            wp("WP3", "500", "2026-02-01", "2026-02-11", "0", "0")]},
        {"id": "B_complete_on_budget", "status": d("2026-03-01"), "composite": True, "wps": [
            wp("WP1", "800", "2026-01-01", "2026-01-31", "100", "800"),
            wp("WP2", "1200", "2026-02-01", "2026-02-28", "100", "1200")]},
        {"id": "C_before_any_start", "status": d("2025-12-31"), "composite": True, "wps": [
            wp("WP1", "1000", "2026-01-01", "2026-02-01", "0", "0")]},
        {"id": "D_overspend", "status": d("2026-02-10"), "composite": True, "wps": [
            wp("WP1", "1000", "2026-01-01", "2026-02-01", "80", "1500"),
            wp("WP2", "500", "2026-01-15", "2026-03-01", "10", "300")]},
        {"id": "E_zero_bac", "status": d("2026-02-10"), "composite": True, "wps": [
            wp("WP1", "0", "2026-01-01", "2026-02-01", "50", "10")]},
        {"id": "F_same_day_start_finish", "status": d("2026-01-10"), "composite": False, "wps": [
            wp("WP1", "700", "2026-01-10", "2026-01-10", "100", "650")]},
        {"id": "G_missing_progress", "status": d("2026-01-20"), "composite": True, "wps": [
            wp("WP1", "1000", "2026-01-01", "2026-02-01", None, "400"),
            wp("WP2", "500", "2026-01-10", "2026-02-10", "50", "100")]},
        {"id": "H_missing_cost", "status": d("2026-01-20"), "composite": True, "wps": [
            wp("WP1", "1000", "2026-01-01", "2026-02-01", "40", None)]},
        {"id": "I_zero_progress_with_spend", "status": d("2026-01-20"), "composite": True, "wps": [
            wp("WP1", "1000", "2026-01-01", "2026-02-01", "0", "250")]},
        {"id": "J_day_before_finish", "status": d("2026-01-30"), "composite": True, "wps": [
            wp("WP1", "1000", "2026-01-01", "2026-01-31", "99.5", "990")]},
        {"id": "K_ac_equals_bac", "status": d("2026-02-01"), "composite": True, "wps": [
            wp("WP1", "1000", "2026-01-01", "2026-02-01", "90", "1000")]},
    ]


def random_cases(n=300, seed=20261027):
    rnd = random.Random(seed)
    cases = []
    for c in range(n):
        wps = []
        for i in range(rnd.randint(1, 12)):
            start = date(2026, 1, 1) + timedelta(days=rnd.randint(0, 300))
            finish = start + timedelta(days=rnd.choice([0, 1, 7, 30, 90, rnd.randint(0, 365)]))
            budget = Fraction(rnd.randint(0, 5_000_000), 100) if rnd.random() > 0.03 else Fraction(0)
            pct = None if rnd.random() < 0.04 else Fraction(rnd.randint(0, 10_000), 100)
            ac = None if rnd.random() < 0.04 else (budget * Fraction(rnd.randint(0, 150), 100)).limit_denominator(100)
            wps.append({"id": f"WP{i + 1}", "budget": f"{float(budget):.2f}", "start": start, "finish": finish,
                        "pct": None if pct is None else f"{float(pct):.2f}",
                        "ac": None if ac is None else f"{float(round(ac * 100) / 100):.2f}"})
        cases.append({"id": f"R{c:03d}", "status": date(2025, 12, 1) + timedelta(days=rnd.randint(0, 600)),
                      "composite": rnd.random() < 0.5, "wps": wps})
    return cases


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    cases = hand_cases() + random_cases()
    with open(OUT / "datasets.csv", "w", newline="") as fd, open(OUT / "expected.csv", "w", newline="") as fe:
        wd, we = csv.writer(fd, lineterminator="\n"), csv.writer(fe, lineterminator="\n")
        wd.writerow(["case", "status_date", "composite", "wp_id", "budget", "start", "finish", "percent", "ac"])
        we.writerow(["case", "metric", "state", "value"])
        for case in cases:
            for w in case["wps"]:
                wd.writerow([case["id"], case["status"].isoformat(), str(case["composite"]).lower(), w["id"],
                             w["budget"], w["start"].isoformat(), w["finish"].isoformat(),
                             "" if w["pct"] is None else w["pct"], "" if w["ac"] is None else w["ac"]])
            result = compute(case)
            for m in METRICS:
                v = result[m]
                we.writerow([case["id"], m, "UNDEFINED" if v is None else "DEFINED", "" if v is None else fmt(v)])
    print(f"wrote {len(cases)} cases to {OUT}")


if __name__ == "__main__":
    main()
