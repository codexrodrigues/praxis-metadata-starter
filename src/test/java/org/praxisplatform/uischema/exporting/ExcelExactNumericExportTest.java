package org.praxisplatform.uischema.exporting;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ExcelExactNumericExportTest {
  static Stream<Arguments> exactTextNumbers() {
    return Stream.of(
        Arguments.of(new BigDecimal("123456789012345678901234567890.12345678")),
        Arguments.of(new BigDecimal("-123456789012345678901234567890.12345678")),
        Arguments.of(new BigDecimal("0.00000000000000012345678901234567890123")),
        Arguments.of(new BigDecimal("1E-999")),
        Arguments.of(new BigDecimal("1E-310")),
        Arguments.of(new BigDecimal("1E+308")),
        Arguments.of(new BigDecimal("1E+999")),
        Arguments.of(new BigInteger("99999999999999999999999999999999999999")),
        Arguments.of(new BigInteger("-99999999999999999999999999999999999999")),
        Arguments.of(9007199254740993L),
        Arguments.of(Long.MIN_VALUE), Arguments.of(Long.MAX_VALUE),
        Arguments.of(1000000000000001L));
  }

  @ParameterizedTest @MethodSource("exactTextNumbers")
  void numbersBeyondTheSpreadsheetPrecisionArePreservedAsText(Number value) throws Exception {
    try (var workbook = export(value, "number")) {
      var cell = workbook.getSheetAt(0).getRow(1).getCell(0);
      assertEquals(CellType.STRING, cell.getCellType());
      assertEquals(value instanceof BigDecimal decimal ? decimal.toPlainString() : value.toString(), cell.getStringCellValue());
    }
  }

  @Test void safeNumericCellsRemainNumeric() throws Exception {
    for (Number value : List.of(0, -42, 999999999999999L, new BigInteger("999999999999999"),
        new BigDecimal("123.45"), new BigDecimal("0.125"))) {
      try (var workbook = export(value, "number")) {
        var cell = workbook.getSheetAt(0).getRow(1).getCell(0);
        assertEquals(CellType.NUMERIC, cell.getCellType());
        assertEquals(value.doubleValue(), cell.getNumericCellValue());
      }
    }
  }

  @Test void declaredStringFieldsRemainTextualEvenForSmallNumericValues() throws Exception {
    for (Number value : List.of(new BigDecimal("-0.1250"), 123L, new BigDecimal("0.00"))) {
      try (var workbook = export(value, "string")) {
        var cell = workbook.getSheetAt(0).getRow(1).getCell(0);
        assertEquals(CellType.STRING, cell.getCellType());
        assertEquals(value.toString(), cell.getStringCellValue());
      }
    }
  }

  @Test void formulaLikeTextIsStillGuarded() throws Exception {
    try (var workbook = export(" =SUM(A1:A2)", "string")) {
      var cell = workbook.getSheetAt(0).getRow(1).getCell(0);
      assertEquals(CellType.STRING, cell.getCellType());
      assertEquals("' =SUM(A1:A2)", cell.getStringCellValue());
    }
  }

  private static XSSFWorkbook export(Object value, String type) throws Exception {
    var request = new CollectionExportRequest<>("table", "precision", "/test", CollectionExportFormat.EXCEL,
        CollectionExportScope.FILTERED, null, null, null, null, null, Map.of(), true, false,
        100, "exact.xlsx", new CollectionExportFormatOptions(null,
            new CollectionExportExcelOptions("Exact", false, false, true, false)), null, Map.of());
    var result = new ExcelCollectionExportEngine().export(request, List.of(value),
        List.of(new CollectionExportField("value", "Value", true, true, type, "value")),
        (row, field) -> row, Map.of());
    return new XSSFWorkbook(new ByteArrayInputStream(result.content()));
  }
}
