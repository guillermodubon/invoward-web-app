package io.github.guillermodubon.invoward.insights.application.model;

import java.util.Arrays;

/** Allowlisted ordering for supplier analyses; persistence adds id DESC as a tie-break. */
public enum SupplierHistorySort {
    CREATED_AT_DESC("createdAt,desc", Field.CREATED_AT, Direction.DESC),
    CREATED_AT_ASC("createdAt,asc", Field.CREATED_AT, Direction.ASC),
    COMPLETED_AT_DESC("completedAt,desc", Field.COMPLETED_AT, Direction.DESC),
    COMPLETED_AT_ASC("completedAt,asc", Field.COMPLETED_AT, Direction.ASC);

    private final String parameterValue;
    private final Field field;
    private final Direction direction;

    SupplierHistorySort(String parameterValue, Field field, Direction direction) {
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

    public static SupplierHistorySort parse(String value) {
        if (value == null) {
            return CREATED_AT_DESC;
        }
        String candidate = value.strip();
        return Arrays.stream(values())
                .filter(sort -> sort.parameterValue.equals(candidate))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Supplier history sort is invalid."));
    }

    public enum Field {
        CREATED_AT,
        COMPLETED_AT
    }

    public enum Direction {
        ASC,
        DESC
    }
}
