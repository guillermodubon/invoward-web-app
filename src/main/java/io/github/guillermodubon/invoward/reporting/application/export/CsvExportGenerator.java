package io.github.guillermodubon.invoward.reporting.application.export;

import io.github.guillermodubon.invoward.reconciliation.application.model.ReconciliationResult;
import io.github.guillermodubon.invoward.reconciliation.domain.Discrepancy;
import io.github.guillermodubon.invoward.reconciliation.domain.ReconciliationLine;
import io.github.guillermodubon.invoward.reporting.application.exception.CsvExportTooLargeException;
import io.github.guillermodubon.invoward.reporting.application.model.CsvExport;

import java.io.OutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Generates a bounded, deterministic CSV view of an existing reconciliation projection. */
public final class CsvExportGenerator {

    private static final List<String> HEADER = List.of(
            "Scope", "Line Status", "Reference Code", "Reference Description", "Invoice Code",
            "Invoice Description", "Reference Qty", "Invoice Qty", "Quantity Difference",
            "Reference Unit Price", "Invoice Unit Price", "Unit Price Difference", "Reference Line Total",
            "Invoice Line Total", "Line Total Difference", "Discrepancy Type", "Reference Value",
            "Invoice Value", "Absolute Difference", "Percentage Difference", "Resolution Status",
            "Resolution Reason", "Resolution Note");

    private final int maximumBytes;

    public CsvExportGenerator(long maximumBytes) {
        if (maximumBytes <= 0 || maximumBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("CSV maximum size must be between 1 byte and 2 GiB");
        }
        this.maximumBytes = (int) maximumBytes;
    }

    /** Returns the entire export or throws without exposing a partial payload. */
    public CsvExport generate(ReconciliationResult reconciliation) {
        Objects.requireNonNull(reconciliation, "reconciliation must not be null");
        BoundedOutput output = new BoundedOutput(maximumBytes);
        writeRow(output, HEADER);

        List<ReconciliationLine> lines = reconciliation.lines();
        Map<UUID, Integer> linePositions = new HashMap<>();
        for (int index = 0; index < lines.size(); index++) {
            ReconciliationLine line = lines.get(index);
            if (linePositions.putIfAbsent(line.id(), index) != null) {
                throw new IllegalArgumentException("Reconciliation result contains duplicate line identifiers");
            }
        }

        List<Discrepancy> discrepancies = orderedDiscrepancies(reconciliation.discrepancies(), linePositions);
        Map<UUID, List<Discrepancy>> discrepanciesByLine = new HashMap<>();
        List<Discrepancy> documentDiscrepancies = new ArrayList<>();
        for (Discrepancy discrepancy : discrepancies) {
            if (discrepancy.reconciliationLineId() == null) {
                documentDiscrepancies.add(discrepancy);
            } else {
                discrepanciesByLine.computeIfAbsent(discrepancy.reconciliationLineId(), ignored -> new ArrayList<>())
                        .add(discrepancy);
            }
        }

        for (ReconciliationLine line : lines) {
            List<Discrepancy> lineDiscrepancies = discrepanciesByLine.get(line.id());
            if (lineDiscrepancies == null || lineDiscrepancies.isEmpty()) {
                writeRow(output, lineRow(line, null));
            } else {
                for (Discrepancy discrepancy : lineDiscrepancies) {
                    writeRow(output, lineRow(line, discrepancy));
                }
            }
        }
        for (Discrepancy discrepancy : documentDiscrepancies) {
            writeRow(output, documentRow(discrepancy));
        }

        return new CsvExport(output.toByteArray());
    }

    private static List<Discrepancy> orderedDiscrepancies(
            List<Discrepancy> discrepancies, Map<UUID, Integer> linePositions) {
        Comparator<Discrepancy> order = Comparator
                .comparingInt((Discrepancy item) -> item.reconciliationLineId() == null
                        ? Integer.MAX_VALUE
                        : requireLinePosition(item, linePositions))
                .thenComparing(item -> item.type().ordinal())
                .thenComparing(Discrepancy::id);
        return discrepancies.stream().sorted(order).toList();
    }

