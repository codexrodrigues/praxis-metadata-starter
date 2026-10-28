package org.praxisplatform.uischema.filter.range;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.media.*;
import java.util.*;

/** Single owner of emitted array/object interval constraints and their structural recognition. */
public final class CanonicalRangeSchema {
    private CanonicalRangeSchema() { }

    public static void apply(Schema<?> property, boolean isDateLike, boolean monetary, boolean requireTwoBounds) {
        String itemFormat=property.getItems().getFormat();
        ArraySchema arrayVariant = new ArraySchema();
        inheritGeneratedSchemaDialect(arrayVariant, property, "array");
        Schema<?> arrayItem = copyRangeItemSchema(property.getItems());
        applyArrayRangeBoundContract(arrayVariant, arrayItem, requireTwoBounds);

        ObjectSchema objectVariant = new ObjectSchema();
        inheritGeneratedSchemaDialect(objectVariant, property, "object");
        Map<String, Schema> properties = new LinkedHashMap<>();
        Schema<?> lowerBoundSchema;
        Schema<?> upperBoundSchema;
        String lowerBoundName;
        String upperBoundName;

        if (isDateLike) {
            StringSchema lower = new StringSchema();
            inheritGeneratedSchemaDialect(lower, property.getItems(), "string");
            lower.setFormat(itemFormat);
            StringSchema upper = new StringSchema();
            inheritGeneratedSchemaDialect(upper, property.getItems(), "string");
            upper.setFormat(itemFormat);
            markRangeBoundNullable(lower, !requireTwoBounds);
            markRangeBoundNullable(upper, !requireTwoBounds);
            lowerBoundName = "startDate";
            upperBoundName = "endDate";
            lowerBoundSchema = lower;
            upperBoundSchema = upper;
            properties.put(lowerBoundName, lower);
            properties.put(upperBoundName, upper);
        } else {

            Schema<?> lower = copyRangeItemSchema(property.getItems());
            Schema<?> upper = copyRangeItemSchema(property.getItems());
            markRangeBoundNullable(lower, !requireTwoBounds);
            markRangeBoundNullable(upper, !requireTwoBounds);
            if (monetary) {
                lowerBoundName = "minPrice";
                upperBoundName = "maxPrice";
                properties.put(lowerBoundName, lower);
                properties.put(upperBoundName, upper);
                StringSchema currency = new StringSchema();
                inheritGeneratedSchemaDialect(currency, property, "string");
                properties.put("currency", currency);
            } else {
                lowerBoundName = "min";
                upperBoundName = "max";
                properties.put(lowerBoundName, lower);
                properties.put(upperBoundName, upper);
            }
            lowerBoundSchema = lower;
            upperBoundSchema = upper;
        }

        objectVariant.setProperties(properties);
        applyObjectRangeBoundContract(
                objectVariant,
                lowerBoundName,
                lowerBoundSchema,
                upperBoundName,
                upperBoundSchema,
                requireTwoBounds
        );

        List<Schema> oneOf = new ArrayList<>(2);
        oneOf.add(arrayVariant);
        oneOf.add(objectVariant);
        property.setOneOf(oneOf);
        // Remove tipo base fixo para que o oneOf permita array e objeto no mesmo contrato.
        property.setType(null);
        property.setTypes(null);
        property.setFormat(null);
        property.setItems(null);
    }

    private static Schema<?> copyRangeItemSchema(Schema<?> source) {
        if (source == null) {
            return new Schema<>();
        }
        Schema<?> copy = new Schema<>();
        copy.setType(source.getType());
        copy.setSpecVersion(source.getSpecVersion());
        if (source.getTypes() != null) {
            copy.setTypes(new java.util.LinkedHashSet<>(source.getTypes()));
        }
        copy.setFormat(source.getFormat());
        copy.setPattern(source.getPattern());
        copy.setMinimum(source.getMinimum());
        copy.setMaximum(source.getMaximum());
        copy.setNullable(source.getNullable());
        copy.setExample(source.getExample());
        return copy;
    }

