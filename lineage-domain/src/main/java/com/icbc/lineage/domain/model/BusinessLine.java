package com.icbc.lineage.domain.model;

import java.util.Locale;

public enum BusinessLine {
    EAST, STANDARD, STD, YBT;

    public static BusinessLine from(String value) {
        if (value == null || value.isBlank()) {
            return EAST;
        }
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }
}
