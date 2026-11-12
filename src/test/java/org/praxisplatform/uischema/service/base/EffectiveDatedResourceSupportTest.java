package org.praxisplatform.uischema.service.base;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.rest.exceptionhandler.exception.BusinessException;
import org.praxisplatform.uischema.service.base.EffectiveDatedResourceSupport.MutableEffectiveDatedInterval;
import org.praxisplatform.uischema.service.base.EffectiveDatedResourceSupport.ValidityInterval;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EffectiveDatedResourceSupportTest {

    static class SampleTemporalEntity implements MutableEffectiveDatedInterval {
        private Long id;
        private LocalDate dtini;
        private LocalDate dtfim;

        SampleTemporalEntity(Long id, LocalDate dtini, LocalDate dtfim) {
            this.id = id;
            this.dtini = dtini;
            this.dtfim = dtfim;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        @Override
        public LocalDate getDtini() {
            return dtini;
        }

        public void setDtini(LocalDate dtini) {
            this.dtini = dtini;
        }

        @Override
        public LocalDate getDtfim() {
            return dtfim;
        }

        @Override
        public void setDtfim(LocalDate dtfim) {
            this.dtfim = dtfim;
        }
    }

    @Test
    void rejectsNullStartOrInvertedValidityRange() {
        assertThrows(BusinessException.class, () -> EffectiveDatedResourceSupport.validateRange(null, LocalDate.of(2025, 1, 10)));
        assertThrows(
                BusinessException.class,
                () -> EffectiveDatedResourceSupport.validateRange(LocalDate.of(2025, 6, 1), LocalDate.of(2025, 5, 31))
        );
        assertThrows(
                BusinessException.class,
                () -> new ValidityInterval(LocalDate.of(2025, 6, 1), LocalDate.of(2025, 5, 31))
        );
    }

    @Test
    void detectsOverlapDeterministicallyForClosedAndOpenEndedIntervals() {
        // Adjacent non-overlapping intervals: [2025-01-01, 2025-05-31] and [2025-06-01, null]
        assertFalse(EffectiveDatedResourceSupport.overlaps(
                LocalDate.of(2025, 1, 1),
                LocalDate.of(2025, 5, 31),
                LocalDate.of(2025, 6, 1),
                null
        ));

        // Boundary-touching intervals overlap (inclusive endpoints): [2025-01-01, 2025-06-01] and [2025-06-01, 2025-12-31]
        assertTrue(EffectiveDatedResourceSupport.overlaps(
                LocalDate.of(2025, 1, 1),
                LocalDate.of(2025, 6, 1),
                LocalDate.of(2025, 6, 1),
                LocalDate.of(2025, 12, 31)
        ));

        // Two open-ended intervals always overlap
        assertTrue(EffectiveDatedResourceSupport.overlaps(
                LocalDate.of(2024, 1, 1),
                null,
                LocalDate.of(2025, 1, 1),
                null
        ));
    }

    @Test
    void validatesNoOverlapAndExcludesSameEntityIdentityOnUpdate() {
        SampleTemporalEntity first = new SampleTemporalEntity(1L, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31));
        SampleTemporalEntity second = new SampleTemporalEntity(2L, LocalDate.of(2025, 1, 1), null);
        List<SampleTemporalEntity> existing = List.of(first, second);

        // Updating 'first' within its own non-overlapping window succeeds
        SampleTemporalEntity updatedFirst = new SampleTemporalEntity(1L, LocalDate.of(2024, 2, 1), LocalDate.of(2024, 12, 31));
        EffectiveDatedResourceSupport.validateNoOverlap(updatedFirst, existing);

        // New entity overlapping 'first' throws BusinessException
        SampleTemporalEntity conflicting = new SampleTemporalEntity(null, LocalDate.of(2024, 6, 1), LocalDate.of(2024, 8, 1));
        BusinessException ex = assertThrows(
                BusinessException.class,
                () -> EffectiveDatedResourceSupport.validateNoOverlap(conflicting, existing)
        );
        assertTrue(ex.getMessage().contains("Sobreposição de vigência"));
    }

    @Test
    void automaticallyClosesPreviousOpenValidityWithNewStartMinusOneDay() {
        SampleTemporalEntity closedHistory = new SampleTemporalEntity(
                1L,
                LocalDate.of(2024, 1, 1),
                LocalDate.of(2024, 12, 31)
        );
        SampleTemporalEntity openCurrent = new SampleTemporalEntity(
                2L,
                LocalDate.of(2025, 1, 1),
                null
        );
        List<SampleTemporalEntity> existing = new ArrayList<>(List.of(closedHistory, openCurrent));

        SampleTemporalEntity newPeriod = new SampleTemporalEntity(
                null,
                LocalDate.of(2025, 7, 1),
                null
        );

        Optional<SampleTemporalEntity> closed = EffectiveDatedResourceSupport.prepareAndValidateInsert(
                newPeriod,
                existing
        );

        assertTrue(closed.isPresent());
        assertSame(openCurrent, closed.get());
        assertEquals(LocalDate.of(2025, 6, 30), openCurrent.getDtfim());
        assertNull(newPeriod.getDtfim());
    }

    @Test
    void rejectsAutoCloseWhenNewValidityStartsOnOrBeforeOpenValidityStart() {
        SampleTemporalEntity openCurrent = new SampleTemporalEntity(
                2L,
                LocalDate.of(2025, 6, 1),
                null
        );
        SampleTemporalEntity retroactiveCandidate = new SampleTemporalEntity(
                null,
                LocalDate.of(2025, 6, 1),
                null
        );

        assertThrows(
                BusinessException.class,
                () -> EffectiveDatedResourceSupport.prepareAndValidateInsert(retroactiveCandidate, List.of(openCurrent))
        );
        assertNull(openCurrent.getDtfim());
    }

    @Test
    void allowsRetroactiveClosedHistoricalPeriodPriorToOpenValidityWithoutClosingOpenValidity() {
        SampleTemporalEntity openCurrent = new SampleTemporalEntity(
                2L,
                LocalDate.of(2025, 1, 1),
                null
        );
        SampleTemporalEntity closedRetroactive = new SampleTemporalEntity(
                null,
                LocalDate.of(2024, 1, 1),
                LocalDate.of(2024, 12, 31)
        );

        Optional<SampleTemporalEntity> closed = EffectiveDatedResourceSupport.prepareAndValidateInsert(
                closedRetroactive,
                List.of(openCurrent)
        );

        assertTrue(closed.isEmpty());
        assertNull(openCurrent.getDtfim(), "Vigência aberta posterior não deve ser encerrada ao inserir período pretérito fechado.");
    }

    @Test
    void doesNotMutateOpenValidityWhenCandidateConflictsWithAnotherExistingPeriod() {
        SampleTemporalEntity openCurrent = new SampleTemporalEntity(
                1L,
                LocalDate.of(2025, 1, 1),
                null
        );
        SampleTemporalEntity scheduledFuture = new SampleTemporalEntity(
                2L,
                LocalDate.of(2025, 9, 1),
                LocalDate.of(2025, 12, 31)
        );
        SampleTemporalEntity conflictingCandidate = new SampleTemporalEntity(
                null,
                LocalDate.of(2025, 6, 1),
                LocalDate.of(2025, 10, 15)
        );

        assertThrows(
                BusinessException.class,
                () -> EffectiveDatedResourceSupport.prepareAndValidateInsert(
                        conflictingCandidate,
                        List.of(openCurrent, scheduledFuture)
                )
        );
        assertNull(openCurrent.getDtfim(), "Mutação de encerramento não deve ocorrer quando a validação de sobreposição falha.");
    }

    @Test
    void evaluatesContiguityAndFindsGapsInTemporalTimeline() {
        assertTrue(EffectiveDatedResourceSupport.isContiguous(
                LocalDate.of(2024, 12, 31),
                LocalDate.of(2025, 1, 1)
        ));
        assertFalse(EffectiveDatedResourceSupport.isContiguous(
                LocalDate.of(2024, 12, 30),
                LocalDate.of(2025, 1, 1)
        ));

        List<SampleTemporalEntity> timeline = List.of(
                new SampleTemporalEntity(1L, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 3, 31)),
                new SampleTemporalEntity(2L, LocalDate.of(2024, 4, 1), LocalDate.of(2024, 6, 30)),
                new SampleTemporalEntity(3L, LocalDate.of(2024, 7, 15), LocalDate.of(2024, 12, 31)),
                new SampleTemporalEntity(4L, LocalDate.of(2025, 2, 1), null)
        );

        List<ValidityInterval> gaps = EffectiveDatedResourceSupport.findGaps(timeline);
        assertEquals(2, gaps.size());
        assertEquals(new ValidityInterval(LocalDate.of(2024, 7, 1), LocalDate.of(2024, 7, 14)), gaps.get(0));
        assertEquals(new ValidityInterval(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 31)), gaps.get(1));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void buildsVigenteEmJpaSpecificationForAsOfDate() {
        LocalDate asOfDate = LocalDate.of(2025, 10, 15);
        Specification<SampleTemporalEntity> spec = EffectiveDatedResourceSupport.vigenteEm(asOfDate);

        Root<SampleTemporalEntity> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);

        Path startPath = mock(Path.class);
        Path endPath = mock(Path.class);
        when(root.get("dtini")).thenReturn(startPath);
        when(root.get("dtfim")).thenReturn(endPath);

        Predicate startLe = mock(Predicate.class);
        Predicate endNull = mock(Predicate.class);
        Predicate endGe = mock(Predicate.class);
        Predicate endOr = mock(Predicate.class);
        Predicate combined = mock(Predicate.class);

        when(cb.lessThanOrEqualTo(startPath, asOfDate)).thenReturn(startLe);
        when(cb.isNull(endPath)).thenReturn(endNull);
        when(cb.greaterThanOrEqualTo(endPath, asOfDate)).thenReturn(endGe);
        when(cb.or(endNull, endGe)).thenReturn(endOr);
        when(cb.and(startLe, endOr)).thenReturn(combined);

        Predicate result = spec.toPredicate(root, query, cb);

        assertNotNull(result);
        assertSame(combined, result);
        verify(cb).lessThanOrEqualTo(startPath, asOfDate);
        verify(cb).isNull(endPath);
        verify(cb).greaterThanOrEqualTo(endPath, asOfDate);
        verify(cb).or(endNull, endGe);
        verify(cb).and(startLe, endOr);
    }
}
