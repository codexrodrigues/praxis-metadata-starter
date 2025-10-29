package org.praxisplatform.uischema.util;

import org.springdoc.core.models.GroupedOpenApi;

import java.util.Collections;
import java.util.List;

/**
 * <h2>🎯 Resolvedor Inteligente de Grupos OpenAPI com Algoritmo "Best Match"</h2>
 * 
 * <h3>🎯 Problema Resolvido</h3>
 * <p>Antes desta implementação, a resolução de grupos retornava o primeiro match encontrado,
 * frequentemente resultando em "default" ou "application" em vez do grupo específico correto.
 * O algoritmo "best match" prioriza padrões mais específicos (mais longos).</p>
 * 
 * <h3>🧠 Algoritmo "Best Match"</h3>
 * <p>Em vez de retornar o primeiro padrão que faz match, este resolvedor:</p>
 * <ol>
 *   <li><strong>Avalia todos os padrões:</strong> Não para no primeiro match</li>
 *   <li><strong>Normaliza padrões:</strong> Remove wildcards (/** e /*) para comparação</li>
 *   <li><strong>Prioriza especificidade:</strong> Padrões mais longos têm prioridade</li>
 *   <li><strong>Retorna melhor match:</strong> Grupo com padrão mais específico</li>
 * </ol>
 * 
 * <h3>📊 Exemplo de Funcionamento</h3>
 * <pre>
 * Path de entrada: "/api/human-resources/eventos-folha/all"
 * 
 * Grupos registrados:
 * - Group: "application", Pattern: "/**"           → Normalizado: ""            (length: 0)
 * - Group: "api-hr-eventos", Pattern: "/api/human-resources/eventos-folha/**" 
 *                                                  → Normalizado: "/api/human-resources/eventos-folha" (length: 35)
 * 
 * Resultado: "api-hr-eventos" (melhor match com maior especificidade)
 * </pre>
 * 
 * <h3>⚡ Performance</h3>
 * <ul>
 *   <li><strong>O(n*m):</strong> n grupos × m padrões por grupo</li>
 *   <li><strong>Típico:</strong> 5-10 grupos com 1-2 padrões cada = ~10-20 operações</li>
 *   <li><strong>Cache upstream:</strong> ApiDocsController cacheia resultados</li>
 * </ul>
 * 
 * <h3>🔗 Integração</h3>
 * <p>Usado automaticamente pelo ApiDocsController para resolver grupos baseados
 * no path da requisição, eliminando necessidade de parâmetro 'document' manual.</p>
 * 
 * @see org.praxisplatform.uischema.controller.docs.ApiDocsController#resolveGroupFromPath(String)
 * @see org.praxisplatform.uischema.configuration.DynamicSwaggerConfig
 */
public class OpenApiGroupResolver {

    private final List<GroupedOpenApi> groupedOpenApis;

    public OpenApiGroupResolver(List<GroupedOpenApi> groupedOpenApis) {
        this.groupedOpenApis = groupedOpenApis == null ? Collections.emptyList() : groupedOpenApis;
    }

