package org.praxisplatform.uischema.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.responses.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springdoc.core.models.MethodAttributes;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.core.service.GenericResponseService;
import org.springdoc.core.service.OperationService;
import org.springdoc.core.utils.PropertyResolverUtils;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.HandlerMethod;

/**
 * Keeps Springdoc's generic error-response resolution within one OpenAPI generation.
 *
 * <p>Springdoc 2.8 retains advice entries in its response builder across generations. This
 * adapter delegates both public build operations to a fresh builder associated with the
 * identity of the generated {@link Components}; it does not change response/schema parsing.</p>
 *
 * <p>Servlet frames belong to the request, are removed by the ordered global customizer after
 * path generation, and are cleared at request completion even when another customizer fails first.
 * The servlet map retains each generation until its cleanup or request completion; it has no
 * fixed frame-count limit. Non-servlet
 * callers (including preload) retain at most one frame per thread: a new generation replaces
 * it. An observed build failure or this global customization callback removes that frame. A failure
 * outside these callbacks can retain that single frame until the next generation; arbitrary
 * nested non-servlet generations and cross-thread continuation are not supported. Springdoc
 * can run group-specific customizers after the globals; those callbacks must not invoke this
 * response builder again after its frame has been released.</p>
 */
public final class GenerationScopedGenericResponseService extends GenericResponseService
        implements GlobalOpenApiCustomizer, Ordered {
    private final OperationService operationService;
    private final SpringDocConfigProperties properties;
    private final PropertyResolverUtils propertyResolver;
    private volatile ApplicationContext applicationContext;
    private final String requestAttribute = getClass().getName() + "." + UUID.randomUUID();
    private final ThreadLocal<Frame> nonServletFrame = new ThreadLocal<>();

    public GenerationScopedGenericResponseService(OperationService operationService,
            SpringDocConfigProperties properties,
            PropertyResolverUtils propertyResolver) {
        super(operationService, properties, propertyResolver);
        this.operationService = Objects.requireNonNull(operationService);
        this.properties = Objects.requireNonNull(properties);
        this.propertyResolver = Objects.requireNonNull(propertyResolver);
    }

    @Override
    public void setApplicationContext(ApplicationContext context) throws BeansException {
        super.setApplicationContext(context);
        this.applicationContext = Objects.requireNonNull(context);
    }

    @Override
    public void buildGenericResponse(Components components, Map<String, Object> advice, Locale locale) {
        Objects.requireNonNull(components, "Generation components are required");
        var frame = new Frame(components, newDelegate(), Thread.currentThread());
        install(frame);
        try {
            frame.delegate().buildGenericResponse(components, advice, locale);
        } catch (RuntimeException | Error failure) {
            release(components);
            throw failure;
        }
    }

    @Override
    public ApiResponses build(Components components, HandlerMethod handler, Operation operation,
            MethodAttributes attributes) {
        Objects.requireNonNull(components, "Generation components are required");
        // Springdoc intentionally skips buildGenericResponse when this option is false. Keep
        // that path usable with an empty builder; never borrow a previous generation's advice.
        if (!properties.isDefaultOverrideWithGenericResponse()) {
            release(components);
            return newDelegate().build(components, handler, operation, attributes);
        }
        Frame frame = current(components);
        if (frame == null || frame.owner() != Thread.currentThread()) {
            release(components);
            throw new IllegalStateException("No generic response generation for these Components on this thread");
        }
        try {
            return frame.delegate().build(components, handler, operation, attributes);
        } catch (RuntimeException | Error failure) {
            release(components);
            throw failure;
        }
    }

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi != null && openApi.getComponents() != null) release(openApi.getComponents());
    }

    @Override
    public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }

    private GenericResponseService newDelegate() {
        ApplicationContext context = Objects.requireNonNull(applicationContext,
                "Spring context must initialize the generic response builder");
        GenericResponseService delegate = new GenericResponseService(operationService, properties, propertyResolver);
        delegate.setApplicationContext(context);
        return delegate;
    }

    private void install(Frame frame) {
        ServletRequestAttributes attributes = servletAttributes();
        if (attributes == null) {
            nonServletFrame.set(frame); // One frame only, including after an unobserved external failure.
            return;
        }
        HttpServletRequest request = attributes.getRequest();
        synchronized (request) {
            Map<Components, Frame> frames = requestFrames(request);
            if (frames == null) {
                frames = new IdentityHashMap<>();
                request.setAttribute(requestAttribute, frames);
                Map<Components, Frame> retained = frames;
                attributes.registerDestructionCallback(requestAttribute, () -> {
                    synchronized (request) {
                        retained.clear();
                        if (request.getAttribute(requestAttribute) == retained) request.removeAttribute(requestAttribute);
                    }
                }, RequestAttributes.SCOPE_REQUEST);
            }
            frames.put(frame.components(), frame);
        }
    }

    private Frame current(Components components) {
        ServletRequestAttributes attributes = servletAttributes();
        if (attributes == null) {
            Frame frame = nonServletFrame.get();
            if (frame != null && frame.components() == components) return frame;
            nonServletFrame.remove();
            return null;
        }
        HttpServletRequest request = attributes.getRequest();
        synchronized (request) {
            Map<Components, Frame> frames = requestFrames(request);
            return frames == null ? null : frames.get(components);
        }
    }

    private void release(Components components) {
        ServletRequestAttributes attributes = servletAttributes();
        if (attributes == null) {
            Frame frame = nonServletFrame.get();
            if (frame != null && frame.components() == components) nonServletFrame.remove();
            return;
        }
        HttpServletRequest request = attributes.getRequest();
        synchronized (request) {
            Map<Components, Frame> frames = requestFrames(request);
            if (frames != null) {
                frames.remove(components);
                if (frames.isEmpty()) request.removeAttribute(requestAttribute);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<Components, Frame> requestFrames(HttpServletRequest request) {
        return (Map<Components, Frame>) request.getAttribute(requestAttribute);
    }

    private static ServletRequestAttributes servletAttributes() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes : null;
    }

    private record Frame(Components components, GenericResponseService delegate, Thread owner) { }
}