    private static void markRangeBoundNullable(Schema<?> schema, boolean allowNull) {
        if (schema == null) {
            return;
        }
        schema.setNullable(allowNull);
        if (schema.getSpecVersion() == io.swagger.v3.oas.models.SpecVersion.V31 && schema.getTypes() != null) {
            Set<String> types = new java.util.LinkedHashSet<>(schema.getTypes());
            if (allowNull) types.add("null");
            else types.remove("null");
            schema.setTypes(types);
        }
    }

    /** Generated range variants retain the source dialect, never coerce its input to legacy type. */
    private static void inheritGeneratedSchemaDialect(Schema<?> target, Schema<?> source, String type) {
        target.setSpecVersion(source.getSpecVersion());
        if (source.getSpecVersion() == io.swagger.v3.oas.models.SpecVersion.V31) {
            target.setTypes(new java.util.LinkedHashSet<>(Set.of(type)));
        }
    }

    private static void applyArrayRangeBoundContract(
            ArraySchema arrayVariant,
            Schema<?> arrayItem,
            boolean requireTwoBounds
    ) {
        if (arrayVariant == null || arrayItem == null) {
            return;
        }

        if (requireTwoBounds) {
            Schema<?> strictItem = copyRangeItemSchema(arrayItem);
            markRangeBoundNullable(strictItem, false);
            arrayVariant.setItems(strictItem);
            arrayVariant.setMinItems(2);
            arrayVariant.setMaxItems(2);
            return;
        }

        Schema<?> nullableItem = copyRangeItemSchema(arrayItem);
        markRangeBoundNullable(nullableItem, true);
        arrayVariant.setItems(nullableItem);
        arrayVariant.setMinItems(1);
        arrayVariant.setMaxItems(2);

        ArraySchema oneNonNullBound = new ArraySchema();
        inheritGeneratedSchemaDialect(oneNonNullBound, arrayVariant, "array");
        Schema<?> oneNonNullItem = copyRangeItemSchema(arrayItem);
        markRangeBoundNullable(oneNonNullItem, false);
        oneNonNullBound.setItems(oneNonNullItem);
        oneNonNullBound.setMinItems(1);
        oneNonNullBound.setMaxItems(1);

        ArraySchema twoBounds = new ArraySchema();
        inheritGeneratedSchemaDialect(twoBounds, arrayVariant, "array");
        Schema<?> twoBoundsItem = copyRangeItemSchema(arrayItem);
        markRangeBoundNullable(twoBoundsItem, true);
        twoBounds.setItems(twoBoundsItem);
        twoBounds.setMinItems(2);
        twoBounds.setMaxItems(2);

        ArraySchema allNullTwoBounds = new ArraySchema();
        inheritGeneratedSchemaDialect(allNullTwoBounds, arrayVariant, "array");
        Schema<?> allNullItem = copyRangeItemSchema(arrayItem);
        markRangeBoundNullable(allNullItem, true);
        allNullTwoBounds.setItems(allNullItem);
        allNullTwoBounds.setMinItems(2);
        allNullTwoBounds.setMaxItems(2);
        allNullTwoBounds.setEnum(List.of(Arrays.asList((Object) null, null)));

        arrayVariant.setAnyOf(List.of(oneNonNullBound, twoBounds));
        arrayVariant.setNot(allNullTwoBounds);
    }

