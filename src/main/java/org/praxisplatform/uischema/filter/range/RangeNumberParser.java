package org.praxisplatform.uischema.filter.range;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Parser numérico tolerante para payloads de filtro de range.
 *
 * <p>Objetivo: aceitar formatos comuns (incluindo locale/currency) sem
 * distorcer valores silenciosamente. Quando o texto é ambíguo ou inválido,
 * retorna {@code null}.</p>
 */
public final class RangeNumberParser {

    private RangeNumberParser() {
    }

    public static BigDecimal parse(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof BigDecimal bigDecimal) {
            return bigDecimal;
        }
        if (raw instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        return parse(String.valueOf(raw));
    }

    public static BigDecimal parse(CharSequence raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.toString().trim();
        if (text.isEmpty()) {
            return null;
        }

        String normalized = normalize(text);
        if (normalized == null || normalized.isEmpty()) {
            return null;
        }

        if (!isValidNumberToken(normalized)) {
            return null;
        }

        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String normalize(String raw) {
        String normalized = raw
                .replace("\u00A0", "")
                .replace("\u202F", "")
                .replace(" ", "")
                .replace("_", "")
                .replace("'", "");

        if (normalized.isEmpty()) {
            return null;
        }

        // Remoção defensiva de símbolos monetários e códigos ISO de moeda.
        normalized = normalized
                .replaceAll("[\\p{Sc}]", "")
                .replaceAll("(?i)\\b[A-Z]{3}\\b", "");

        // Cobre variantes sem separador entre código de moeda e número (ex: USD123,45, R123,45).
        normalized = normalized
                .replaceAll("(?i)^[a-z]{1,3}", "")
                .replaceAll("(?i)[a-z]{1,3}$", "");

        if (normalized.isEmpty() || hasUnsupportedAlphabeticToken(normalized)) {
            return null;
        }

        int exponentIndex = firstExponentIndex(normalized);
        String mantissa = exponentIndex >= 0 ? normalized.substring(0, exponentIndex) : normalized;
        String exponent = exponentIndex >= 0 ? normalized.substring(exponentIndex).toLowerCase(Locale.ROOT) : "";

        if (mantissa.isEmpty()) {
            return null;
        }

        String normalizedMantissa = normalizeMantissa(mantissa);
        if (normalizedMantissa == null || normalizedMantissa.isEmpty()) {
            return null;
        }

        return normalizedMantissa + exponent;
    }

    private static String normalizeMantissa(String mantissa) {
        int lastComma = mantissa.lastIndexOf(',');
        int lastDot = mantissa.lastIndexOf('.');

        if (lastComma >= 0 && lastDot >= 0) {
            if (lastComma > lastDot) {
                // Ex: 1.234,56 -> 1234.56
                mantissa = mantissa.replace(".", "");
                mantissa = replaceLast(mantissa, ',', '.');
            } else {
                // Ex: 1,234.56 -> 1234.56
                mantissa = mantissa.replace(",", "");
            }
        } else if (lastComma >= 0) {
            // Ex: 12,5 -> 12.5
            mantissa = replaceLast(mantissa, ',', '.');
        }

        if (!containsOnlyNumericSymbols(mantissa)) {
            return null;
        }
        return mantissa;
    }

    private static int firstExponentIndex(String value) {
        int lower = value.indexOf('e');
        int upper = value.indexOf('E');
        if (lower < 0) return upper;
        if (upper < 0) return lower;
        return Math.min(lower, upper);
    }

    private static String replaceLast(String source, char target, char replacement) {
        int index = source.lastIndexOf(target);
        if (index < 0) {
            return source;
        }
        return source.substring(0, index) + replacement + source.substring(index + 1);
    }

    private static boolean hasUnsupportedAlphabeticToken(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c) && c != 'e' && c != 'E') {
                return true;
            }
        }
        return false;
    }

    private static boolean containsOnlyNumericSymbols(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean supported = Character.isDigit(c) || c == '+' || c == '-' || c == '.';
            if (!supported) {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidNumberToken(String token) {
        return token.matches("^[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:e[+-]?\\d+)?$");
    }
}