    private static int requireLinePosition(Discrepancy discrepancy, Map<UUID, Integer> linePositions) {
        Integer position = linePositions.get(discrepancy.reconciliationLineId());
        if (position == null) {
            throw new IllegalArgumentException("Discrepancy references an unknown reconciliation line");
        }
        return position;
    }

    private static List<String> lineRow(ReconciliationLine line, Discrepancy discrepancy) {
        return List.of(
                text("LINE"), text(line.status().name()), text(line.referenceItemCode()),
                text(line.referenceDescription()), text(line.invoiceItemCode()), text(line.invoiceDescription()),
                number(line.referenceQuantity()), number(line.invoiceQuantity()), number(line.quantityDifference()),
                number(line.referenceUnitPrice()), number(line.invoiceUnitPrice()), number(line.unitPriceDifference()),
                number(line.referenceLineTotal()), number(line.invoiceLineTotal()), number(line.lineTotalDifference()),
                text(discrepancy == null ? null : discrepancy.type().name()),
                text(discrepancy == null ? null : discrepancy.referenceValue()),
                text(discrepancy == null ? null : discrepancy.invoiceValue()),
                number(discrepancy == null ? null : discrepancy.absoluteDifference()),
                number(discrepancy == null ? null : discrepancy.percentageDifference()),
                text(discrepancy == null ? null : discrepancy.resolutionStatus().name()),
                text(discrepancy == null || discrepancy.resolutionReason() == null
                        ? null : discrepancy.resolutionReason().name()),
                text(discrepancy == null ? null : discrepancy.resolutionNote()));
    }

    private static List<String> documentRow(Discrepancy discrepancy) {
        return List.of(
                text("DOCUMENT"), text(null), text(null), text(null), text(null), text(null), text(null), text(null),
                text(null), text(null), text(null), text(null), text(null), text(null), text(null),
                text(discrepancy.type().name()), text(discrepancy.referenceValue()), text(discrepancy.invoiceValue()),
                number(discrepancy.absoluteDifference()), number(discrepancy.percentageDifference()),
                text(discrepancy.resolutionStatus().name()),
                text(discrepancy.resolutionReason() == null ? null : discrepancy.resolutionReason().name()),
                text(discrepancy.resolutionNote()));
    }

    private static String text(String value) {
        return CsvEscaper.escapeText(value);
    }

    private static String number(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }

    private static void writeRow(BoundedOutput output, List<String> cells) {
        try {
            for (int index = 0; index < cells.size(); index++) {
                if (index > 0) {
                    output.write(',');
                }
                output.write(cells.get(index).getBytes(StandardCharsets.UTF_8));
            }
            output.write('\r');
            output.write('\n');
        } catch (IOException exception) {
            // BoundedOutput is in-memory; only its size limit can interrupt writing.
            throw new IllegalStateException("Unexpected in-memory CSV output failure", exception);
        }
    }

    /** Grows geometrically but never allocates beyond the configured maximum. */
    private static final class BoundedOutput extends OutputStream {

        private final int maximumBytes;
        private byte[] buffer;
        private int size;

        private BoundedOutput(int maximumBytes) {
            this.maximumBytes = maximumBytes;
            this.buffer = new byte[Math.min(maximumBytes, 8192)];
        }

        @Override
        public void write(int value) {
            ensureCapacity(1);
            buffer[size++] = (byte) value;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            ensureCapacity(length);
            System.arraycopy(bytes, offset, buffer, size, length);
            size += length;
        }

        private void ensureCapacity(int addition) {
            if (addition > maximumBytes - size) {
                throw new CsvExportTooLargeException();
            }
            int required = size + addition;
            if (required <= buffer.length) {
                return;
            }
            long doubled = Math.max(1L, (long) buffer.length * 2);
            int capacity = (int) Math.min(maximumBytes, Math.max(required, doubled));
            buffer = java.util.Arrays.copyOf(buffer, capacity);
        }

        private byte[] toByteArray() {
            return java.util.Arrays.copyOf(buffer, size);
        }
    }
}
