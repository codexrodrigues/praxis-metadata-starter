package org.praxisplatform.uischema.service.base;

import jakarta.persistence.criteria.Path;
import org.praxisplatform.uischema.id.JpaCriteriaPathSupport;
import org.praxisplatform.uischema.rest.exceptionhandler.exception.BusinessException;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Suporte canonico reutilizavel para entidades corporativas com intervalo de vigencia temporal
 * inclusivo {@code [dtini, dtfim]}, onde {@code dtfim == null} representa vigencia em aberto.
 *
 * <p>Fornece tres capacidades deterministas de plataforma:
 * <ol>
 *   <li>Validacao determinista de intervalo e de nao-sobreposicao ({@link #validateNoOverlap});</li>
 *   <li>Encerramento automatico da vigencia aberta anterior ({@code dtfim = novaDtini.minusDays(1)})
 *       quando uma nova vigencia posterior e inserida ({@link #closePreviousOpenValidity},
 *       {@link #prepareAndValidateInsert});</li>
 *   <li>Especificacao JPA Criteria reutilizavel para o filtro {@code vigenteEm} / {@code asOfDate}
 *       ({@link #vigenteEm(LocalDate)}, {@link #vigenteEm(LocalDate, String, String)}).</li>
 * </ol>
 */
public final class EffectiveDatedResourceSupport {

    public static final String DEFAULT_ID_FIELD = "id";
    public static final String DEFAULT_START_FIELD = "dtini";
    public static final String DEFAULT_END_FIELD = "dtfim";

    private EffectiveDatedResourceSupport() {
    }

    /**
     * Contrato opcional de leitura para entidades ou projeções com vigencia {@code [dtini, dtfim]}.
     */
    public interface EffectiveDatedInterval {
        LocalDate getDtini();

        LocalDate getDtfim();

        default boolean isOpenEnded() {
            return getDtfim() == null;
        }

        default boolean isEffectiveOn(LocalDate referenceDate) {
            return EffectiveDatedResourceSupport.isEffectiveOn(getDtini(), getDtfim(), referenceDate);
        }

        default boolean overlaps(LocalDate otherDtini, LocalDate otherDtfim) {
            return EffectiveDatedResourceSupport.overlaps(getDtini(), getDtfim(), otherDtini, otherDtfim);
        }
    }

    /**
     * Contrato opcional de mutacao para entidades cuja vigencia aberta anterior pode ser encerrada.
     */
    public interface MutableEffectiveDatedInterval extends EffectiveDatedInterval {
        void setDtfim(LocalDate dtfim);
    }

    /**
     * Value object imutavel representando um intervalo de vigencia validado {@code [dtini, dtfim]}.
     */
    public record ValidityInterval(LocalDate dtini, LocalDate dtfim) implements EffectiveDatedInterval {
        public ValidityInterval {
            validateRange(dtini, dtfim);
        }

        @Override
        public LocalDate getDtini() {
            return dtini;
        }

        @Override
        public LocalDate getDtfim() {
            return dtfim;
        }

        public boolean overlaps(ValidityInterval other) {
            Objects.requireNonNull(other, "Intervalo de comparacao nao pode ser nulo.");
            return EffectiveDatedResourceSupport.overlaps(dtini, dtfim, other.dtini, other.dtfim);
        }
    }

    /**
     * Valida que {@code dtini} nao e nulo e que {@code dtfim} (quando informado) nao e anterior a {@code dtini}.
     */
    public static void validateRange(LocalDate dtini, LocalDate dtfim) {
        if (dtini == null) {
            throw new BusinessException("Data inicial de vigência (dtini) é obrigatória.");
        }
        if (dtfim != null && dtfim.isBefore(dtini)) {
            throw new BusinessException(
                    "Data final de vigência (dtfim) não pode ser anterior à data inicial (dtini): ["
                            + dtini + ", " + dtfim + "]."
            );
        }
    }

    /**
     * Verifica se o intervalo {@code [dtini, dtfim]} esta em vigor na data de referencia (limites inclusivos).
     */
    public static boolean isEffectiveOn(LocalDate dtini, LocalDate dtfim, LocalDate referenceDate) {
        if (dtini == null || referenceDate == null) {
            return false;
        }
        return !dtini.isAfter(referenceDate) && (dtfim == null || !dtfim.isBefore(referenceDate));
    }

    /**
     * Verifica deterministicamente se dois intervalos inclusivos {@code [startA, endA]} e
     * {@code [startB, endB]} possuem intersecao, tratando {@code end == null} como vigencia aberta ({@code +∞}).
     */
    public static boolean overlaps(LocalDate startA, LocalDate endA, LocalDate startB, LocalDate endB) {
        validateRange(startA, endA);
        validateRange(startB, endB);
        LocalDate effectiveEndA = endA != null ? endA : LocalDate.MAX;
        LocalDate effectiveEndB = endB != null ? endB : LocalDate.MAX;
        return !startA.isAfter(effectiveEndB) && !startB.isAfter(effectiveEndA);
    }

    /**
     * Retorna todos os registros existentes cujos intervalos de vigencia colidem com {@code [candidateDtini, candidateDtfim]}.
     */
    public static <E> List<E> findOverlapping(
            LocalDate candidateDtini,
            LocalDate candidateDtfim,
            Collection<E> existingEntities,
            Function<E, LocalDate> startExtractor,
            Function<E, LocalDate> endExtractor
    ) {
        validateRange(candidateDtini, candidateDtfim);
        Objects.requireNonNull(startExtractor, "startExtractor e obrigatorio.");
        Objects.requireNonNull(endExtractor, "endExtractor e obrigatorio.");
        if (existingEntities == null || existingEntities.isEmpty()) {
            return List.of();
        }
        List<E> conflicts = new ArrayList<>();
        for (E existing : existingEntities) {
            if (existing == null) {
                continue;
            }
            LocalDate existingStart = startExtractor.apply(existing);
            LocalDate existingEnd = endExtractor.apply(existing);
            if (existingStart == null) {
                continue;
            }
            if (overlaps(candidateDtini, candidateDtfim, existingStart, existingEnd)) {
                conflicts.add(existing);
            }
        }
        return List.copyOf(conflicts);
    }

    /**
     * Retorna todos os registros existentes (excluindo o proprio candidato por {@code idExtractor} ou referencia)
     * cujos intervalos de vigencia colidem com o candidato.
     */
    public static <E, ID> List<E> findOverlapping(
            E candidate,
            Collection<E> existingEntities,
            Function<E, ID> idExtractor,
            Function<E, LocalDate> startExtractor,
            Function<E, LocalDate> endExtractor
    ) {
        Objects.requireNonNull(candidate, "Entidade candidata nao pode ser nula.");
        Objects.requireNonNull(startExtractor, "startExtractor e obrigatorio.");
        Objects.requireNonNull(endExtractor, "endExtractor e obrigatorio.");
        LocalDate candidateDtini = startExtractor.apply(candidate);
        LocalDate candidateDtfim = endExtractor.apply(candidate);
        validateRange(candidateDtini, candidateDtfim);
        if (existingEntities == null || existingEntities.isEmpty()) {
            return List.of();
        }
        ID candidateId = idExtractor != null ? idExtractor.apply(candidate) : null;
        List<E> filtered = existingEntities.stream()
                .filter(Objects::nonNull)
                .filter(existing -> !isSameIdentity(candidate, candidateId, existing, idExtractor))
                .toList();
        return findOverlapping(candidateDtini, candidateDtfim, filtered, startExtractor, endExtractor);
    }

    /**
     * Valida que o intervalo {@code [candidateDtini, candidateDtfim]} nao possui sobreposicao com
     * nenhum dos registros informados, lancando {@link BusinessException} em caso de conflito.
     */
    public static <E> void validateNoOverlap(
            LocalDate candidateDtini,
            LocalDate candidateDtfim,
            Collection<E> existingEntities,
            Function<E, LocalDate> startExtractor,
            Function<E, LocalDate> endExtractor
    ) {
        List<E> conflicts = findOverlapping(candidateDtini, candidateDtfim, existingEntities, startExtractor, endExtractor);
        if (!conflicts.isEmpty()) {
            E first = conflicts.get(0);
            LocalDate conflictStart = startExtractor.apply(first);
            LocalDate conflictEnd = endExtractor.apply(first);
            throw new BusinessException(
                    "Sobreposição de vigência detectada entre o período informado ["
                            + formatInterval(candidateDtini, candidateDtfim)
                            + "] e a vigência existente ["
                            + formatInterval(conflictStart, conflictEnd)
                            + "]."
            );
        }
    }

    /**
     * Valida que a entidade candidata nao possui sobreposicao de vigencia com os demais registros
     * existentes da mesma chave de negocio (excluindo o proprio registro em atualizacoes).
     */
    public static <E, ID> void validateNoOverlap(
            E candidate,
            Collection<E> existingEntities,
            Function<E, ID> idExtractor,
            Function<E, LocalDate> startExtractor,
            Function<E, LocalDate> endExtractor
    ) {
        List<E> conflicts = findOverlapping(candidate, existingEntities, idExtractor, startExtractor, endExtractor);
        if (!conflicts.isEmpty()) {
            E first = conflicts.get(0);
            LocalDate candidateStart = startExtractor.apply(candidate);
            LocalDate candidateEnd = endExtractor.apply(candidate);
            LocalDate conflictStart = startExtractor.apply(first);
            LocalDate conflictEnd = endExtractor.apply(first);
            throw new BusinessException(
                    "Sobreposição de vigência detectada entre o período informado ["
                            + formatInterval(candidateStart, candidateEnd)
                            + "] e a vigência existente ["
                            + formatInterval(conflictStart, conflictEnd)
                            + "]."
            );
        }
    }

    /**
     * Sobrecarga por convencao para entidades que expõem propriedades {@code id}, {@code dtini} e {@code dtfim}.
     */
    public static <E> void validateNoOverlap(E candidate, Collection<E> existingEntities) {
        validateNoOverlap(
                candidate,
                existingEntities,
                entity -> readProperty(entity, DEFAULT_ID_FIELD, Object.class),
                entity -> readProperty(entity, DEFAULT_START_FIELD, LocalDate.class),
                entity -> readProperty(entity, DEFAULT_END_FIELD, LocalDate.class)
        );
    }

    /**
     * Encerra automaticamente a vigencia aberta anterior ({@code dtfim = novaDtini.minusDays(1)})
     * quando {@code novaDtini} e estritamente posterior ao {@code dtini} da vigencia em aberto.
     *
     * @return {@link Optional} contendo a entidade anterior que teve seu {@code dtfim} encerrado,
     *         ou {@link Optional#empty()} quando nao havia vigencia aberta anterior elegivel.
     */
    public static <E> Optional<E> closePreviousOpenValidity(
            LocalDate novaDtini,
            Collection<E> existingEntities,
            Function<E, LocalDate> startExtractor,
            Function<E, LocalDate> endExtractor,
            BiConsumer<E, LocalDate> endMutator
    ) {
        if (novaDtini == null) {
            throw new BusinessException("Data inicial da nova vigência (dtini) é obrigatória.");
        }
        Objects.requireNonNull(startExtractor, "startExtractor e obrigatorio.");
        Objects.requireNonNull(endExtractor, "endExtractor e obrigatorio.");
        Objects.requireNonNull(endMutator, "endMutator e obrigatorio.");
        if (existingEntities == null || existingEntities.isEmpty()) {
            return Optional.empty();
        }

        List<E> openEntities = existingEntities.stream()
                .filter(Objects::nonNull)
                .filter(entity -> startExtractor.apply(entity) != null && endExtractor.apply(entity) == null)
                .sorted(Comparator.comparing(startExtractor))
                .toList();

        if (openEntities.isEmpty()) {
            return Optional.empty();
        }
        if (openEntities.size() > 1) {
            throw new BusinessException(
                    "Foram encontradas múltiplas vigências abertas simultâneas; regularize a cadeia temporal antes de abrir novo período."
            );
        }

        E previousOpen = openEntities.get(0);
        LocalDate previousStart = startExtractor.apply(previousOpen);
        if (!novaDtini.isAfter(previousStart)) {
            throw new BusinessException(
                    "A nova vigência iniciada em " + novaDtini
                            + " deve ser estritamente posterior ao início da vigência aberta atual ("
                            + previousStart + ") para permitir o encerramento automático."
            );
        }

        endMutator.accept(previousOpen, novaDtini.minusDays(1));
        return Optional.of(previousOpen);
    }

    /**
     * Encerra automaticamente a vigencia aberta anterior (quando aplicavel) e valida que o novo
     * intervalo do candidato nao se sobrepoe a nenhum periodo existente na cadeia temporal.
     *
     * <p>Regras de integridade corporativa:</p>
     * <ul>
     *   <li>Periodos historicos fechados ({@code dtfim != null}) inteiramente anteriores a vigencia
     *       aberta atual sao validados contra sobreposicao sem encerrar indevidamente a vigencia aberta;</li>
     *   <li>A mutacao {@code endMutator} na vigencia aberta anterior so e aplicada apos confirmar que
     *       nao ha sobreposicao com nenhum outro registro da serie, evitando efeitos colaterais parciais.</li>
     * </ul>
     */
    public static <E, ID> Optional<E> prepareAndValidateInsert(
            E candidate,
            Collection<E> existingEntities,
            Function<E, ID> idExtractor,
            Function<E, LocalDate> startExtractor,
            Function<E, LocalDate> endExtractor,
            BiConsumer<E, LocalDate> endMutator
    ) {
        Objects.requireNonNull(candidate, "Entidade candidata nao pode ser nula.");
        Objects.requireNonNull(startExtractor, "startExtractor e obrigatorio.");
        Objects.requireNonNull(endExtractor, "endExtractor e obrigatorio.");
        Objects.requireNonNull(endMutator, "endMutator e obrigatorio.");

        LocalDate candidateStart = startExtractor.apply(candidate);
        LocalDate candidateEnd = endExtractor.apply(candidate);
        validateRange(candidateStart, candidateEnd);

        if (existingEntities == null || existingEntities.isEmpty()) {
            return Optional.empty();
        }

        ID candidateId = idExtractor != null ? idExtractor.apply(candidate) : null;
        List<E> peers = existingEntities.stream()
                .filter(Objects::nonNull)
                .filter(existing -> !isSameIdentity(candidate, candidateId, existing, idExtractor))
                .toList();

        List<E> openEntities = peers.stream()
                .filter(entity -> startExtractor.apply(entity) != null && endExtractor.apply(entity) == null)
                .sorted(Comparator.comparing(startExtractor))
                .toList();

        if (openEntities.size() > 1) {
            throw new BusinessException(
                    "Foram encontradas múltiplas vigências abertas simultâneas; regularize a cadeia temporal antes de abrir novo período."
            );
        }

        E openToClose = null;
        if (openEntities.size() == 1) {
            E previousOpen = openEntities.get(0);
            LocalDate previousStart = startExtractor.apply(previousOpen);
            if (candidateStart.isAfter(previousStart)) {
                openToClose = previousOpen;
            } else if (candidateEnd == null) {
                throw new BusinessException(
                        "A nova vigência iniciada em " + candidateStart
                                + " deve ser estritamente posterior ao início da vigência aberta atual ("
                                + previousStart + ") para permitir o encerramento automático."
                );
            }
        }

        final E targetOpenToClose = openToClose;
        List<E> peersForOverlapCheck = targetOpenToClose == null
                ? peers
                : peers.stream().filter(peer -> peer != targetOpenToClose).toList();

        validateNoOverlap(candidateStart, candidateEnd, peersForOverlapCheck, startExtractor, endExtractor);

        if (targetOpenToClose != null) {
            endMutator.accept(targetOpenToClose, candidateStart.minusDays(1));
            return Optional.of(targetOpenToClose);
        }
        return Optional.empty();
    }

    /**
     * Sobrecarga por convencao para entidades que implementam {@link MutableEffectiveDatedInterval}
     * ou possuem propriedades JavaBeans {@code id}, {@code dtini} e {@code dtfim}.
     */
    public static <E> Optional<E> prepareAndValidateInsert(E candidate, Collection<E> existingEntities) {
        return prepareAndValidateInsert(
                candidate,
                existingEntities,
                entity -> readProperty(entity, DEFAULT_ID_FIELD, Object.class),
                entity -> entity instanceof EffectiveDatedInterval interval
                        ? interval.getDtini()
                        : readProperty(entity, DEFAULT_START_FIELD, LocalDate.class),
                entity -> entity instanceof EffectiveDatedInterval interval
                        ? interval.getDtfim()
                        : readProperty(entity, DEFAULT_END_FIELD, LocalDate.class),
                (entity, newEnd) -> {
                    if (entity instanceof MutableEffectiveDatedInterval mutable) {
                        mutable.setDtfim(newEnd);
                    } else {
                        writeProperty(entity, DEFAULT_END_FIELD, newEnd);
                    }
                }
        );
    }

    /**
     * Cria uma {@link Specification} JPA Criteria para filtrar registros vigentes na data informada
     * ({@code dtini <= asOfDate AND (dtfim IS NULL OR dtfim >= asOfDate)}) usando os campos padrao
     * {@code dtini} e {@code dtfim}.
     */
    public static <E> Specification<E> vigenteEm(LocalDate asOfDate) {
        return vigenteEm(asOfDate, DEFAULT_START_FIELD, DEFAULT_END_FIELD);
    }

    /**
     * Cria uma {@link Specification} JPA Criteria para filtrar registros vigentes na data informada
     * ({@code startField <= asOfDate AND (endField IS NULL OR endField >= asOfDate)}).
     *
     * <p>Quando {@code asOfDate} for {@code null}, retorna {@code cb.conjunction()} (sem restricao).</p>
     */
    public static <E> Specification<E> vigenteEm(LocalDate asOfDate, String startField, String endField) {
        String effectiveStartField = startField == null || startField.isBlank() ? DEFAULT_START_FIELD : startField.trim();
        String effectiveEndField = endField == null || endField.isBlank() ? DEFAULT_END_FIELD : endField.trim();
        return (root, query, cb) -> {
            if (asOfDate == null) {
                return cb.conjunction();
            }
            @SuppressWarnings("unchecked")
            Path<LocalDate> startPath = (Path<LocalDate>) JpaCriteriaPathSupport.resolvePath(root, effectiveStartField, true);
            @SuppressWarnings("unchecked")
            Path<LocalDate> endPath = (Path<LocalDate>) JpaCriteriaPathSupport.resolvePath(root, effectiveEndField, true);
            return cb.and(
                    cb.lessThanOrEqualTo(startPath, asOfDate),
                    cb.or(cb.isNull(endPath), cb.greaterThanOrEqualTo(endPath, asOfDate))
            );
        };
    }

    /**
     * Alias em ingles para {@link #vigenteEm(LocalDate)}.
     */
    public static <E> Specification<E> asOfDate(LocalDate asOfDate) {
        return vigenteEm(asOfDate);
    }

    /**
     * Alias em ingles para {@link #vigenteEm(LocalDate, String, String)}.
     */
    public static <E> Specification<E> asOfDate(LocalDate asOfDate, String startField, String endField) {
        return vigenteEm(asOfDate, startField, endField);
    }

    /**
     * Resolve o estado temporal canonico ({@code FUTURO}, {@code ENCERRADO} ou {@code VIGENTE})
     * para uso em {@code ResourceStateSnapshot}.
     */
    public static String resolveTemporalState(LocalDate dtini, LocalDate dtfim, LocalDate referenceDate) {
        validateRange(dtini, dtfim);
        LocalDate effectiveRef = referenceDate != null ? referenceDate : LocalDate.now();
        if (dtini.isAfter(effectiveRef)) {
            return "FUTURO";
        }
        if (dtfim != null && dtfim.isBefore(effectiveRef)) {
            return "ENCERRADO";
        }
        return "VIGENTE";
    }

    /**
     * Verifica se dois periodos adjacentes sao estritamente contiguos em calendario
     * ({@code previousDtfim.plusDays(1).equals(nextDtini)}).
     */
    public static boolean isContiguous(LocalDate previousDtfim, LocalDate nextDtini) {
        if (previousDtfim == null || nextDtini == null) {
            return false;
        }
        return previousDtfim.plusDays(1).equals(nextDtini);
    }

    /**
     * Identifica lacunas (gaps) na cadeia temporal de uma serie de entidades, retornando os
     * intervalos descobertos {@code [dtiniLacuna, dtfimLacuna]} em ordem cronologica.
     */
    public static <E> List<ValidityInterval> findGaps(
            Collection<E> entities,
            Function<E, LocalDate> startExtractor,
            Function<E, LocalDate> endExtractor
    ) {
        Objects.requireNonNull(startExtractor, "startExtractor e obrigatorio.");
        Objects.requireNonNull(endExtractor, "endExtractor e obrigatorio.");
        if (entities == null || entities.size() < 2) {
            return List.of();
        }
        List<ValidityInterval> sorted = entities.stream()
                .filter(Objects::nonNull)
                .filter(e -> startExtractor.apply(e) != null)
                .map(e -> new ValidityInterval(startExtractor.apply(e), endExtractor.apply(e)))
                .sorted(Comparator.comparing(ValidityInterval::dtini))
                .toList();

        if (sorted.size() < 2) {
            return List.of();
        }

        List<ValidityInterval> gaps = new ArrayList<>();
        LocalDate coveredUntil = sorted.get(0).dtfim();

        for (int i = 1; i < sorted.size(); i++) {
            if (coveredUntil == null) {
                break;
            }
            ValidityInterval current = sorted.get(i);
            if (current.dtini().isAfter(coveredUntil.plusDays(1))) {
                gaps.add(new ValidityInterval(coveredUntil.plusDays(1), current.dtini().minusDays(1)));
            }
            if (current.dtfim() == null) {
                coveredUntil = null;
            } else if (current.dtfim().isAfter(coveredUntil)) {
                coveredUntil = current.dtfim();
            }
        }
        return List.copyOf(gaps);
    }

    /**
     * Sobrecarga por convencao para localizar lacunas na cadeia temporal de entidades que implementam
     * {@link EffectiveDatedInterval} ou possuem propriedades {@code dtini} e {@code dtfim}.
     */
    public static <E> List<ValidityInterval> findGaps(Collection<E> entities) {
        return findGaps(
                entities,
                entity -> entity instanceof EffectiveDatedInterval interval
                        ? interval.getDtini()
                        : readProperty(entity, DEFAULT_START_FIELD, LocalDate.class),
                entity -> entity instanceof EffectiveDatedInterval interval
                        ? interval.getDtfim()
                        : readProperty(entity, DEFAULT_END_FIELD, LocalDate.class)
        );
    }

    private static <E, ID> boolean isSameIdentity(
            E candidate,
            ID candidateId,
            E existing,
            Function<E, ID> idExtractor
    ) {
        if (candidate == existing) {
            return true;
        }
        if (candidateId == null || idExtractor == null) {
            return false;
        }
        ID existingId = idExtractor.apply(existing);
        return existingId != null && Objects.equals(candidateId, existingId);
    }

    private static String formatInterval(LocalDate dtini, LocalDate dtfim) {
        return dtini + " .. " + (dtfim != null ? dtfim : "em aberto");
    }

    private static <T> T readProperty(Object target, String propertyName, Class<T> expectedType) {
        BeanWrapperImpl wrapper = new BeanWrapperImpl(target);
        if (!wrapper.isReadableProperty(propertyName)) {
            return null;
        }
        Object raw = wrapper.getPropertyValue(propertyName);
        return expectedType.isInstance(raw) ? expectedType.cast(raw) : null;
    }

    private static void writeProperty(Object target, String propertyName, Object value) {
        BeanWrapperImpl wrapper = new BeanWrapperImpl(target);
        if (!wrapper.isWritableProperty(propertyName)) {
            throw new IllegalArgumentException(
                    "Propriedade '" + propertyName + "' nao e gravavel em " + target.getClass().getName()
            );
        }
        wrapper.setPropertyValue(propertyName, value);
    }
}
