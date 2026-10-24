package io.github.guillermodubon.invoward.analysis.application.model;

import io.github.guillermodubon.invoward.analysis.application.exception.InvalidAnalysisHistoryQueryException;

import java.util.Arrays;

/** Allowlisted, single-field ordering options for Analysis history. */
public enum AnalysisHistorySort {
    CREATED_AT_DESC("createdAt,desc", Field.CREATED_AT, Direction.DESC),
    CREATED_AT_ASC("createdAt,asc", Field.CREATED_AT, Direction.ASC),
    DIFFERENCE_DESC("difference,desc", Field.DIFFERENCE, Direction.DESC),
    DIFFERENCE_ASC("difference,asc", Field.DIFFERENCE, Direction.ASC),
    INVOICED_TOTAL_DESC("invoicedTotal,desc", Field.INVOICED_TOTAL, Direction.DESC),
    INVOICED_TOTAL_ASC("invoicedTotal,asc", Field.INVOICED_TOTAL, Direction.ASC),
    SUPPLIER_NAME_ASC("supplierName,asc", Field.SUPPLIER_NAME, Direction.ASC),
    SUPPLIER_NAME_DESC("supplierName,desc", Field.SUPPLIER_NAME, Direction.DESC);

    private final String parameterValue;
    private final Field field;
    private final Direction direction;

    AnalysisHistorySort(String parameterValue, Field field, Direction direction) {
        this.parameterValue = parameterValue;
        this.field = field;
        this.direction = direction;
    }

    public String parameterValue() {
        return parameterValue;
    }

    public Field field() {
        return field;
    }

    public Direction direction() {
        return direction;
    }

    /** Parses only the public allowlist; arbitrary persistence sort expressions are never accepted. */
    public static AnalysisHistorySort parse(String value) {
        if (value == null) {
            return CREATED_AT_DESC;
        }
        String normalized = value.strip();
        return Arrays.stream(values())
                .filter(sort -> sort.parameterValue.equals(normalized))
                .findFirst()
                .orElseThrow(InvalidAnalysisHistoryQueryException::new);
    }

    public enum Field {
        CREATED_AT,
        DIFFERENCE,
        INVOICED_TOTAL,
        SUPPLIER_NAME
    }

    public enum Direction {
        ASC,
        DESC
    }
}
