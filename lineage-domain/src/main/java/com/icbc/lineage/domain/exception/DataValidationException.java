package com.icbc.lineage.domain.exception;

import com.icbc.lineage.domain.model.LineageIssue;

import java.util.List;

public class DataValidationException extends IllegalArgumentException {

    private final List<LineageIssue> issues;

    public DataValidationException(List<LineageIssue> issues) {
        super(issues.isEmpty() ? "Invalid lineage data" : issues.get(0).message());
        this.issues = List.copyOf(issues);
    }

    public List<LineageIssue> getIssues() {
        return issues;
    }
}
