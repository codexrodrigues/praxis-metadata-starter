package org.praxisplatform.uischema.exporting;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.temporal.TemporalAccessor;
import java.util.List;

abstract class AbstractTabularCollectionExportEngine implements CollectionExportEngine {

    protected static final String DEFAULT_FILE_BASENAME = "collection-export";

    protected String columnKey(CollectionExportField field) {
        if (field == null) {
            return "";
        }
        if (field.key() != null && !field.key().isBlank()) {
            return field.key();
        }
        return field.valuePath() == null ? "" : field.valuePath();
    }

    protected String columnLabel(CollectionExportField field) {
        if (field == null) {
            return "";
        }
        if (field.label() != null && !field.label().isBlank()) {
            return field.label();
        }
        return columnKey(field);
    }

    protected String resolveFileName(CollectionExportRequest<?> request, String extension) {
        if (request != null && request.fileName() != null && !request.fileName().isBlank()) {
            return request.fileName();
        }
        return DEFAULT_FILE_BASENAME + "." + extension;
    }

    protected long rowCount(List<?> rows) {
        return rows == null ? 0L : rows.size();
    }

    protected Object normalizeValue(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof BigInteger integer) {
            return integer.toString();
        }
        if (value instanceof TemporalAccessor) {
            return value.toString();
        }
        return value;
    }
}
