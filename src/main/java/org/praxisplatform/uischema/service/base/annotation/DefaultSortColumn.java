package org.praxisplatform.uischema.service.base.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * <h2>📊 Anotação para Ordenação Padrão Automática</h2>
 * 
 * <p>Define automaticamente a ordenação padrão para consultas JPA quando nenhuma ordenação 
 * explícita é fornecida via {@code Pageable} ou parâmetros de requisição.</p>
 * 
 * <h3>🎯 Objetivo</h3>
 * <p>Elimina a necessidade de especificar ordenação em cada consulta, fornecendo uma 
 * ordenação sensata e consistente por padrão para listagens e filtros.</p>
 * 
 * <h3>⚙️ Como Funciona</h3>
 * <ol>
 *   <li><strong>Detecção:</strong> {@link org.praxisplatform.uischema.service.base.BaseCrudService#getDefaultSort()} 
 *       escaneia campos com esta anotação</li>
 *   <li><strong>Priorização:</strong> Campos são ordenados por prioridade (menor número = maior prioridade)</li>
 *   <li><strong>Aplicação:</strong> Ordenação aplicada automaticamente quando {@code Pageable.getSort().isSorted() == false}</li>
 * </ol>
 * 
 * <h3>📋 Parâmetros</h3>
 * <ul>
 *   <li><strong>ascending:</strong> {@code true} = ASC, {@code false} = DESC (padrão: {@code true})</li>
 *   <li><strong>priority:</strong> Prioridade da ordenação - menor valor = maior prioridade (padrão: {@code 0})</li>
 * </ul>
 * 
 * <h3>🔄 Exemplos de Uso</h3>
 * 
 * <h4>Ordenação Simples:</h4>
 * <pre>{@code
 * @Entity
 * public class Cliente {
 *     @Id
 *     private Long id;
 *     
 *     @DefaultSortColumn  // Ordenação alfabética por padrão
 *     private String nome;
 * }
 * 
 * // Resultado: ORDER BY nome ASC (quando nenhum sort é especificado)
 * }</pre>
 * 
 * <h4>Ordenação Múltipla com Prioridades:</h4>
 * <pre>{@code
 * @Entity
 * public class Funcionario {
 *     @DefaultSortColumn(priority = 1, ascending = true)
 *     private String departamento;
 *     
 *     @DefaultSortColumn(priority = 2, ascending = true) 
 *     private String nomeCompleto;
 * }
 * 
 * // Resultado: ORDER BY departamento ASC, nomeCompleto ASC
 * }</pre>
 * 
 * <h4>Ordenação por Data (Mais Recente Primeiro):</h4>
 * <pre>{@code
 * @Entity
 * public class Noticia {
 *     @DefaultSortColumn(ascending = false)
 *     private LocalDateTime dataPublicacao;
 * }
 * 
 * // Resultado: ORDER BY dataPublicacao DESC (notícias mais recentes primeiro)
 * }</pre>
 * 
 * <h3>🔗 Integração com APIs</h3>
 * 
 * <h4>Sem parâmetro sort (usa @DefaultSortColumn):</h4>
 * <pre>
 * GET /api/funcionarios/all
 * → Aplica ordenação: ORDER BY departamento ASC, nomeCompleto ASC
 * </pre>
 * 
 * <h4>Com parâmetro sort (ignora @DefaultSortColumn):</h4>
 * <pre>
 * GET /api/funcionarios/all?sort=salario,desc
 * → Aplica ordenação: ORDER BY salario DESC
 * </pre>
 * 
 * <h3>⚡ Aplicação Automática</h3>
 * <p>A ordenação é aplicada automaticamente nos seguintes métodos:</p>
 * <ul>
 *   <li>{@code GET /{resource}/all} - Lista completa sem filtros</li>
 *   <li>{@code POST /{resource}/filter} - Lista filtrada com paginação</li>
 *   <li>Qualquer método que use {@link org.praxisplatform.uischema.service.base.BaseCrudService#findAll()}</li>
 * </ul>
 * 
 * <h3>🎯 Benefícios</h3>
 * <ul>
 *   <li><strong>UX Consistente:</strong> Usuários sempre veem dados organizados de forma lógica</li>
 *   <li><strong>Performance:</strong> Evita scans desnecessários com ordenação inteligente</li>
 *   <li><strong>Zero Configuração:</strong> Funciona automaticamente sem código adicional</li>
 *   <li><strong>Flexível:</strong> Pode ser sobrescrita via parâmetros de requisição</li>
 * </ul>
 * 
 * <h3>⚠️ Considerações</h3>
 * <ul>
 *   <li>Usar apenas em campos <strong>indexados</strong> para melhor performance</li>
 *   <li>Prioridades devem ser <strong>únicas</strong> para ordem determinística</li>
 *   <li>Evitar ordenação por campos de relacionamentos profundos (N+1 queries)</li>
 * </ul>
 * 
 * @see org.praxisplatform.uischema.service.base.BaseCrudService#getDefaultSort()
 * @see org.praxisplatform.uischema.controller.base.AbstractCrudController
 * @since 1.0.0
 */


@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DefaultSortColumn {
    boolean ascending() default true;
    int priority() default 0;
}
