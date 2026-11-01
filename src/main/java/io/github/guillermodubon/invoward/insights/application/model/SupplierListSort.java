package io.github.guillermodubon.invoward.insights.application.model;

import java.util.Arrays;

/** Allowlisted supplier-list order; persistence adapters provide the key tie-break. */
public enum SupplierListSort {
    ANALYSIS_COUNT_DESC("analysisCount,desc", Field.ANALYSIS_COUNT, Direction.DESC),
    ANALYSIS_COUNT_ASC("analysisCount,asc", Field.ANALYSIS_COUNT, Direction.ASC),
    SUPPLIER_NAME_ASC("supplierName,asc", Field.SUPPLIER_NAME, Direction.ASC),
    SUPPLIER_NAME_DESC("supplierName,desc", Field.SUPPLIER_NAME, Direction.DESC);

    private final String parameterValue;
    private final Field field;
    private final Direction direction;

    SupplierListSort(String parameterValue, Field field, Direction direction) {
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

    public static SupplierListSort parse(String value) {
        if (value == null) {
            return ANALYSIS_COUNT_DESC;
        }
        String candidate = value.strip();
        return Arrays.stream(values())
                .filter(sort -> sort.parameterValue.equals(candidate))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Supplier list sort is invalid."));
    }

    public enum Field {
        ANALYSIS_COUNT,
        SUPPLIER_NAME
    }

    public enum Direction {
        ASC,
        DESC
    }
}
