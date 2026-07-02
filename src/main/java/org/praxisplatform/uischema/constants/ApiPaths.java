package org.praxisplatform.uischema.constants;

/**
 * <h2>🔧 Constantes de Paths Internos do Framework Praxis</h2>
 * 
 * <p>Esta classe contém <strong>apenas</strong> os paths internos utilizados pelo próprio 
 * framework Praxis Metadata Starter. Não contém paths específicos de domínios de negócio.</p>
 * 
 * <h3>⚠️ Importante - Separação de Responsabilidades</h3>
 * <p>Esta classe é parte do <strong>framework/starter</strong>, portanto deve conter apenas 
 * constantes que são <strong>internas ao framework</strong>, não específicas de aplicações.</p>
 * 
 * <h4>✅ Pertence aqui:</h4>
 * <ul>
 *   <li>Paths de endpoints do próprio framework (ex: /schemas)</li>
 *   <li>Prefixos padrão utilizados internamente</li>
 *   <li>Paths de infraestrutura do Praxis</li>
 * </ul>
 * 
 * <h4>❌ NÃO pertence aqui:</h4>
 * <ul>
 *   <li>Paths específicos de domínio (funcionarios, departamentos, etc.)</li>
 *   <li>Endpoints da aplicação host</li>
 *   <li>Paths de módulos de negócio específicos</li>
 * </ul>
 * 
 * <h3>🏗️ Para Aplicações</h3>
 * <p>Aplicações devem criar suas próprias classes de constantes:</p>
 * <pre>{@code
 * // No projeto da aplicação:
 * public final class ApiPaths {
 *     public static final class HumanResources {
 *         public static final String FUNCIONARIOS = "/api/human-resources/funcionarios";
 *         public static final String DEPARTAMENTOS = "/api/human-resources/departamentos";
 *     }
 * }
 * }</pre>
 * 
 * @see org.praxisplatform.uischema.controller.docs.ApiDocsController
 */
public final class ApiPaths {
    
    private ApiPaths() {
        throw new AssertionError("Utility class cannot be instantiated");
    }
    
    /**
     * <h3>📋 Endpoints de Metadados e Esquemas do Framework</h3>
     * <p>Paths utilizados pelos controllers internos do framework para 
     * servir documentação e metadados.</p>
     */
    public static final class Framework {
        private Framework() {
            throw new AssertionError("Utility class cannot be instantiated");
        }
        
        /**
         * Base path para endpoints de schemas e documentação.
         * Utilizado pelo ApiDocsController.
         */
        public static final String SCHEMAS = "/schemas";
        
        /**
         * Endpoint para documentação filtrada por path.
         * Permite resolução automática de grupos OpenAPI.
         */
        public static final String SCHEMAS_FILTERED = SCHEMAS + "/filtered";

        /**
         * Base path for the semantic host cockpit bundled by the starter.
         * Hosts with custom security can use these constants to allow the cockpit
         * without copying static assets into the application.
         */
        public static final String COCKPIT = "/praxis/cockpit";

        /**
         * Static entry point served from META-INF/resources.
         */
        public static final String COCKPIT_INDEX = COCKPIT + "/index.html";

        /**
         * Ant-style matcher for cockpit assets and nested resources.
         */
        public static final String COCKPIT_PATTERN = COCKPIT + "/**";

        /**
         * Base path para metadados de UI Schema.
         * Futuras extensões de metadados podem usar este prefixo.
         */
        public static final String UI_SCHEMA = SCHEMAS + "/ui-schema";
    }
}
