package org.praxisplatform.uischema.extension;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.FieldConfigProperties;
import org.praxisplatform.uischema.FieldControlType;
import org.praxisplatform.uischema.FieldDataType;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.filter.annotation.Filterable;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FilterableBetweenListDetectionTest {

    private CustomOpenApiResolver resolver;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        resolver = new CustomOpenApiResolver(objectMapper);
    }

    private UISchema defaultUISchema() {
        return defaultUISchema(org.praxisplatform.uischema.NumericFormat.INTEGER);
    }

    private UISchema defaultUISchema(org.praxisplatform.uischema.NumericFormat numericFormat) {
        return new UISchema() {
            @Override
            public Class<? extends Annotation> annotationType() { return UISchema.class; }
            @Override public String description() { return ""; }
            @Override public String example() { return ""; }
            @Override public String name() { return ""; }
            @Override public String label() { return ""; }
            @Override public FieldDataType type() { return FieldDataType.TEXT; }
            @Override public FieldControlType controlType() { return FieldControlType.AUTO; }
            @Override public String placeholder() { return ""; }
            @Override public String defaultValue() { return ""; }
            @Override public String group() { return ""; }
            @Override public int order() { return 0; }
            @Override public String width() { return ""; }
            @Override public boolean isFlex() { return false; }
            @Override public String displayOrientation() { return ""; }
            @Override public boolean disabled() { return false; }
            @Override public boolean readOnly() { return false; }
            @Override public boolean multiple() { return false; }
            @Override public boolean editable() { return true; }
            @Override public String validationMode() { return ""; }
            @Override public boolean unique() { return false; }
            @Override public String mask() { return ""; }
            @Override public boolean sortable() { return true; }
            @Override public String conditionalRequired() { return ""; }
            @Override public String viewOnlyStyle() { return ""; }
            @Override public String validationTriggers() { return ""; }
            @Override public boolean hidden() { return false; }
            @Override public boolean tableHidden() { return false; }
            @Override public boolean formHidden() { return false; }
            @Override public boolean filterable() { return false; }
            @Override public String conditionalDisplay() { return ""; }
            @Override public String dependentField() { return ""; }
            @Override public boolean resetOnDependentChange() { return false; }
            @Override public boolean inlineEditing() { return false; }
            @Override public String transformValueFunction() { return ""; }
            @Override public int debounceTime() { return 0; }
            @Override public String helpText() { return ""; }
            @Override public String hint() { return ""; }
            @Override public String hiddenCondition() { return ""; }
            @Override public String tooltipOnHover() { return ""; }
            @Override public String icon() { return ""; }
            @Override public org.praxisplatform.uischema.IconPosition iconPosition() { return org.praxisplatform.uischema.IconPosition.LEFT; }
            @Override public String iconSize() { return ""; }
            @Override public String iconColor() { return ""; }
            @Override public String iconClass() { return ""; }
            @Override public String iconStyle() { return ""; }
            @Override public String iconFontSize() { return ""; }
            @Override public String valueField() { return ""; }
            @Override public String displayField() { return ""; }
            @Override public String endpoint() { return ""; }
            @Override public String emptyOptionText() { return ""; }
            @Override public String options() { return ""; }
            @Override public String filter() { return ""; }
            @Override public String filterOptions() { return ""; }
            @Override public String filterControlType() { return ""; }
            @Override public org.praxisplatform.uischema.NumericFormat numericFormat() { return numericFormat; }
            @Override public String numericStep() { return ""; }
            @Override public String numericMin() { return ""; }
            @Override public String numericMax() { return ""; }
            @Override public String numericMaxLength() { return ""; }
            @Override public boolean required() { return false; }
            @Override public int minLength() { return 0; }
            @Override public int maxLength() { return 0; }
            @Override public String min() { return ""; }
            @Override public String max() { return ""; }
            @Override public org.praxisplatform.uischema.ValidationPattern pattern() { return org.praxisplatform.uischema.ValidationPattern.CUSTOM; }
            @Override public String requiredMessage() { return ""; }
            @Override public String minLengthMessage() { return ""; }
            @Override public String maxLengthMessage() { return ""; }
            @Override public String patternMessage() { return ""; }
            @Override public String rangeMessage() { return ""; }
            @Override public String customValidator() { return ""; }
            @Override public String asyncValidator() { return ""; }
            @Override public int minWords() { return 0; }
            @Override public org.praxisplatform.uischema.AllowedFileTypes allowedFileTypes() { return org.praxisplatform.uischema.AllowedFileTypes.ALL; }
            @Override public String maxFileSize() { return ""; }
            @Override public ExtensionProperty[] extraProperties() { return new ExtensionProperty[0]; }
        };
    }

    private Filterable filterable(Filterable.FilterOperation operation) {
        return new Filterable() {
            @Override
            public Class<? extends Annotation> annotationType() { return Filterable.class; }
            @Override
            public FilterOperation operation() { return operation; }
            @Override
            public String relation() { return ""; }
        };
    }

    private Filterable betweenFilterable() {
        return filterable(Filterable.FilterOperation.BETWEEN);
    }

    private Schema<?> numericRangeSchema(String name, String itemFormat) {
        Schema<?> schema = new Schema<>();
        schema.setName(name);
        schema.setType("array");
        Schema<?> item = new Schema<>();
        item.setType("number");
        item.setFormat(itemFormat);
        schema.setItems(item);
        return schema;
    }

    private void assertNonExclusiveRangeContract(Schema<?> schema, String lowerBoundName, String upperBoundName) {
        assertNull(schema.getType());
        assertNotNull(schema.getOneOf());
        assertEquals(2, schema.getOneOf().size());

        Schema<?> arrayVariant = schema.getOneOf().stream()
                .filter(candidate -> "array".equals(candidate.getType()))
                .findFirst()
                .orElseThrow();
        assertEquals(1, arrayVariant.getMinItems());
        assertEquals(2, arrayVariant.getMaxItems());
        assertNotNull(arrayVariant.getItems());
        assertEquals(Boolean.TRUE, arrayVariant.getItems().getNullable());
        assertNotNull(arrayVariant.getAnyOf());
        assertEquals(2, arrayVariant.getAnyOf().size());
        assertNotNull(arrayVariant.getNot());

        Schema<?> objectVariant = schema.getOneOf().stream()
                .filter(candidate -> "object".equals(candidate.getType()))
                .findFirst()
                .orElseThrow();
        assertEquals(1, objectVariant.getMinProperties());
        assertNotNull(objectVariant.getAnyOf());
        assertEquals(2, objectVariant.getAnyOf().size());
        assertNull(objectVariant.getRequired());
        assertTrue(objectVariant.getAnyOf().stream().anyMatch(variant ->
                variant.getRequired() != null && variant.getRequired().contains(lowerBoundName)));
        assertTrue(objectVariant.getAnyOf().stream().anyMatch(variant ->
                variant.getRequired() != null && variant.getRequired().contains(upperBoundName)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> applyAnnotations(Schema<?> schema, Annotation[] annotations) {
        resolver.applyBeanValidatorAnnotations(schema, annotations, null, true);
        return (Map<String, Object>) schema.getExtensions().get("x-ui");
    }

    @Test
    void dateListBetweenSetsDateRange() {
        Schema<?> schema = new Schema<>();
        schema.setName("periodoFiltro");
        schema.setType("array");
        Schema<?> item = new Schema<>();
        item.setType("string");
        item.setFormat("date");
        schema.setItems(item);

        Map<String, Object> xUi = applyAnnotations(schema, new Annotation[]{defaultUISchema(), betweenFilterable()});
        assertEquals(FieldControlType.DATE_RANGE.getValue(), xUi.get(FieldConfigProperties.CONTROL_TYPE.getValue()));
    }

    @Test
    void dateTimeListBetweenSetsDateTimeRange() {
        Schema<?> schema = new Schema<>();
        schema.setName("periodoFiltro");
        schema.setType("array");
        Schema<?> item = new Schema<>();
        item.setType("string");
        item.setFormat("date-time");
        schema.setItems(item);

        Map<String, Object> xUi = applyAnnotations(schema, new Annotation[]{defaultUISchema(), betweenFilterable()});
        assertEquals(FieldControlType.DATE_TIME_RANGE.getValue(), xUi.get(FieldConfigProperties.CONTROL_TYPE.getValue()));
    }

    @Test
    void dateListBetweenWorksWithoutFiltroSuffix() {
        Schema<?> schema = new Schema<>();
        schema.setName("periodo");
        schema.setType("array");
        Schema<?> item = new Schema<>();
        item.setType("string");
        item.setFormat("date");
        schema.setItems(item);

        Map<String, Object> xUi = applyAnnotations(schema, new Annotation[]{defaultUISchema(), betweenFilterable()});
        assertEquals(FieldControlType.DATE_RANGE.getValue(), xUi.get(FieldConfigProperties.CONTROL_TYPE.getValue()));
    }

    @Test
    void dateTimeListBetweenWorksWithoutFiltroSuffix() {
        Schema<?> schema = new Schema<>();
        schema.setName("periodo");
        schema.setType("array");
        Schema<?> item = new Schema<>();
        item.setType("string");
        item.setFormat("date-time");
        schema.setItems(item);

        Map<String, Object> xUi = applyAnnotations(schema, new Annotation[]{defaultUISchema(), betweenFilterable()});
        assertEquals(FieldControlType.DATE_TIME_RANGE.getValue(), xUi.get(FieldConfigProperties.CONTROL_TYPE.getValue()));
    }

    @Test
    void numericListBetweenSetsRangeSlider() {
        Schema<?> schema = new Schema<>();
        schema.setName("pontuacaoRange");
        schema.setType("array");
        Schema<?> item = new Schema<>();
        item.setType("number");
        schema.setItems(item);

        Map<String, Object> xUi = applyAnnotations(schema, new Annotation[]{defaultUISchema(), betweenFilterable()});
        assertEquals(FieldControlType.RANGE_SLIDER.getValue(), xUi.get(FieldConfigProperties.CONTROL_TYPE.getValue()));
        assertEquals("range", xUi.get("mode"));
    }

    @Test
    void percentNumericListBetweenSetsRangeSliderAndPercentDefaults() {
        Schema<?> schema = new Schema<>();
        schema.setName("taxaRange");
        schema.setType("array");
        Schema<?> item = new Schema<>();
        item.setType("number");
        item.setFormat("percent");
        schema.setItems(item);

        Map<String, Object> xUi = applyAnnotations(schema, new Annotation[]{defaultUISchema(), betweenFilterable()});
        assertEquals(FieldControlType.RANGE_SLIDER.getValue(), xUi.get(FieldConfigProperties.CONTROL_TYPE.getValue()));
        assertEquals("range", xUi.get("mode"));
        assertEquals("percent", xUi.get(FieldConfigProperties.NUMERIC_FORMAT.getValue()));
        assertEquals("0.01", xUi.get(FieldConfigProperties.NUMERIC_STEP.getValue()));
        assertEquals("0", String.valueOf(xUi.get(FieldConfigProperties.NUMERIC_MIN.getValue())));
        assertEquals("100", String.valueOf(xUi.get(FieldConfigProperties.NUMERIC_MAX.getValue())));
    }

    @Test
    void monetaryListBetweenSetsPriceRangeWhenNumericFormatCurrency() {
        Schema<?> schema = new Schema<>();
        schema.setName("valorRange");
        schema.setType("array");
        Schema<?> item = new Schema<>();
        item.setType("number");
        schema.setItems(item);

        Map<String, Object> xUi = applyAnnotations(schema, new Annotation[]{
                defaultUISchema(org.praxisplatform.uischema.NumericFormat.CURRENCY),
                betweenFilterable()
        });
        assertEquals(FieldControlType.PRICE_RANGE.getValue(), xUi.get(FieldConfigProperties.CONTROL_TYPE.getValue()));
    }

    @Test
    void monetaryListBetweenSetsPriceRangeWhenItemFormatCurrency() {
        Schema<?> schema = new Schema<>();
        schema.setName("intervalo");
        schema.setType("array");
        Schema<?> item = new Schema<>();
        item.setType("number");
        item.setFormat("currency");
        schema.setItems(item);

        Map<String, Object> xUi = applyAnnotations(schema, new Annotation[]{defaultUISchema(), betweenFilterable()});
        assertEquals(FieldControlType.PRICE_RANGE.getValue(), xUi.get(FieldConfigProperties.CONTROL_TYPE.getValue()));
    }

    @Test
    void numericListNotBetweenSetsRangeSlider() {
        Schema<?> schema = new Schema<>();
        schema.setName("idadeRange");
        schema.setType("array");
        Schema<?> item = new Schema<>();
        item.setType("integer");
        schema.setItems(item);

        Map<String, Object> xUi = applyAnnotations(schema, new Annotation[]{
                defaultUISchema(),
                filterable(Filterable.FilterOperation.NOT_BETWEEN)
        });
        assertEquals(FieldControlType.RANGE_SLIDER.getValue(), xUi.get(FieldConfigProperties.CONTROL_TYPE.getValue()));
        assertEquals("range", xUi.get("mode"));
    }

    @Test
    void monetaryListOutsideRangeSetsPriceRange() {
        Schema<?> schema = new Schema<>();
        schema.setName("salarioRange");
        schema.setType("array");
        Schema<?> item = new Schema<>();
        item.setType("number");
        schema.setItems(item);

        Map<String, Object> xUi = applyAnnotations(schema, new Annotation[]{
                defaultUISchema(org.praxisplatform.uischema.NumericFormat.CURRENCY),
                filterable(Filterable.FilterOperation.OUTSIDE_RANGE)
        });
        assertEquals(FieldControlType.PRICE_RANGE.getValue(), xUi.get(FieldConfigProperties.CONTROL_TYPE.getValue()));
    }

    @Test
    void monetaryRangePublishesOneOfArrayAndCanonicalObjectContract() {
        Schema<?> schema = new Schema<>();
        schema.setName("salaryRange");
        schema.setType("array");
        Schema<?> item = new Schema<>();
        item.setType("number");
        item.setFormat("currency");
        schema.setItems(item);

        applyAnnotations(schema, new Annotation[]{defaultUISchema(), betweenFilterable()});

        assertNull(schema.getType());
        assertNotNull(schema.getOneOf());
        assertEquals(2, schema.getOneOf().size());

        Schema<?> arrayVariant = schema.getOneOf().stream()
                .filter(candidate -> "array".equals(candidate.getType()))
                .findFirst()
                .orElseThrow();
        assertEquals(1, arrayVariant.getMinItems());
        assertEquals(2, arrayVariant.getMaxItems());
        assertNotNull(arrayVariant.getItems());
        assertEquals(Boolean.TRUE, arrayVariant.getItems().getNullable());
        assertNotNull(arrayVariant.getAnyOf());
        assertEquals(2, arrayVariant.getAnyOf().size());
        assertNotNull(arrayVariant.getNot());
        assertNotNull(arrayVariant.getNot().getEnum());
        assertEquals(1, arrayVariant.getNot().getEnum().size());
        assertEquals(Arrays.asList((Object) null, null), arrayVariant.getNot().getEnum().get(0));
        assertTrue(arrayVariant.getAnyOf().stream().anyMatch(variant ->
                Integer.valueOf(1).equals(variant.getMaxItems())
                        && Boolean.FALSE.equals(variant.getItems().getNullable())));

        Schema<?> objectVariant = schema.getOneOf().stream()
                .filter(candidate -> "object".equals(candidate.getType()))
                .findFirst()
                .orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Schema> props = (Map<String, Schema>) objectVariant.getProperties();
        assertNotNull(props.get("minPrice"));
        assertNotNull(props.get("maxPrice"));
        assertNotNull(props.get("currency"));
        assertEquals(Boolean.TRUE, props.get("minPrice").getNullable());
        assertEquals(Boolean.TRUE, props.get("maxPrice").getNullable());
        assertNotNull(objectVariant.getAnyOf());
        assertEquals(2, objectVariant.getAnyOf().size());
        assertTrue(objectVariant.getAnyOf().stream().anyMatch(variant ->
                variant.getRequired() != null && variant.getRequired().contains("minPrice")));
        assertTrue(objectVariant.getAnyOf().stream().anyMatch(variant ->
                variant.getRequired() != null && variant.getRequired().contains("maxPrice")));
        Schema<?> minRequiredVariant = objectVariant.getAnyOf().stream()
                .filter(variant -> variant.getRequired() != null && variant.getRequired().contains("minPrice"))
                .findFirst()
                .orElseThrow();
        Schema<?> maxRequiredVariant = objectVariant.getAnyOf().stream()
                .filter(variant -> variant.getRequired() != null && variant.getRequired().contains("maxPrice"))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Schema> minRequiredProps = (Map<String, Schema>) minRequiredVariant.getProperties();
        @SuppressWarnings("unchecked")
        Map<String, Schema> maxRequiredProps = (Map<String, Schema>) maxRequiredVariant.getProperties();
        assertEquals(Boolean.FALSE, minRequiredProps.get("minPrice").getNullable());
        assertEquals(Boolean.FALSE, maxRequiredProps.get("maxPrice").getNullable());
    }

    @Test
    void dateRangePublishesOneOfArrayAndCanonicalObjectContract() {
        Schema<?> schema = new Schema<>();
        schema.setName("periodo");
        schema.setType("array");
        Schema<?> item = new Schema<>();
        item.setType("string");
        item.setFormat("date");
        schema.setItems(item);

        applyAnnotations(schema, new Annotation[]{defaultUISchema(), betweenFilterable()});

        assertNull(schema.getType());
        assertNotNull(schema.getOneOf());
        assertEquals(2, schema.getOneOf().size());

        Schema<?> arrayVariant = schema.getOneOf().stream()
                .filter(candidate -> "array".equals(candidate.getType()))
                .findFirst()
                .orElseThrow();
        assertEquals(1, arrayVariant.getMinItems());
        assertEquals(2, arrayVariant.getMaxItems());
        assertNotNull(arrayVariant.getItems());
        assertEquals(Boolean.TRUE, arrayVariant.getItems().getNullable());
        assertNotNull(arrayVariant.getNot());
        assertNotNull(arrayVariant.getNot().getEnum());
        assertEquals(1, arrayVariant.getNot().getEnum().size());
        assertEquals(Arrays.asList((Object) null, null), arrayVariant.getNot().getEnum().get(0));

        Schema<?> objectVariant = schema.getOneOf().stream()
                .filter(candidate -> "object".equals(candidate.getType()))
                .findFirst()
                .orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Schema> props = (Map<String, Schema>) objectVariant.getProperties();
        assertNotNull(props.get("startDate"));
        assertNotNull(props.get("endDate"));
        assertEquals(Boolean.TRUE, props.get("startDate").getNullable());
        assertEquals(Boolean.TRUE, props.get("endDate").getNullable());
        assertNotNull(objectVariant.getAnyOf());
        assertEquals(2, objectVariant.getAnyOf().size());
        assertTrue(objectVariant.getAnyOf().stream().anyMatch(variant ->
                variant.getRequired() != null && variant.getRequired().contains("startDate")));
        assertTrue(objectVariant.getAnyOf().stream().anyMatch(variant ->
                variant.getRequired() != null && variant.getRequired().contains("endDate")));
    }

    @Test
    void exclusiveRangePublishesStrictTwoBoundContract() {
        Schema<?> schema = new Schema<>();
        schema.setName("salaryRange");
        schema.setType("array");
        Schema<?> item = new Schema<>();
        item.setType("number");
        item.setFormat("currency");
        schema.setItems(item);

        applyAnnotations(schema, new Annotation[]{
                defaultUISchema(org.praxisplatform.uischema.NumericFormat.CURRENCY),
                filterable(Filterable.FilterOperation.BETWEEN_EXCLUSIVE)
        });

        Schema<?> arrayVariant = schema.getOneOf().stream()
                .filter(candidate -> "array".equals(candidate.getType()))
                .findFirst()
                .orElseThrow();
        assertEquals(2, arrayVariant.getMinItems());
        assertEquals(2, arrayVariant.getMaxItems());
        assertNotNull(arrayVariant.getItems());
        assertEquals(Boolean.FALSE, arrayVariant.getItems().getNullable());
        assertNull(arrayVariant.getAnyOf());

        Schema<?> objectVariant = schema.getOneOf().stream()
                .filter(candidate -> "object".equals(candidate.getType()))
                .findFirst()
                .orElseThrow();
        assertNull(objectVariant.getAnyOf());
        assertNotNull(objectVariant.getRequired());
        assertTrue(objectVariant.getRequired().containsAll(List.of("minPrice", "maxPrice")));
        assertEquals(2, objectVariant.getMinProperties());

        @SuppressWarnings("unchecked")
        Map<String, Schema> props = (Map<String, Schema>) objectVariant.getProperties();
        assertEquals(Boolean.FALSE, props.get("minPrice").getNullable());
        assertEquals(Boolean.FALSE, props.get("maxPrice").getNullable());
    }

    @Test
    void exclusiveDateRangePublishesStrictTwoBoundContract() {
        Schema<?> schema = new Schema<>();
        schema.setName("periodo");
        schema.setType("array");
        Schema<?> item = new Schema<>();
        item.setType("string");
        item.setFormat("date");
        schema.setItems(item);

        applyAnnotations(schema, new Annotation[]{
                defaultUISchema(),
                filterable(Filterable.FilterOperation.BETWEEN_EXCLUSIVE)
        });

        Schema<?> arrayVariant = schema.getOneOf().stream()
                .filter(candidate -> "array".equals(candidate.getType()))
                .findFirst()
                .orElseThrow();
        assertEquals(2, arrayVariant.getMinItems());
        assertEquals(2, arrayVariant.getMaxItems());
        assertNotNull(arrayVariant.getItems());
        assertEquals(Boolean.FALSE, arrayVariant.getItems().getNullable());

        Schema<?> objectVariant = schema.getOneOf().stream()
                .filter(candidate -> "object".equals(candidate.getType()))
                .findFirst()
                .orElseThrow();
        assertNotNull(objectVariant.getRequired());
        assertTrue(objectVariant.getRequired().containsAll(List.of("startDate", "endDate")));
        assertEquals(2, objectVariant.getMinProperties());
        @SuppressWarnings("unchecked")
        Map<String, Schema> props = (Map<String, Schema>) objectVariant.getProperties();
        assertEquals(Boolean.FALSE, props.get("startDate").getNullable());
        assertEquals(Boolean.FALSE, props.get("endDate").getNullable());
    }

    @Test
    void notBetweenPublishesNonExclusiveRangeContract() {
        Schema<?> schema = numericRangeSchema("salaryRange", "currency");

        applyAnnotations(schema, new Annotation[]{
                defaultUISchema(org.praxisplatform.uischema.NumericFormat.CURRENCY),
                filterable(Filterable.FilterOperation.NOT_BETWEEN)
        });

        assertNonExclusiveRangeContract(schema, "minPrice", "maxPrice");
    }

    @Test
    void outsideRangePublishesNonExclusiveRangeContract() {
        Schema<?> schema = numericRangeSchema("salaryRange", "currency");

        applyAnnotations(schema, new Annotation[]{
                defaultUISchema(org.praxisplatform.uischema.NumericFormat.CURRENCY),
                filterable(Filterable.FilterOperation.OUTSIDE_RANGE)
        });

        assertNonExclusiveRangeContract(schema, "minPrice", "maxPrice");
    }
}