    /**
     * <h3>🎯 Método Principal - Algoritmo "Best Match"</h3>
     * <p>Este é o coração da resolução inteligente de grupos. Implementa o algoritmo
     * "best match" que prioriza padrões mais específicos sobre genéricos.</p>
     * 
     * <h4>🔄 Fluxo do Algoritmo:</h4>
     * <ol>
     *   <li><strong>Iteração:</strong> Percorre todos os GroupedOpenApi registrados</li>
     *   <li><strong>Extração:</strong> Obtém pathsToMatch de cada grupo</li>
     *   <li><strong>Normalização:</strong> Remove wildcards (/** e /*) dos padrões</li>
     *   <li><strong>Matching:</strong> Verifica se requestPath.startsWith(normalizedPattern)</li>
     *   <li><strong>Comparação:</strong> Prioriza padrões com maior length (mais específicos)</li>
     *   <li><strong>Resultado:</strong> Retorna grupo com padrão mais específico ou null</li>
     * </ol>
     * 
     * <h4>🏆 Vantagem do "Best Match":</h4>
     * <p>Resolve o problema clássico onde padrões genéricos como "/**" sempre faziam match primeiro,
     * mascarando padrões específicos como "/api/human-resources/eventos-folha/**".</p>
     * 
     * <h4>📊 Cenário Típico:</h4>
     * <pre>
     * requestPath: "/api/human-resources/eventos-folha/all"
     * 
     * Padrões disponíveis:
     * 1. "/**"                                    → normalized: ""                          (length: 0)
     * 2. "/api/human-resources/**"                → normalized: "/api/human-resources"     (length: 21) 
     * 3. "/api/human-resources/eventos-folha/**" → normalized: "/api/human-resources/eventos-folha" (length: 35)
     * 
     * Todos fazem match, mas o padrão 3 tem maior especificidade → grupo correspondente retornado
     * </pre>
     *
     * @param requestPath path da requisição HTTP (ex: "/api/human-resources/eventos-folha/all")
     * @return nome do grupo com melhor match ou {@code null} se nenhum padrão fizer match
     */
    public String resolveGroup(String requestPath) {
        if (requestPath == null) {
            return null;
        }
        
        String bestMatch = null;
        int bestMatchLength = 0;
        
        // 🔍 PASSO 1: Iterar todos os grupos registrados dinamicamente
        for (GroupedOpenApi groupedOpenApi : groupedOpenApis) {
            List<String> patterns = groupedOpenApi.getPathsToMatch();
            if (patterns == null) {
                continue;
            }
            
            // 🎯 PASSO 2: Avaliar cada padrão do grupo atual
            for (String pattern : patterns) {
                String normalized = normalize(pattern);
                
                // ✅ PASSO 3: Verificar se o path faz match com o padrão
                if (requestPath.startsWith(normalized)) {
                    // 🏆 PASSO 4: Priorizar matches mais específicos (padrões mais longos)
                    if (normalized.length() > bestMatchLength) {
                        bestMatch = groupedOpenApi.getGroup();
                        bestMatchLength = normalized.length();
                    }
                }
            }
        }
        
        return bestMatch;
    }

    /**
     * <h3>🧹 Normalização de Padrões</h3>
     * <p>Remove wildcards dos padrões de path para permitir comparação precisa de especificidade.</p>
     * 
     * <h4>🔄 Transformações:</h4>
     * <ul>
     *   <li><strong>"/**"</strong> → <strong>""</strong> (padrão mais genérico)</li>
     *   <li><strong>"/*"</strong> → <strong>""</strong> (padrão genérico)</li>
     *   <li><strong>"/api/human-resources/**"</strong> → <strong>"/api/human-resources"</strong></li>
     *   <li><strong>"/api/human-resources/eventos-folha/**"</strong> → <strong>"/api/human-resources/eventos-folha"</strong></li>
     * </ul>
     * 
     * <h4>🎯 Importância:</h4>
     * <p>A normalização permite comparar a especificidade real dos padrões baseada no
     * comprimento do path efetivo, não nos wildcards. Isso é crucial para o algoritmo
     * "best match" funcionar corretamente.</p>
     * 
     * @param pattern padrão original com wildcards (ex: "/api/human-resources/**")
     * @return padrão normalizado sem wildcards (ex: "/api/human-resources")
     */
    private String normalize(String pattern) {
        if (pattern == null) {
            return "";
        }
        
        // 🎯 Remove wildcard de múltiplos níveis: "/**"
        if (pattern.endsWith("/**")) {
            return pattern.substring(0, pattern.length() - 3);
        }
        
        // 🎯 Remove wildcard de nível único: "/*"  
        if (pattern.endsWith("/*")) {
            return pattern.substring(0, pattern.length() - 2);
        }
        
        // 📋 Retorna padrão inalterado se não tiver wildcards
        return pattern;
    }
}
