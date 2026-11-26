package org.praxisplatform.uischema.bulk;

import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.praxisplatform.uischema.rest.exceptionhandler.ErrorCategory;
import org.praxisplatform.uischema.rest.response.CustomProblemDetail;
import org.praxisplatform.uischema.rest.response.RestApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Opt-in HTTP administration endpoints for asynchronous bulk publication/reconciliation jobs and
 * V21 durable lifecycle audit queries. Enabled when
 * {@code praxis.bulk.lifecycle.admin-endpoints.enabled=true}.
 */
@RestController
@Conditional(BulkPublicationAdminController.AutoConfiguredOnlyCondition.class)
@RequestMapping("${praxis.bulk.lifecycle.admin-endpoints.base-path:/api/platform/bulk-operations/publication}")
public class BulkPublicationAdminController {
    private static final Logger log = LoggerFactory.getLogger(BulkPublicationAdminController.class);
    private static final CacheControl NO_STORE = CacheControl.noStore();

    private final BulkPublicationJobService jobService;

    public BulkPublicationAdminController(BulkPublicationJobService jobService) {
        this.jobService = Objects.requireNonNull(jobService, "jobService");
    }

    /** Submits an asynchronous namespace publication or reconciliation job ({@code 202 Accepted}). */
    @PostMapping("/jobs")
    public ResponseEntity<RestApiResponse<BulkPublicationJobSnapshot>> submitJob(
            @RequestBody(required = false) SubmitJobRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationHeader,
            Principal principal) {
        BulkPublicationJobIntent intent = request != null && request.intent() != null
                ? request.intent()
                : BulkPublicationJobIntent.PUBLISH_ALL;
        // Derive actor exclusively from the authenticated Principal so callers cannot spoof V21 audit identity.
        String actor = principal != null && principal.getName() != null && !principal.getName().isBlank()
                ? principal.getName()
                : null;
        String realm = request != null ? request.realm() : null;
        String correlation = request != null && request.correlationId() != null && !request.correlationId().isBlank()
                ? request.correlationId()
                : correlationHeader;
        BulkLifecycleAuditContext audit = BulkLifecycleAuditContext.sanitized(actor, realm, correlation);
        BulkPublicationJobSnapshot snapshot = jobService.submit(intent, audit);
        URI location = ServletUriComponentsBuilder.fromCurrentRequestUri()
                .path("/{jobId}")
                .buildAndExpand(snapshot.jobId())
                .toUri();
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(location)
                .cacheControl(NO_STORE)
                .body(RestApiResponse.success(snapshot, null));
    }

    /** Returns the snapshot for a previously submitted publication job, or {@code 404}. */
    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<RestApiResponse<BulkPublicationJobSnapshot>> getJob(@PathVariable UUID jobId) {
        Optional<BulkPublicationJobSnapshot> found = jobService.findJob(jobId);
        if (found.isEmpty()) {
            CustomProblemDetail problem = buildProblem(
                    HttpStatus.NOT_FOUND,
                    "Bulk publication job not found",
                    "No bulk publication job was found for id " + jobId,
                    ErrorCategory.BUSINESS_LOGIC,
                    "BULK_PUBLICATION_JOB_NOT_FOUND",
                    "jobId");
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .cacheControl(NO_STORE)
                    .body(RestApiResponse.failure("Bulk publication job not found.", List.of(problem)));
        }
        return ResponseEntity.ok()
                .cacheControl(NO_STORE)
                .body(RestApiResponse.success(found.get(), null));
    }

    /** Lists recent asynchronous publication jobs, newest first (1..200). */
    @GetMapping("/jobs")
    public ResponseEntity<RestApiResponse<List<BulkPublicationJobSnapshot>>> listJobs(
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        List<BulkPublicationJobSnapshot> jobs = jobService.listJobs(limit);
        return ResponseEntity.ok()
                .cacheControl(NO_STORE)
                .body(RestApiResponse.success(jobs, null));
    }

