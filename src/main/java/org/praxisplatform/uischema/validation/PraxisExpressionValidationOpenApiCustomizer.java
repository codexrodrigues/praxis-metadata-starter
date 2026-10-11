package org.praxisplatform.uischema.validation;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.util.Map;

/**
 * Customizer OpenAPI do SpringDoc que executa a validacao estatica de todas as expressoes
 * de dominio presentes nos schemas gerados antes da publicacao do contrato.
 *
 * <p>
 * Em perfis de desenvolvimento, teste e CI (ou quando explicitamente configurado com strict=true),
 * falha imediatamente com {@link InvalidDomainExpressionException}. Em outros perfis (producao),
 * registra logs de erro estruturados sem abortar o startup.
 * </p>
 */
public class PraxisExpressionValidationOpenApiCustomizer implements GlobalOpenApiCustomizer, Ordered {

    private static final Logger LOGGER = LoggerFactory.getLogger(PraxisExpressionValidationOpenApiCustomizer.class);

    private final ExpressionValidationProperties properties;
    private final Environment environment;
    private final PraxisExpressionStaticValidator validator;

    public PraxisExpressionValidationOpenApiCustomizer(
            ExpressionValidationProperties properties,
            Environment environment,
            PraxisExpressionStaticValidator validator
    ) {
        this.properties = properties != null ? properties : ExpressionValidationProperties.defaults();
        this.environment = environment;
        this.validator = validator != null ? validator : new PraxisExpressionStaticValidator();
    }

    @Override
    public int getOrder() {
        // Executa apos todos os geradores e customizadores de schema
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public void customise(OpenAPI openApi) {
        if (!properties.enabled() || openApi == null || openApi.getComponents() == null) {
            return;
        }

        Map<String, Schema> schemas = openApi.getComponents().getSchemas();
        if (schemas == null || schemas.isEmpty()) {
            return;
        }

        boolean isStrict = properties.strict() ||
                (environment != null && environment.acceptsProfiles(Profiles.of("dev", "test", "ci")));

        LOGGER.debug("Iniciando validacao estatica de expressoes de dominio em {} schemas OpenAPI (strict: {})",
                schemas.size(), isStrict);

        for (Map.Entry<String, Schema> entry : schemas.entrySet()) {
            String schemaName = entry.getKey();
            Schema schema = entry.getValue();
            validator.validateSchemaExpressions(schemaName, schema, isStrict);
        }
    }
}
