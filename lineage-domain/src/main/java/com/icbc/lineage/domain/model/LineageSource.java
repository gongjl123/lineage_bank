package com.icbc.lineage.domain.model;

public record LineageSource(SourceKind kind, String ref, String value, String path) {

    public static LineageSource column(String ref, String path) {
        return new LineageSource(SourceKind.COLUMN, ref, null, path);
    }

    public static LineageSource constant(String value, String path) {
        return new LineageSource(SourceKind.CONSTANT, null, value, path);
    }
}