    /** Reads immutable V21 lifecycle audit entries for the configured namespace (1..500). */
    @GetMapping("/audit")
    public ResponseEntity<RestApiResponse<List<BulkLifecycleAuditEntry>>> listAuditEntries(
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        List<BulkLifecycleAuditEntry> entries = jobService.listAuditEntries(limit);
        return ResponseEntity.ok()
                .cacheControl(NO_STORE)
                .body(RestApiResponse.success(entries, null));
    }

    @ExceptionHandler(BulkPublicationConflictException.class)
    public ResponseEntity<RestApiResponse<Void>> handleConflict(BulkPublicationConflictException ex) {
        log.warn("Bulk publication admin conflict: {}", ex.getMessage());
        CustomProblemDetail problem = buildProblem(
                HttpStatus.CONFLICT,
                "Bulk publication conflict",
                "Bulk publication conflicted with current generation or active job state.",
                ErrorCategory.CONFLICT,
                "BULK_PUBLICATION_CONFLICT",
                null);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .cacheControl(NO_STORE)
                .body(RestApiResponse.failure("Bulk publication conflict.", List.of(problem)));
    }

    @ExceptionHandler(BulkLifecycleUnavailableException.class)
    public ResponseEntity<RestApiResponse<Void>> handleUnavailable(BulkLifecycleUnavailableException ex) {
        log.error("Bulk lifecycle admin endpoint unavailable: {}", ex.getMessage(), ex);
        CustomProblemDetail problem = buildProblem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Bulk lifecycle unavailable",
                "Bulk lifecycle infrastructure is temporarily unavailable.",
                ErrorCategory.SYSTEM,
                "BULK_LIFECYCLE_UNAVAILABLE",
                null);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(NO_STORE)
                .body(RestApiResponse.failure("Bulk lifecycle unavailable.", List.of(problem)));
    }

    @ExceptionHandler(BulkContractDiagnosticException.class)
    public ResponseEntity<RestApiResponse<Void>> handleDiagnostic(BulkContractDiagnosticException ex) {
        log.error("Bulk contract diagnostic on admin endpoint (operationId={}): {}", ex.operationId(), ex.getMessage());
        CustomProblemDetail problem = buildProblem(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Bulk contract diagnostic",
                "Bulk operation contract validation failed.",
                ErrorCategory.VALIDATION,
                "BULK_CONTRACT_DIAGNOSTIC",
                ex.operationId());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .cacheControl(NO_STORE)
                .body(RestApiResponse.failure("Bulk contract diagnostic.", List.of(problem)));
    }

    @ExceptionHandler({
            IllegalArgumentException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<RestApiResponse<Void>> handleInvalidRequest(Exception ex) {
        log.warn("Invalid bulk publication admin request: {}", ex.getMessage());
        CustomProblemDetail problem = buildProblem(
                HttpStatus.BAD_REQUEST,
                "Invalid bulk publication request",
                "Invalid bulk publication request parameters.",
                ErrorCategory.VALIDATION,
                "BULK_PUBLICATION_INVALID_REQUEST",
                null);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .cacheControl(NO_STORE)
                .body(RestApiResponse.failure("Invalid bulk publication request.", List.of(problem)));
    }

    private static CustomProblemDetail buildProblem(
            HttpStatus status,
            String title,
            String detail,
            ErrorCategory category,
            String code,
            String target) {
        CustomProblemDetail problem = new CustomProblemDetail(detail);
        problem.setStatus(status.value());
        problem.setTitle(title);
        problem.setDetail(detail);
        problem.setCategory(category);
        problem.setCode(code);
        problem.setTarget(target);
        return problem;
    }

    /** Optional request payload for asynchronous job submission; {@code actor} is ignored in favor of {@link Principal}. */
    public record SubmitJobRequest(
            BulkPublicationJobIntent intent,
            String actor,
            String realm,
            String correlationId) {
    }

    static final class AutoConfiguredOnlyCondition extends SpringBootCondition {
        @Override
        public ConditionOutcome getMatchOutcome(
                ConditionContext context, AnnotatedTypeMetadata metadata) {
            return ConditionOutcome.noMatch(
                    "BulkPublicationAdminController is opt-in and registered exclusively via BulkOperationLifecycleAutoConfiguration");
        }
    }
}