    private static void applyObjectRangeBoundContract(
            ObjectSchema objectVariant,
            String lowerBoundName,
            Schema<?> lowerBoundSchema,
            String upperBoundName,
            Schema<?> upperBoundSchema,
            boolean requireTwoBounds
    ) {
        if (objectVariant == null) {
            return;
        }

        if (requireTwoBounds) {
            objectVariant.setRequired(List.of(lowerBoundName, upperBoundName));
            objectVariant.setMinProperties(2);
            return;
        }

        objectVariant.setMinProperties(1);

        ObjectSchema requiresLower = new ObjectSchema();
        inheritGeneratedSchemaDialect(requiresLower, objectVariant, "object");
        Map<String, Schema> lowerProperties = new LinkedHashMap<>();
        Schema<?> nonNullLower = copyRangeItemSchema(lowerBoundSchema);
        markRangeBoundNullable(nonNullLower, false);
        lowerProperties.put(lowerBoundName, nonNullLower);
        requiresLower.setProperties(lowerProperties);
        requiresLower.setRequired(List.of(lowerBoundName));

        ObjectSchema requiresUpper = new ObjectSchema();
        inheritGeneratedSchemaDialect(requiresUpper, objectVariant, "object");
        Map<String, Schema> upperProperties = new LinkedHashMap<>();
        Schema<?> nonNullUpper = copyRangeItemSchema(upperBoundSchema);
        markRangeBoundNullable(nonNullUpper, false);
        upperProperties.put(upperBoundName, nonNullUpper);
        requiresUpper.setProperties(upperProperties);
        requiresUpper.setRequired(List.of(upperBoundName));

        objectVariant.setAnyOf(List.of(requiresLower, requiresUpper));
    }


    /** Recognizes only exactly re-emitted interval variants, never arbitrary request unions. */
    public static boolean matches(JsonNode source, SpecVersion version) {
        Set<String> envelope = Set.of("oneOf", "description", "title", "nullable", "deprecated",
                "readOnly", "writeOnly", "example", "examples", "default", "$schema", "externalDocs", "xml");
        var envelopeKeys = source.fieldNames();
        while (envelopeKeys.hasNext()) {
            String key = envelopeKeys.next();
            if (!envelope.contains(key) && !key.startsWith("x-")) return false;
        }
        if (source.has("anyOf") || source.has("allOf") || source.has("not")) return false;
        JsonNode variants=source.path("oneOf");
        if (!variants.isArray() || variants.size()!=2 || source.has("type") || source.has("properties") || source.has("items")) return false;
        JsonNode array=variants.get(0), object=variants.get(1), item=array.path("items");
        if (!"array".equals(array.path("type").asText()) && !array.path("type").toString().equals("[\"array\"]")) return false;
        if (!item.isObject()) return false;
        Set<String> allowed=Set.of("type","format","pattern","minimum","maximum","nullable","example");
        var keys=item.fieldNames();while(keys.hasNext()) if(!allowed.contains(keys.next())) return false;
        if(item.has("example") && !item.path("example").isValueNode()) return false;
        String type;
        if(item.path("type").isTextual()) type=item.path("type").asText();
        else {
            var types=item.path("type");if(!types.isArray() || types.isEmpty() || types.size()>2) return false;
            type=null;for(var name:types) { if(!name.isTextual()) return false;if(!name.asText().equals("null")) { if(type!=null)return false;type=name.asText(); } }
        }
        boolean date="string".equals(type) && Set.of("date","date-time").contains(item.path("format").asText());
        if(!date && !"integer".equals(type) && !"number".equals(type)) return false;
        try {
            var mapper=version==SpecVersion.V31 ? io.swagger.v3.core.util.Json31.mapper() : io.swagger.v3.core.util.Json.mapper();
            Schema<?> scalar=mapper.treeToValue(item,Schema.class);scalar.setSpecVersion(version);
            var property=new ArraySchema();property.setSpecVersion(version);property.setItems(scalar);
            boolean monetary=object.path("properties").has("minPrice");
            boolean exclusive=array.path("minItems").asInt()==2;
            apply(property,date,monetary,exclusive);
            JsonNode expected=mapper.valueToTree(property);
            return new org.praxisplatform.uischema.hash.SchemaCanonicalizer().canonicalize(expected.path("oneOf"))
                .equals(new org.praxisplatform.uischema.hash.SchemaCanonicalizer().canonicalize(variants));
        } catch (java.io.IOException | RuntimeException invalid) { return false; }
    }
}
