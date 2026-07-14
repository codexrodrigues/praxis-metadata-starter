package org.praxisplatform.uischema.service.base;

import jakarta.persistence.criteria.Path;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.time.ZoneId;

/** Builds an inclusive calendar-date predicate for a governed temporal property. */
final class ComparisonPeriodSpecifications {
    private ComparisonPeriodSpecifications() { }

    static <E> Specification<E> forPeriod(String propertyPath, LocalDate from, LocalDate to, String timezone) {
        return (root, query, cb) -> {
            Path<?> path = root;
            for (String segment : propertyPath.split("\\.")) path = path.get(segment);
            Class<?> type = path.getJavaType();
            if (type == LocalDate.class) return cb.between(path.as(LocalDate.class), from, to);
            java.time.ZonedDateTime start = from.atStartOfDay(ZoneId.of(timezone));
            java.time.ZonedDateTime end = to.plusDays(1).atStartOfDay(ZoneId.of(timezone));
            if (type == java.time.LocalDateTime.class) return cb.and(cb.greaterThanOrEqualTo(path.as(java.time.LocalDateTime.class), start.toLocalDateTime()), cb.lessThan(path.as(java.time.LocalDateTime.class), end.toLocalDateTime()));
            if (type == java.time.Instant.class) return cb.and(cb.greaterThanOrEqualTo(path.as(java.time.Instant.class), start.toInstant()), cb.lessThan(path.as(java.time.Instant.class), end.toInstant()));
            if (type == java.time.OffsetDateTime.class) return cb.and(cb.greaterThanOrEqualTo(path.as(java.time.OffsetDateTime.class), start.toOffsetDateTime()), cb.lessThan(path.as(java.time.OffsetDateTime.class), end.toOffsetDateTime()));
            if (type == java.time.ZonedDateTime.class) return cb.and(cb.greaterThanOrEqualTo(path.as(java.time.ZonedDateTime.class), start), cb.lessThan(path.as(java.time.ZonedDateTime.class), end));
            throw new IllegalArgumentException("Comparison period field must be LocalDate, LocalDateTime, Instant, OffsetDateTime, or ZonedDateTime: " + propertyPath);
        };
    }
}
