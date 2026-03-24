package org.praxisplatform.uischema.stats;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Registro canônico de campos elegiveis para stats filtrados em um recurso.
 *
 * <p>
 * O registro centraliza quais campos podem ser usados como buckets, eixos temporais ou metricas
 * agregadas nos endpoints estatisticos da plataforma. Em vez de espalhar essas regras em cada
 * controller ou query ad hoc, o recurso publica explicitamente sua elegibilidade neste registry.
 * </p>
 */
public final class StatsFieldRegistry {

    private static final StatsFieldRegistry EMPTY = new StatsFieldRegistry(Map.of());

    private final Map<String, StatsFieldDescriptor> fields;

    private StatsFieldRegistry(Map<String, StatsFieldDescriptor> fields) {
        this.fields = fields;
    }

    /**
     * Retorna um registro vazio.
     *
     * @return registry vazio
     */
    public static StatsFieldRegistry empty() {
        return EMPTY;
    }

    /**
     * Cria um registro imutavel a partir de uma colecao de descritores.
     *
     * @param descriptors descritores de campos elegiveis
     * @return registry resultante
     */
    public static StatsFieldRegistry of(Collection<StatsFieldDescriptor> descriptors) {
        if (descriptors == null || descriptors.isEmpty()) {
            return empty();
        }
        Map<String, StatsFieldDescriptor> map = new LinkedHashMap<>();
        for (StatsFieldDescriptor descriptor : descriptors) {
            if (descriptor == null || descriptor.field() == null || descriptor.field().isBlank()) {
                continue;
            }
            map.put(descriptor.field(), descriptor);
        }
        return new StatsFieldRegistry(Map.copyOf(map));
    }

    /**
     * Cria um builder para montagem fluente do registro.
     *
     * @return builder do registry
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Resolve o descritor canonico de um campo elegivel.
     *
     * @param field nome canonico do campo exposto pela API
     * @return descritor do campo, quando existir
     */
    public Optional<StatsFieldDescriptor> resolve(String field) {
        if (field == null || field.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(fields.get(field));
    }

    /**
     * Indica se o registry nao possui campos elegiveis.
     *
     * @return {@code true} quando vazio
     */
    public boolean isEmpty() {
        return fields.isEmpty();
    }

    public static final class Builder {

        private final Map<String, StatsFieldDescriptor> descriptors = new LinkedHashMap<>();

        /**
         * Adiciona um descritor arbitrario ao builder.
         *
         * @param descriptor descritor do campo
         * @return o proprio builder
         */
        public Builder add(StatsFieldDescriptor descriptor) {
            if (descriptor == null || descriptor.field() == null || descriptor.field().isBlank()) {
                return this;
            }
            descriptors.put(descriptor.field(), descriptor);
            return this;
        }

        public Builder categoricalGroupByBucket(String field, String propertyPath) {
            return add(StatsFieldDescriptor.categoricalGroupByBucket(field, propertyPath));
        }

        public Builder categoricalTermsBucket(String field, String propertyPath) {
            return add(StatsFieldDescriptor.categoricalTermsBucket(field, propertyPath));
        }

        public Builder temporalTimeSeriesField(String field, String propertyPath) {
            return add(StatsFieldDescriptor.temporalTimeSeriesField(field, propertyPath));
        }

        public Builder numericMeasureField(String field, String propertyPath) {
            return add(StatsFieldDescriptor.numericMeasureField(field, propertyPath));
        }

        public Builder numericHistogramMeasureField(String field, String propertyPath) {
            return add(StatsFieldDescriptor.numericHistogramMeasureField(field, propertyPath));
        }

        public Builder groupByBucket(String field, String propertyPath, java.util.Set<StatsMetric> metrics) {
            return add(StatsFieldDescriptor.groupByBucket(field, propertyPath, metrics));
        }

        public Builder distributionTermsBucket(String field, String propertyPath, java.util.Set<StatsMetric> metrics) {
            return add(StatsFieldDescriptor.distributionTermsBucket(field, propertyPath, metrics));
        }

        public Builder metricField(String field, String propertyPath, java.util.Set<StatsMetric> metrics) {
            return add(StatsFieldDescriptor.metricField(field, propertyPath, metrics));
        }

        public Builder histogramField(String field, String propertyPath, java.util.Set<StatsMetric> metrics) {
            return add(StatsFieldDescriptor.histogramField(field, propertyPath, metrics));
        }

        /**
         * Materializa o registry imutavel com os descritores acumulados.
         *
         * @return registry construido
         */
        public StatsFieldRegistry build() {
            return StatsFieldRegistry.of(descriptors.values());
        }
    }
}
