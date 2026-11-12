package org.praxisplatform.uischema.schema;

import org.praxisplatform.uischema.annotation.CalculationMemory;
import org.praxisplatform.uischema.annotation.CalculationTrail;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolve anotacoes {@link CalculationMemory} em tipos DTO para o contrato JSON canonico
 * {@code x-ui.calculationMemory} consumido nativamente por {@code @praxisui/core} e
 * {@code @praxisui/table} ({@code PLAT-EVO-20}).
 */
public final class CalculationMemoryMetadataResolver {

    private CalculationMemoryMetadataResolver() {
    }

    public static Map<String, Object> resolve(Class<?> dtoClass) {
        if (dtoClass == null) {
            return null;
        }
        CalculationMemory memory = AnnotatedElementUtils.findMergedAnnotation(dtoClass, CalculationMemory.class);
        if (memory == null || memory.trails().length == 0) {
            return null;
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "calculationMemory");
        putIfHasText(root, "title", memory.title());
        putIfHasText(root, "titleExpr", memory.titleExpr());
        putIfHasText(root, "subtitle", memory.subtitle());
        putIfHasText(root, "subtitleExpr", memory.subtitleExpr());
        putIfHasText(root, "icon", memory.icon());
        putIfHasText(root, "legalBasis", memory.legalBasis());
        putIfHasText(root, "legalBasisExpr", memory.legalBasisExpr());
        putIfHasText(root, "emptyText", memory.emptyText());

        List<Map<String, Object>> trails = new ArrayList<>(memory.trails().length);
        for (CalculationTrail trail : memory.trails()) {
            trails.add(toTrailMap(trail));
        }
        root.put("trails", List.copyOf(trails));
        return Map.copyOf(root);
    }

    private static Map<String, Object> toTrailMap(CalculationTrail trail) {
        if (!StringUtils.hasText(trail.id())) {
            throw new IllegalArgumentException("@CalculationTrail.id must not be blank.");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", trail.id().trim());
        putIfHasText(map, "title", trail.title());
        putIfHasText(map, "titleExpr", trail.titleExpr());
        putIfHasText(map, "subtitle", trail.subtitle());
        putIfHasText(map, "subtitleExpr", trail.subtitleExpr());
        putIfHasText(map, "icon", trail.icon());
        putIfHasText(map, "ruleCode", trail.ruleCode());
        putIfHasText(map, "ruleCodeExpr", trail.ruleCodeExpr());
        putIfHasText(map, "formula", trail.formula());
        putIfHasText(map, "formulaExpr", trail.formulaExpr());
        putIfHasText(map, "visibleWhen", trail.visibleWhen());

        putIfHasText(map, "baseLabel", trail.baseLabel());
        putIfHasText(map, "baseExpr", trail.baseExpr());
        putIfHasText(map, "baseFormat", trail.baseFormat());
        putIfHasText(map, "baseHint", trail.baseHint());
        putIfHasText(map, "baseHintExpr", trail.baseHintExpr());

        putIfHasText(map, "additionLabel", trail.additionLabel());
        putIfHasText(map, "additionExpr", trail.additionExpr());
        putIfHasText(map, "additionFormat", trail.additionFormat());
        putIfHasText(map, "additionHint", trail.additionHint());
        putIfHasText(map, "additionHintExpr", trail.additionHintExpr());

        putIfHasText(map, "deductionLabel", trail.deductionLabel());
        putIfHasText(map, "deductionExpr", trail.deductionExpr());
        putIfHasText(map, "deductionFormat", trail.deductionFormat());
        putIfHasText(map, "deductionHint", trail.deductionHint());
        putIfHasText(map, "deductionHintExpr", trail.deductionHintExpr());

        putIfHasText(map, "factorLabel", trail.factorLabel());
        putIfHasText(map, "factorExpr", trail.factorExpr());
        putIfHasText(map, "factorFormat", trail.factorFormat());
        putIfHasText(map, "factorHint", trail.factorHint());
        putIfHasText(map, "factorHintExpr", trail.factorHintExpr());

        putIfHasText(map, "capLabel", trail.capLabel());
        putIfHasText(map, "capExpr", trail.capExpr());
        putIfHasText(map, "capFormat", trail.capFormat());
        putIfHasText(map, "capHint", trail.capHint());
        putIfHasText(map, "capHintExpr", trail.capHintExpr());

        putIfHasText(map, "resultLabel", trail.resultLabel());
        putIfHasText(map, "resultExpr", trail.resultExpr());
        putIfHasText(map, "resultFormat", trail.resultFormat());
        putIfHasText(map, "resultTone", trail.resultTone());
        putIfHasText(map, "resultHint", trail.resultHint());
        putIfHasText(map, "resultHintExpr", trail.resultHintExpr());

        putIfHasText(map, "legalBasis", trail.legalBasis());
        putIfHasText(map, "legalBasisExpr", trail.legalBasisExpr());
        putIfHasText(map, "legalPurpose", trail.legalPurpose());
        putIfHasText(map, "legalPurposeExpr", trail.legalPurposeExpr());
        return Map.copyOf(map);
    }

    private static void putIfHasText(Map<String, Object> target, String key, String value) {
        if (StringUtils.hasText(value)) {
            target.put(key, value.trim());
        }
    }
}
