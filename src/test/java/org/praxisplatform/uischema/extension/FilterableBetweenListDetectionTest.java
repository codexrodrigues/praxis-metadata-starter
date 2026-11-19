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
import org.praxisplatform.uischema.extension.annotation.UISchemaPreset;
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
        return defaultUISchema(org.praxisplatform.uischema.NumericFormat.AUTO);
    }

    private UISchema defaultUISchema(org.praxisplatform.uischema.NumericFormat numericFormat) {
        return new UISchema() {
            @Override
            public Class<? extends Annotation> annotationType() { return UISchema.class; }
            @Override public UISchemaPreset preset() { return UISchemaPreset.NONE; }
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
            @Override public boolean quickViewVisible() { return false; }
            @Override public int quickViewOrder() { return 0; }
            @Override public boolean filterable() { return false; }
            @Override public String[] visibleForAuthorities() { return new String[0]; }
            @Override public String[] editableForAuthorities() { return new String[0]; }
            @Override public String fieldAccessReason() { return ""; }
            @Override public String conditionalDisplay() { return ""; }
            @Override public String conditionalReadOnly() { return ""; }
            @Override public String dependsOn() { return ""; }
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
            @Override public String iconTrue() { return ""; }
            @Override public String iconFalse() { return ""; }
            @Override public String tone() { return ""; }
            @Override public String toneTrue() { return ""; }
            @Override public String toneFalse() { return ""; }
            @Override public String labelTrue() { return ""; }
            @Override public String labelFalse() { return ""; }
            @Override public String valueField() { return ""; }
            @Override public String displayField() { return ""; }
            @Override public String endpoint() { return ""; }
            @Override public String emptyOptionText() { return ""; }
            @Override public org.praxisplatform.uischema.annotation.UiOption[] optionItems() { return new org.praxisplatform.uischema.annotation.UiOption[0]; }
            @Override public Class<?> optionEnum() { return Void.class; }
            @Override public String options() { return ""; }
            @Override public String filter() { return ""; }
            @Override public String filterOptions() { return ""; }
            @Override public org.praxisplatform.uischema.NumericFormat numericFormat() { return numericFormat; }
            @Override public int colSpan() { return 0; }
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
            @Override
            public String endRelation() { return "validTo"; }
            @Override
            public boolean primarySearch() { return false; }
            @Override
            public boolean inline() { return false; }
            @Override
            public String[] relations() { return new String[0]; }
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

    @Test
    void inAndNotInArrayFilterableSetsMultipleTrue() {
        for (Filterable.FilterOperation op : List.of(Filterable.FilterOperation.IN, Filterable.FilterOperation.NOT_IN)) {
            Schema<?> schema = new Schema<>();
            schema.setName("statusList");
            schema.setType("array");
            Schema<?> item = new Schema<>();
            item.setType("string");
            schema.setItems(item);

            Map<String, Object> xUi = applyAnnotations(schema, new Annotation[]{
                    defaultUISchema(),
                    filterable(op)
            });
            assertEquals(Boolean.TRUE, xUi.get(FieldConfigProperties.MULTIPLE.getValue()),
                    "Expected x-ui.multiple=true for array property with " + op);
        }
    }

    @Test
    void inArrayWithOptionItemsPromotesDefaultControlToMultiSelect() {
        Schema<?> schema = new Schema<>();
        schema.setName("situacoes");
        schema.setType("array");
        Schema<?> item = new Schema<>();
        item.setType("string");
        schema.setItems(item);

        org.praxisplatform.uischema.annotation.UiOption option = new org.praxisplatform.uischema.annotation.UiOption() {
            @Override public Class<? extends Annotation> annotationType() {
                return org.praxisplatform.uischema.annotation.UiOption.class;
            }
            @Override public String value() { return "ATIVO"; }
            @Override public String label() { return "Ativo"; }
            @Override public String icon() { return ""; }
            @Override public org.praxisplatform.uischema.annotation.QuickFilterTone tone() {
                return org.praxisplatform.uischema.annotation.QuickFilterTone.UNSPECIFIED;
            }
            @Override public String description() { return ""; }
            @Override public boolean disabled() { return false; }
        };

        UISchema uiWithOptions = new UISchema() {
            private final UISchema base = defaultUISchema();
            @Override public Class<? extends Annotation> annotationType() { return UISchema.class; }
            @Override public UISchemaPreset preset() { return base.preset(); }
            @Override public String description() { return base.description(); }
            @Override public String example() { return base.example(); }
            @Override public String name() { return base.name(); }
            @Override public String label() { return "Situações"; }
            @Override public FieldDataType type() { return base.type(); }
            @Override public FieldControlType controlType() { return FieldControlType.AUTO; }
            @Override public String placeholder() { return base.placeholder(); }
            @Override public String defaultValue() { return base.defaultValue(); }
            @Override public String group() { return base.group(); }
            @Override public int order() { return base.order(); }
            @Override public String width() { return base.width(); }
            @Override public boolean isFlex() { return base.isFlex(); }
            @Override public String displayOrientation() { return base.displayOrientation(); }
            @Override public boolean disabled() { return base.disabled(); }
            @Override public boolean readOnly() { return base.readOnly(); }
            @Override public boolean multiple() { return base.multiple(); }
            @Override public boolean editable() { return base.editable(); }
            @Override public String validationMode() { return base.validationMode(); }
            @Override public boolean unique() { return base.unique(); }
            @Override public String mask() { return base.mask(); }
            @Override public boolean sortable() { return base.sortable(); }
            @Override public String conditionalRequired() { return base.conditionalRequired(); }
            @Override public String viewOnlyStyle() { return base.viewOnlyStyle(); }
            @Override public String validationTriggers() { return base.validationTriggers(); }
            @Override public boolean hidden() { return base.hidden(); }
            @Override public boolean tableHidden() { return base.tableHidden(); }
            @Override public boolean formHidden() { return base.formHidden(); }
            @Override public boolean quickViewVisible() { return base.quickViewVisible(); }
            @Override public int quickViewOrder() { return base.quickViewOrder(); }
            @Override public boolean filterable() { return base.filterable(); }
            @Override public String[] visibleForAuthorities() { return base.visibleForAuthorities(); }
            @Override public String[] editableForAuthorities() { return base.editableForAuthorities(); }
            @Override public String fieldAccessReason() { return base.fieldAccessReason(); }
            @Override public String conditionalDisplay() { return base.conditionalDisplay(); }
            @Override public String conditionalReadOnly() { return base.conditionalReadOnly(); }
            @Override public String dependsOn() { return base.dependsOn(); }
            @Override public String dependentField() { return base.dependentField(); }
            @Override public boolean resetOnDependentChange() { return base.resetOnDependentChange(); }
            @Override public boolean inlineEditing() { return base.inlineEditing(); }
            @Override public String transformValueFunction() { return base.transformValueFunction(); }
            @Override public int debounceTime() { return base.debounceTime(); }
            @Override public String helpText() { return base.helpText(); }
            @Override public String hint() { return base.hint(); }
            @Override public String hiddenCondition() { return base.hiddenCondition(); }
            @Override public String tooltipOnHover() { return base.tooltipOnHover(); }
            @Override public String icon() { return base.icon(); }
            @Override public org.praxisplatform.uischema.IconPosition iconPosition() { return base.iconPosition(); }
            @Override public String iconSize() { return base.iconSize(); }
            @Override public String iconColor() { return base.iconColor(); }
            @Override public String iconClass() { return base.iconClass(); }
            @Override public String iconStyle() { return base.iconStyle(); }
            @Override public String iconFontSize() { return base.iconFontSize(); }
            @Override public String iconTrue() { return base.iconTrue(); }
            @Override public String iconFalse() { return base.iconFalse(); }
            @Override public String tone() { return base.tone(); }
            @Override public String toneTrue() { return base.toneTrue(); }
            @Override public String toneFalse() { return base.toneFalse(); }
            @Override public String labelTrue() { return base.labelTrue(); }
            @Override public String labelFalse() { return base.labelFalse(); }
            @Override public String valueField() { return base.valueField(); }
            @Override public String displayField() { return base.displayField(); }
            @Override public String endpoint() { return base.endpoint(); }
            @Override public String emptyOptionText() { return base.emptyOptionText(); }
            @Override public org.praxisplatform.uischema.annotation.UiOption[] optionItems() {
                return new org.praxisplatform.uischema.annotation.UiOption[]{ option };
            }
            @Override public Class<?> optionEnum() { return base.optionEnum(); }
            @Override public String options() { return base.options(); }
            @Override public String filter() { return base.filter(); }
            @Override public String filterOptions() { return base.filterOptions(); }
            @Override public org.praxisplatform.uischema.NumericFormat numericFormat() { return base.numericFormat(); }
            @Override public int colSpan() { return base.colSpan(); }
            @Override public String numericStep() { return base.numericStep(); }
            @Override public String numericMin() { return base.numericMin(); }
            @Override public String numericMax() { return base.numericMax(); }
            @Override public String numericMaxLength() { return base.numericMaxLength(); }
            @Override public boolean required() { return base.required(); }
            @Override public int minLength() { return base.minLength(); }
            @Override public int maxLength() { return base.maxLength(); }
            @Override public String min() { return base.min(); }
            @Override public String max() { return base.max(); }
            @Override public org.praxisplatform.uischema.ValidationPattern pattern() { return base.pattern(); }
            @Override public String requiredMessage() { return base.requiredMessage(); }
            @Override public String minLengthMessage() { return base.minLengthMessage(); }
            @Override public String maxLengthMessage() { return base.maxLengthMessage(); }
            @Override public String patternMessage() { return base.patternMessage(); }
            @Override public String rangeMessage() { return base.rangeMessage(); }
            @Override public String customValidator() { return base.customValidator(); }
            @Override public String asyncValidator() { return base.asyncValidator(); }
            @Override public int minWords() { return base.minWords(); }
            @Override public org.praxisplatform.uischema.AllowedFileTypes allowedFileTypes() { return base.allowedFileTypes(); }
            @Override public String maxFileSize() { return base.maxFileSize(); }
            @Override public ExtensionProperty[] extraProperties() { return base.extraProperties(); }
        };

        Map<String, Object> xUi = applyAnnotations(schema, new Annotation[]{
                uiWithOptions,
                filterable(Filterable.FilterOperation.IN)
        });
        assertEquals(Boolean.TRUE, xUi.get(FieldConfigProperties.MULTIPLE.getValue()));
        assertEquals(FieldControlType.MULTI_SELECT.getValue(), xUi.get(FieldConfigProperties.CONTROL_TYPE.getValue()));
    }

    static class InheritanceResponseDto {
        @UISchema(label = "Situação", controlType = FieldControlType.SELECT, icon = "verified")
        private String situacao;
    }

    static class InheritanceFilterDto {
        @Filterable(operation = Filterable.FilterOperation.IN, relation = "situacao")
        private List<String> situacoes;

        @Filterable(operation = Filterable.FilterOperation.IN, relation = "cargoId")
        private List<Long> cargoIds;

        public List<String> getSituacoes() { return situacoes; }
        public void setSituacoes(List<String> situacoes) { this.situacoes = situacoes; }
        public List<Long> getCargoIds() { return cargoIds; }
        public void setCargoIds(List<Long> cargoIds) { this.cargoIds = cargoIds; }
    }

    @Test
    @SuppressWarnings("unchecked")
    void filterFieldInheritancePromotesMultiSelectWhenInheritingOptionsOrEndpoint() {
        Schema<?> responseSchema = new Schema<>();
        Schema<?> situacaoProp = new Schema<>();
        situacaoProp.setType("string");
        situacaoProp.addExtension("x-ui", new java.util.LinkedHashMap<>(Map.of(
                FieldConfigProperties.CONTROL_TYPE.getValue(), FieldControlType.SELECT.getValue(),
                FieldConfigProperties.ICON.getValue(), "verified",
                "options", List.of(Map.of("value", "ATIVO", "label", "Ativo"))
        )));
        Schema<?> cargoProp = new Schema<>();
        cargoProp.setType("integer");
        cargoProp.addExtension("x-ui", new java.util.LinkedHashMap<>(Map.of(
                FieldConfigProperties.CONTROL_TYPE.getValue(), FieldControlType.ENTITY_LOOKUP.getValue(),
                FieldConfigProperties.ENDPOINT.getValue(), "/api/positions/options/filter",
                FieldConfigProperties.VALUE_FIELD.getValue(), "id",
                FieldConfigProperties.DISPLAY_FIELD.getValue(), "label"
        )));
        responseSchema.setProperties(Map.of("situacao", situacaoProp, "cargoId", cargoProp));

        Schema<?> filterSchema = new Schema<>();
        Schema<?> situacoesFilter = new Schema<>();
        situacoesFilter.setType("array");
        situacoesFilter.addExtension("x-ui", new java.util.LinkedHashMap<>(Map.of(
                FieldConfigProperties.TYPE.getValue(), FieldDataType.ARRAY.getValue(),
                FieldConfigProperties.CONTROL_TYPE.getValue(), FieldControlType.CHIP_INPUT.getValue(),
                FieldConfigProperties.MULTIPLE.getValue(), true
        )));
        Schema<?> cargoIdsFilter = new Schema<>();
        cargoIdsFilter.setType("array");
        cargoIdsFilter.addExtension("x-ui", new java.util.LinkedHashMap<>(Map.of(
                FieldConfigProperties.TYPE.getValue(), FieldDataType.ARRAY.getValue(),
                FieldConfigProperties.CONTROL_TYPE.getValue(), FieldControlType.CHIP_INPUT.getValue(),
                FieldConfigProperties.MULTIPLE.getValue(), true
        )));
        filterSchema.setProperties(Map.of("situacoes", situacoesFilter, "cargoIds", cargoIdsFilter));

        FilterFieldMetadataInheritanceSupport.inheritToOpenApiFilterSchema(
                filterSchema,
                InheritanceFilterDto.class,
                responseSchema,
                Map.of()
        );

        Map<String, Object> situacoesXUi = (Map<String, Object>) situacoesFilter.getExtensions().get("x-ui");
        assertEquals(FieldControlType.MULTI_SELECT.getValue(), situacoesXUi.get(FieldConfigProperties.CONTROL_TYPE.getValue()));
        assertEquals("verified", situacoesXUi.get(FieldConfigProperties.ICON.getValue()));
        assertNotNull(situacoesXUi.get("options"));

        Map<String, Object> cargoIdsXUi = (Map<String, Object>) cargoIdsFilter.getExtensions().get("x-ui");
        assertEquals(FieldControlType.MULTI_SELECT.getValue(), cargoIdsXUi.get(FieldConfigProperties.CONTROL_TYPE.getValue()));
        assertEquals("/api/positions/options/filter", cargoIdsXUi.get(FieldConfigProperties.ENDPOINT.getValue()));
    }
}
