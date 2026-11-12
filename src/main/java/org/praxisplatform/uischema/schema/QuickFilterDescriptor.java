package org.praxisplatform.uischema.schema;

import org.praxisplatform.uischema.annotation.QuickFilterTone;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Descritor programatico e type-safe de filtro rapido associado ao {@code FilterDTO} de um recurso.
 *
 * <p>
 * Complementa {@link org.praxisplatform.uischema.annotation.QuickFilter} para cenarios em que o
 * autor deseja refactor-safety total na IDE (sem literais de nome de campo) por meio de
 * {@code AbstractResourceQueryController#defineQuickFilters()}.
 * </p>
 *
 * @param <F> tipo concreto do {@link GenericFilterDTO} do recurso
 */
public final class QuickFilterDescriptor<F extends GenericFilterDTO> {

    private final String id;
    private final String label;
    private final String icon;
    private final QuickFilterTone tone;
    private final boolean defaultActive;
    private final String description;
    private final int order;
    private final Consumer<F> filterConfigurer;
    private final Map<String, Object> explicitCriteria;

    private QuickFilterDescriptor(Builder<F> builder) {
        this.id = Objects.requireNonNull(builder.id, "id must not be null").trim();
        this.label = Objects.requireNonNull(builder.label, "label must not be null").trim();
        this.icon = builder.icon == null ? "" : builder.icon.trim();
        this.tone = builder.tone == null ? QuickFilterTone.UNSPECIFIED : builder.tone;
        this.defaultActive = builder.defaultActive;
        this.description = builder.description == null ? "" : builder.description.trim();
        this.order = builder.order;
        this.filterConfigurer = builder.filterConfigurer;
        this.explicitCriteria = Collections.unmodifiableMap(new LinkedHashMap<>(builder.explicitCriteria));
    }

    public static <F extends GenericFilterDTO> Builder<F> builder(String id, String label) {
        return new Builder<>(id, label);
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public String icon() {
        return icon;
    }

    public QuickFilterTone tone() {
        return tone;
    }

    public boolean defaultActive() {
        return defaultActive;
    }

    public String description() {
        return description;
    }

    public int order() {
        return order;
    }

    public Consumer<F> filterConfigurer() {
        return filterConfigurer;
    }

    public Map<String, Object> explicitCriteria() {
        return explicitCriteria;
    }

    public static final class Builder<F extends GenericFilterDTO> {
        private final String id;
        private final String label;
        private String icon = "";
        private QuickFilterTone tone = QuickFilterTone.UNSPECIFIED;
        private boolean defaultActive;
        private String description = "";
        private int order;
        private Consumer<F> filterConfigurer;
        private final Map<String, Object> explicitCriteria = new LinkedHashMap<>();

        private Builder(String id, String label) {
            this.id = id;
            this.label = label;
        }

        public Builder<F> icon(String icon) {
            this.icon = icon;
            return this;
        }

        public Builder<F> tone(QuickFilterTone tone) {
            this.tone = tone;
            return this;
        }

        public Builder<F> defaultActive(boolean defaultActive) {
            this.defaultActive = defaultActive;
            return this;
        }

        public Builder<F> description(String description) {
            this.description = description;
            return this;
        }

        public Builder<F> order(int order) {
            this.order = order;
            return this;
        }

        /**
         * Configura uma instancia limpa do proprio {@code FilterDTO} de forma type-safe.
         */
        public Builder<F> where(Consumer<F> filterConfigurer) {
            this.filterConfigurer = filterConfigurer;
            return this;
        }

        /**
         * Adiciona um criterio explicito de chave/valor para cenarios dinamicos ou campos customizados.
         */
        public Builder<F> criterion(String field, Object value) {
            if (field != null && !field.isBlank() && value != null) {
                this.explicitCriteria.put(field.trim(), value);
            }
            return this;
        }

        public QuickFilterDescriptor<F> build() {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("QuickFilterDescriptor requires a non-blank id.");
            }
            if (label == null || label.isBlank()) {
                throw new IllegalArgumentException("QuickFilterDescriptor requires a non-blank label.");
            }
            if (filterConfigurer == null && explicitCriteria.isEmpty()) {
                throw new IllegalArgumentException(
                        "QuickFilterDescriptor '" + id + "' must configure at least one filter criterion via where(...) or criterion(...)."
                );
            }
            return new QuickFilterDescriptor<>(this);
        }
    }
}
