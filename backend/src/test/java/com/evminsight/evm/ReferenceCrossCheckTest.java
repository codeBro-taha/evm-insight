package com.evminsight.evm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Differential test: every case in datasets.csv is recomputed by the Java engine and compared with the
 * expected.csv produced by the independent exact-fraction Python reference (docs/reference/evm_reference.py).
 */
@Tag("NFR-02")
@DisplayName("EVM engine agrees with the independent reference implementation")
class ReferenceCrossCheckTest {

    private static final BigDecimal REL_TOLERANCE = new BigDecimal("1e-25");

    @Test
    void allReferenceCasesMatch() throws IOException {
        Map<String, List<String[]>> datasets = group(read("/reference/datasets.csv"));
        Map<String, List<String[]>> expected = group(read("/reference/expected.csv"));
        assertTrue(datasets.size() >= 300, "reference data set is unexpectedly small: " + datasets.size());
        assertEquals(datasets.keySet(), expected.keySet());

        int compared = 0;
        for (Map.Entry<String, List<String[]>> entry : datasets.entrySet()) {
            String caseId = entry.getKey();
            List<WorkPackageInput> wps = new ArrayList<>();
            LocalDate status = null;
            boolean composite = false;
            for (String[] row : entry.getValue()) {
                status = LocalDate.parse(row[1]);
                composite = Boolean.parseBoolean(row[2]);
                wps.add(new WorkPackageInput(row[3], new BigDecimal(row[4]), LocalDate.parse(row[5]),
                        LocalDate.parse(row[6]), row[7].isEmpty() ? null : new BigDecimal(row[7]),
                        row[8].isEmpty() ? null : new BigDecimal(row[8])));
            }
            Map<String, Metric> actual = EvmCalculator.calculate(wps, status, new EvmOptions(composite)).asMap();
            for (String[] exp : expected.get(caseId)) {
                String metric = exp[1];
                Metric got = actual.get(metric);
                String label = caseId + "/" + metric;
                if (exp[2].equals("UNDEFINED")) {
                    assertTrue(!got.isDefined(), label + ": reference says undefined but engine returned " + got.value());
                } else {
                    assertTrue(got.isDefined(), label + ": reference says defined but engine said: " + got.detail());
                    BigDecimal want = new BigDecimal(exp[3]);
                    BigDecimal allowed = REL_TOLERANCE.multiply(want.abs().max(BigDecimal.ONE));
                    assertTrue(got.value().subtract(want).abs().compareTo(allowed) <= 0,
                            label + ": expected " + want.toPlainString() + " but was " + got.value().toPlainString());
                }
                compared++;
            }
        }
        assertEquals(datasets.size() * 15, compared);
    }

    private static List<String[]> read(String resource) throws IOException {
        List<String[]> rows = new ArrayList<>();
        try (InputStream in = ReferenceCrossCheckTest.class.getResourceAsStream(resource)) {
            assertTrue(in != null, "missing test resource " + resource);
            BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            br.readLine(); // header
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.isBlank()) {
                    rows.add(line.split(",", -1));
                }
            }
        }
        return rows;
    }

    private static Map<String, List<String[]>> group(List<String[]> rows) {
        Map<String, List<String[]>> byCase = new LinkedHashMap<>();
        for (String[] r : rows) {
            byCase.computeIfAbsent(r[0], k -> new ArrayList<>()).add(r);
        }
        return byCase;
    }
}
