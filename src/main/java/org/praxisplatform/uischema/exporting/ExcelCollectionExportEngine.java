package org.praxisplatform.uischema.exporting;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Engine canonico para XLSX tabular, preservando a mesma resolucao governada de campos dos
 * engines CSV/JSON.
 */
public class ExcelCollectionExportEngine extends AbstractTabularCollectionExportEngine {

    public static final String CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String DEFAULT_SHEET_NAME = "Export";
    private static final int EXCEL_SHEET_NAME_LIMIT = 31;
    private static final int MAX_AUTO_FIT_COLUMNS = 64;

    @Override
    public boolean supports(CollectionExportFormat format) {
        return format == CollectionExportFormat.EXCEL;
    }

    @Override
    public <T> CollectionExportResult export(
            CollectionExportRequest<?> request,
            List<T> rows,
            List<CollectionExportField> fields,
            CollectionExportValueResolver<T> valueResolver,
            Map<String, Object> metadata
    ) {
        CollectionExportExcelOptions options = request == null || request.formatOptions() == null
                ? null
                : request.formatOptions().excel();
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(resolveSheetName(options));
            CellStyle dateStyle = dateStyle(workbook, "dd/MM/yyyy");
            CellStyle dateTimeStyle = dateStyle(workbook, "dd/MM/yyyy HH:mm:ss");
            int rowIndex = 0;

            if (request == null || request.includeHeaders() != Boolean.FALSE) {
                Row header = sheet.createRow(rowIndex++);
                for (int columnIndex = 0; columnIndex < fields.size(); columnIndex++) {
                    writeStringCell(header.createCell(columnIndex), columnLabel(fields.get(columnIndex)));
                }
                if (Boolean.TRUE.equals(options == null ? null : options.freezeHeaders())) {
                    sheet.createFreezePane(0, 1);
                }
            }

            boolean typedCells = Boolean.TRUE.equals(options == null ? null : options.typedCells())
                    && (request == null || request.applyFormatting() != Boolean.TRUE);
            for (T sourceRow : rows) {
                Row excelRow = sheet.createRow(rowIndex++);
                for (int columnIndex = 0; columnIndex < fields.size(); columnIndex++) {
                    CollectionExportField field = fields.get(columnIndex);
                    Object value = valueResolver.resolve(sourceRow, field);
                    Cell cell = excelRow.createCell(columnIndex);
                    if (typedCells) {
                        writeTypedCell(cell, value, field, dateStyle, dateTimeStyle);
                    } else {
                        writeStringCell(cell, materializeValue(value, field, request));
                    }
                }
            }

            if (Boolean.TRUE.equals(options == null ? null : options.autoFitColumns())) {
                int limit = Math.min(fields.size(), MAX_AUTO_FIT_COLUMNS);
                for (int columnIndex = 0; columnIndex < limit; columnIndex++) {
                    sheet.autoSizeColumn(columnIndex);
                }
            }

            workbook.write(output);
            return new CollectionExportResult(
                    CollectionExportStatus.COMPLETED,
                    CollectionExportFormat.EXCEL,
                    request == null ? CollectionExportScope.AUTO : request.scope(),
                    output.toByteArray(),
                    resolveExcelFileName(request),
                    CONTENT_TYPE,
                    null,
                    null,
                    rowCount(rows),
                    List.of(),
                    metadata
            );
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to serialize collection export as XLSX", ex);
        }
    }

    private CellStyle dateStyle(Workbook workbook, String pattern) {
        CreationHelper helper = workbook.getCreationHelper();
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(helper.createDataFormat().getFormat(pattern));
        return style;
    }

    private void writeTypedCell(
            Cell cell,
            Object value,
            CollectionExportField field,
            CellStyle dateStyle,
            CellStyle dateTimeStyle
    ) {
        if (value == null) {
            cell.setBlank();
            return;
        }
        if (value instanceof Boolean bool) {
            cell.setCellValue(bool);
            return;
        }
        if (value instanceof BigDecimal decimal) {
            cell.setCellValue(decimal.doubleValue());
            return;
        }
        if (value instanceof BigInteger integer) {
            cell.setCellValue(integer.doubleValue());
            return;
        }
        if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
            return;
        }
        if (value instanceof LocalDate date) {
            cell.setCellValue(date);
            cell.setCellStyle(dateStyle);
            return;
        }
        if (value instanceof LocalDateTime dateTime) {
            cell.setCellValue(dateTime);
            cell.setCellStyle(dateTimeStyle);
            return;
        }
        if (value instanceof ZonedDateTime dateTime) {
            cell.setCellValue(dateTime.toLocalDateTime());
            cell.setCellStyle(dateTimeStyle);
            return;
        }
        if (value instanceof OffsetDateTime dateTime) {
            cell.setCellValue(dateTime.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime());
            cell.setCellStyle(dateTimeStyle);
            return;
        }
        writeStringCell(cell, normalizeValue(value));
    }

    private void writeStringCell(Cell cell, Object value) {
        String text = value == null ? "" : value.toString();
        if (startsWithFormula(text)) {
            text = "'" + text;
        }
        cell.setCellValue(text);
    }

    private boolean startsWithFormula(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        int index = 0;
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        if (index >= text.length()) {
            return false;
        }
        return List.of('=', '+', '-', '@').contains(text.charAt(index));
    }

    private String resolveSheetName(CollectionExportExcelOptions options) {
        String requested = options == null ? null : options.sheetName();
        String candidate = requested == null || requested.isBlank() ? DEFAULT_SHEET_NAME : requested.trim();
        candidate = candidate
                .replace('[', ' ')
                .replace(']', ' ')
                .replace(':', ' ')
                .replace('*', ' ')
                .replace('?', ' ')
                .replace('/', ' ')
                .replace('\\', ' ');
        candidate = candidate.trim();
        if (candidate.isBlank()) {
            candidate = DEFAULT_SHEET_NAME;
        }
        return candidate.length() > EXCEL_SHEET_NAME_LIMIT
                ? candidate.substring(0, EXCEL_SHEET_NAME_LIMIT).trim()
                : candidate;
    }

    private String resolveExcelFileName(CollectionExportRequest<?> request) {
        String fileName = resolveFileName(request, "xlsx");
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".xlsx")) {
            return fileName;
        }
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            return fileName.substring(0, dotIndex) + ".xlsx";
        }
        return fileName + ".xlsx";
    }
}
