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
        return new UISchema() {
            @Override
            public Class<? extends Annotation> annotationType() { return UISchema.class; }
            @Override public String description() { return ""; }
            @Override public String example() { return ""; }
            @Override public String name() { return ""; }
            @Override public String label() { return ""; }
            @Override public FieldDataType type() { return FieldDataType.TEXT; }
            @Override public FieldControlType controlType() { return FieldControlType.INPUT; }
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
            @Override public org.praxisplatform.uischema.NumericFormat numericFormat() { return org.praxisplatform.uischema.NumericFormat.INTEGER; }
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

    private Filterable betweenFilterable() {
        return new Filterable() {
            @Override
            public Class<? extends Annotation> annotationType() { return Filterable.class; }
            @Override
            public FilterOperation operation() { return FilterOperation.BETWEEN; }
            @Override
            public String relation() { return ""; }
        };
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
}
