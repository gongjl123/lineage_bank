package com.icbc.lineage.infrastructure.json;

import com.icbc.lineage.domain.model.Derivation;
import com.icbc.lineage.domain.model.LineageDocument;
import com.icbc.lineage.domain.model.LineageIssue;
import com.icbc.lineage.domain.model.LineageSource;
import com.icbc.lineage.domain.model.SourceKind;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class LineageSemanticValidator {

    public List<LineageIssue> validate(LineageDocument document) {
        List<LineageIssue> issues = new ArrayList<>();
        Map<String, Integer> targets = new HashMap<>();
        for (int index = 0; index < document.derivations().size(); index++) {
            Derivation derivation = document.derivations().get(index);
            String target = derivation.target();
            String location = "/derivations/" + index;
            targets.merge(target.toUpperCase(java.util.Locale.ROOT), 1, Integer::sum);
            if (needsSourceReview(derivation)) {
                issues.add(emptySourceIssue(document.datasetId(), index, target, 0));
            }
            Set<String> usedLogic = new HashSet<>();
            for (int sourceIndex = 0; sourceIndex < derivation.sources().size(); sourceIndex++) {
                LineageSource source = derivation.sources().get(sourceIndex);
                String logicPath = resolveLogicPath(derivation.transformLogic(), source.path());
                if (logicPath == null) {
                    issues.add(LineageIssue.of("MISSING_LOGIC", "来源路径缺少转换逻辑：" + source.path(),
                            document.datasetId(), location + "/sources/" + sourceIndex,
                            LineageIssue.Severity.WARNING).with("target", target));
                } else {
                    usedLogic.add(logicPath);
                }
                if (source.kind() == SourceKind.COLUMN && source.ref().equalsIgnoreCase(target)) {
                    issues.add(LineageIssue.of("SELF_DEPENDENCY", "字段引用自身，保留该关系。",
                            document.datasetId(), location, LineageIssue.Severity.WARNING)
                            .with("target", target));
                }
            }
            derivation.transformLogic().keySet().stream().filter(path -> !usedLogic.contains(path)).sorted()
                    .forEach(path -> issues.add(LineageIssue.of("UNUSED_LOGIC",
                            "转换逻辑路径未被来源引用：" + path, document.datasetId(), location,
                            LineageIssue.Severity.WARNING).with("target", target)));
        }
        targets.forEach((target, count) -> {
            if (count > 1) {
                issues.add(LineageIssue.of("MULTIPLE_DERIVATIONS", "同一文档包含多条字段派生记录。",
                        document.datasetId(), "", LineageIssue.Severity.INFO)
                        .with("target", target).with("count", count));
            }
        });
        return List.copyOf(issues);
    }

    public boolean ignoredInputPath(String value) {
        if (value == null) {
            return false;
        }
        for (String part : value.replace('\\', '/').split("/")) {
            if (part.equalsIgnoreCase(".ipynb_checkpoints")) {
                return true;
            }
        }
        return false;
    }

    public boolean needsSourceReview(Derivation derivation) {
        return derivation.sources().isEmpty() && !derivation.transformLogic().isEmpty();
    }

    public LineageIssue emptySourceIssue(String document, int index, String target, int downstreamCount) {
        String message = downstreamCount > 0
                ? "字段有加工逻辑但没有来源，且已被其他字段直接引用。"
                : "字段有加工逻辑但没有来源，请核查逻辑与来源。";
        LineageIssue.Severity severity = downstreamCount > 0
                ? LineageIssue.Severity.WARNING : LineageIssue.Severity.INFO;
        return LineageIssue.of(downstreamCount > 0 ? "EMPTY_SOURCES_USED" : "EMPTY_SOURCES",
                message, document, "/derivations/" + index, severity)
                .with("target", target).with("downstream_count", downstreamCount);
    }

    public String resolveLogicPath(Map<String, Map<String, Object>> logic, String path) {
        if (logic == null || path == null) {
            return null;
        }
        String current = path;
        while (current != null) {
            if (logic.containsKey(current)) {
                return current;
            }
            int separator = current.lastIndexOf('-');
            current = separator < 0 ? null : current.substring(0, separator);
        }
        return null;
    }
}
