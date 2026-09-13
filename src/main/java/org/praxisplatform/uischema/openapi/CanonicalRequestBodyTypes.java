package org.praxisplatform.uischema.openapi;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.type.TypeFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.core.GenericTypeResolver;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Resolves declared MVC body types without invoking converters, constructors or domain code. */
final class CanonicalRequestBodyTypes {
    private CanonicalRequestBodyTypes() { }

    static JavaType resolve(HandlerMethod handler, TypeFactory factory) {
        MethodParameter body = null;
        for (MethodParameter parameter : handler.getMethodParameters()) {
            RequestBody annotation = parameter.getParameterAnnotation(RequestBody.class);
            if (annotation != null) {
                if (body != null) throw invalid("Exactly one request body is required");
                if (!annotation.required() || parameter.isOptional()) throw invalid("Optional request bodies are not supported");
                body = parameter;
            }
            if (HttpEntity.class.isAssignableFrom(parameter.getParameterType())) {
                throw invalid("HTTP entity transport wrappers are not supported");
            }
        }
        if (body == null) throw invalid("A direct @RequestBody DTO is required");
        Map<TypeVariable, Type> variables = GenericTypeResolver.getTypeVariableMap(handler.getBeanType());
        JavaType type = concrete(body.getGenericParameterType(), variables, factory, new HashSet<>(), 0);
        Class<?> raw = type.getRawClass();
        if (raw == Object.class || raw.isPrimitive() || raw.isEnum() || raw.isArray()
                || raw.isInterface() || Modifier.isAbstract(raw.getModifiers()) || BeanUtils.isSimpleValueType(raw)
                || (raw.isMemberClass() && !Modifier.isStatic(raw.getModifiers()))
                || type.isContainerType() || type.isReferenceType()
                || CharSequence.class.isAssignableFrom(raw) || Number.class.isAssignableFrom(raw)
                || raw == Boolean.class || raw == Character.class || raw == Void.class
                || Optional.class.isAssignableFrom(raw) || HttpEntity.class.isAssignableFrom(raw)
                || JsonNode.class.isAssignableFrom(raw)) {
            throw invalid("A concrete DTO body is required, not an untyped value or transport wrapper");
        }
        // Jackson resolves bounded wildcards to their upper bound. Inspect declared ancestors
        // as well, before treating their resolved JavaTypes as proof of closed generic arguments.
        validateHierarchy(type, new HashSet<>(), new HashSet<>(), 0);
        return type;
    }

    private static void validateDeclaredAncestors(Class<?> raw, Set<Class<?>> seen, int depth) {
        if (raw == null || raw == Object.class || !seen.add(raw)) return;
        if (depth > 32) throw invalid("Request body inheritance exceeds nesting limit");
        validateDeclaredType(raw.getGenericSuperclass(), depth + 1);
        for (Type implemented : raw.getGenericInterfaces()) validateDeclaredType(implemented, depth + 1);
        validateDeclaredAncestors(raw.getSuperclass(), seen, depth + 1);
        for (Class<?> implemented : raw.getInterfaces()) validateDeclaredAncestors(implemented, seen, depth + 1);
    }

    private static void validateDeclaredType(Type type, int depth) {
        if (type == null) return;
        if (depth > 32) throw invalid("Request body generic declaration exceeds nesting limit");
        if (type instanceof WildcardType) throw invalid("Wildcard generic ancestors are unsupported");
        if (type instanceof ParameterizedType parameterized) {
            for (Type argument : parameterized.getActualTypeArguments()) validateDeclaredType(argument, depth + 1);
        } else if (type instanceof GenericArrayType array) {
            validateDeclaredType(array.getGenericComponentType(), depth + 1);
        } else if (type instanceof Class<?> declared && declared.getTypeParameters().length != 0) {
            throw invalid("Raw generic ancestors are unsupported");
        }
        // Type variables here are checked against the resolved JavaType hierarchy below.
    }

    private static void validateHierarchy(JavaType type, Set<JavaType> seen, Set<Class<?>> declarations, int depth) {
        if (type == null || type.isJavaLangObject() || !seen.add(type)) return;
        if (depth > 32) throw invalid("Request body inheritance exceeds nesting limit");
        validateDeclaredAncestors(type.getRawClass(), declarations, depth);
        if (type.getRawClass().getTypeParameters().length != type.getBindings().size()) {
            throw invalid("DTO inheritance contains raw generic types");
        }
        for (JavaType argument : type.getBindings().getTypeParameters()) {
            if (argument.isJavaLangObject()) throw invalid("DTO inheritance contains unresolved generic types");
            validateHierarchy(argument, seen, declarations, depth + 1);
        }
        if (type.isArrayType()) validateHierarchy(type.getContentType(), seen, declarations, depth + 1);
        validateHierarchy(type.getSuperClass(), seen, declarations, depth + 1);
        for (JavaType implemented : type.getInterfaces()) validateHierarchy(implemented, seen, declarations, depth + 1);
    }

    private static JavaType concrete(Type type, Map<TypeVariable, Type> variables, TypeFactory factory,
            Set<TypeVariable<?>> resolving, int depth) {
        if (depth > 32) throw invalid("Request body type exceeds generic nesting limit");
        if (type instanceof TypeVariable<?> variable) {
            Type bound = variables.get(variable);
            if (bound == null || bound.equals(variable) || !resolving.add(variable)) {
                throw invalid("Request body contains an unresolved type variable");
            }
            JavaType result = concrete(bound, variables, factory, resolving, depth + 1);
            resolving.remove(variable);
            return result;
        }
        if (type instanceof ParameterizedType parameterized) {
            if (!(parameterized.getRawType() instanceof Class<?> raw)) throw invalid("Unsupported generic body type");
            if (raw.isMemberClass() && !Modifier.isStatic(raw.getModifiers())) {
                throw invalid("Non-static generic member DTOs are unsupported");
            }
            Type[] args = parameterized.getActualTypeArguments();
            JavaType[] resolved = new JavaType[args.length];
            for (int i = 0; i < args.length; i++) resolved[i] = concrete(args[i], variables, factory, resolving, depth + 1);
            return factory.constructParametricType(raw, resolved);
        }
        if (type instanceof GenericArrayType array) {
            return factory.constructArrayType(concrete(array.getGenericComponentType(), variables, factory, resolving, depth + 1));
        }
        if (type instanceof Class<?> raw) {
            if (raw == Object.class || raw.getTypeParameters().length != 0) {
                throw invalid("Raw or untyped request body arguments are unsupported");
            }
            if (raw.isArray()) return factory.constructArrayType(concrete(raw.getComponentType(), variables, factory, resolving, depth + 1));
            return factory.constructType(raw);
        }
        throw invalid("Wildcard or unsupported request body type");
    }

    private static IllegalStateException invalid(String message) { return new IllegalStateException(message); }
}
