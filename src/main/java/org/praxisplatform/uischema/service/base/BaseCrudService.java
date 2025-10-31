package org.praxisplatform.uischema.service.base;

import org.praxisplatform.uischema.dto.OptionDTO;
import org.praxisplatform.uischema.dto.CursorPage;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.filter.specification.GenericSpecification;
import org.praxisplatform.uischema.filter.specification.GenericSpecificationsBuilder;
import org.praxisplatform.uischema.mapper.base.OptionMapper;
import org.praxisplatform.uischema.repository.base.BaseCrudRepository;
import org.praxisplatform.uischema.service.base.annotation.DefaultSortColumn;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Interface base para operações CRUD e paginação com filtragem.
 *
 * @param <E>  Tipo da entidade
 * @param <D>  Tipo do DTO
 * @param <ID> Tipo do identificador
 * @param <FD> Tipo do DTO de filtro
 */
public interface BaseCrudService<E, D, ID, FD extends GenericFilterDTO> {

    BaseCrudRepository<E, ID> getRepository();
    GenericSpecificationsBuilder<E> getSpecificationsBuilder();
    Class<E> getEntityClass(); // Classe da entidade
    /**
     * Retorna um {@link OptionMapper} padrão que projeta entidades para {@link OptionDTO}.
     * <p>
     * Implementações podem sobrescrever para customizar o mapeamento. Quando não sobrescrito,
     * o mapper padrão usa {@link #extractId(Object)} para o id e {@link #computeOptionLabel(Object)}
     * para o label. O campo {@code extra} permanece {@code null} para manter o payload leve.
     * </p>
     * <p>
     * Observação de performance: a computação do label utiliza reflexão apenas durante a projeção
     * de opções (endpoints de options). Anotar a propriedade com {@code @OptionLabel} evita a
     * varredura por heurísticas.
     * </p>
     */
    default OptionMapper<E, ID> getOptionMapper() {
        return entity -> new OptionDTO<>(extractId(entity), computeOptionLabel(entity), null);
    }

    /**
     * Retorna uma string representando a versão atual do dataset.
     * <p>
     * Implementações podem sobrescrever este método para expor um valor que
     * permita ao cliente invalidar caches quando os dados mudarem. Em cenários
     * corporativos, uma implementação típica calcula um hash ou timestamp a
     * partir da coluna {@code updatedAt} para que o frontend possa detectar
     * mudanças sem precisar refazer consultas pesadas.
     * </p>
     *
     * <pre>{@code
     * // Exemplo de implementação
     * @Override
     * public Optional<String> getDatasetVersion() {
     *     return repository.maxUpdatedAt().map(Instant::toString);
     * }
     * }</pre>
     *
     * @return versão do dataset, quando disponível
     */
    default Optional<String> getDatasetVersion() { return Optional.empty(); }
    /**
     * <h3>📋 Lista Todas as Entidades com Ordenação Padrão</h3>
     * 
     * <p>Retorna todos os registros da entidade aplicando automaticamente a ordenação 
     * definida via anotações {@link DefaultSortColumn}.</p>
     * 
     * <h4>🔄 Comportamento:</h4>
     * <ul>
     *   <li>Aplica {@link #getDefaultSort()} automaticamente</li>
     *   <li>Se nenhum @DefaultSortColumn definido, retorna sem ordenação específica</li>
     *   <li>Ideal para listagens completas onde ordem consistente é importante</li>
     * </ul>
     * 
     * <h4>📋 Exemplo de Uso:</h4>
     * <pre>{@code
     * // No Service:
     * List<Funcionario> funcionarios = findAll();
     * 
     * // SQL gerado (se @DefaultSortColumn(priority=1) em 'nome'):
     * SELECT * FROM funcionarios ORDER BY nome ASC
     * }</pre>
     * 
     * @return Lista de todas as entidades ordenadas conforme @DefaultSortColumn
     * @see #getDefaultSort()
     * @see DefaultSortColumn
     */
    default List<E> findAll() { return getRepository().findAll(getDefaultSort()); }

    /**
     * Recupera uma entidade pelo identificador.
     * @param id identificador da entidade
     * @return entidade encontrada
     * @throws jakarta.persistence.EntityNotFoundException quando não encontrada
     */
    default E findById(ID id) { return getRepository().findById(id).orElseThrow(this::getNotFoundException); }

    /**
     * Recupera múltiplas entidades pelos seus identificadores.
     * <p>
     * Retorna uma lista vazia quando a coleção de IDs é {@code null} ou vazia,
     * evitando consultas desnecessárias ao banco de dados.
     * </p>
     *
     * @param ids coleção de identificadores a serem buscados
     * @return lista de entidades correspondentes aos IDs fornecidos
     */
    default List<E> findAllById(Collection<ID> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return getRepository().findAllById(ids);
    }

    @SuppressWarnings("unchecked")
    default ID extractId(E entity) {
        try {
            return (ID) entity.getClass().getMethod("getId").invoke(entity);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to extract id", e);
        }
    }

    /**
     * Persiste uma nova entidade.
     * @param entity instância a ser salva
     * @return entidade salva (com ID)
     */
    default E save(E entity) { return getRepository().save(entity); }
    default E mergeUpdate(E existing, E update) {
        return existing;
    }

    /**
     * Tenta computar um label textual amigável para a entidade informada.
     * <p>
     * Estratégia de resolução:
     * <ol>
     *   <li>Se existir método ou campo anotado com uma anotação chamada <code>OptionLabel</code>
     *       (qualquer pacote), usa seu valor convertido para String. Métodos são verificados antes
     *       de campos. Suporta herança.</li>
     *   <li>Caso contrário, tenta invocar (case-insensitive, na ordem) os getters
     *       <code>getLabel</code>, <code>getNomeCompleto</code>, <code>getNome</code>,
     *       <code>getDescricao</code>, <code>getTitle</code>.</li>
     *   <li>Se nada encontrado ou valor nulo/vazio, faz fallback para {@code String.valueOf(id)}.
     *   </li>
     * </ol>
     * </p>
     */
    default String computeOptionLabel(E entity) {
        if (entity == null) return null;

        Class<?> clazz = entity.getClass();

        // 1) Procurar método anotado com @OptionLabel
        for (Method m : getAllMethods(clazz)) {
            if (hasOptionLabelAnnotation(m)) {
                Object value = invokeSilently(entity, m);
                String s = toNonBlankString(value);
                if (s != null) return s;
            }
        }

        // 1b) Procurar campo anotado com @OptionLabel
        for (Field f : getAllFields(clazz)) {
            if (hasOptionLabelAnnotation(f)) {
                Object value = getFieldValueSilently(entity, f);
                String s = toNonBlankString(value);
                if (s != null) return s;
            }
        }

        // 2) Heurística de getters comuns (case-insensitive)
        String[] candidates = {"getLabel", "getNomeCompleto", "getNome", "getDescricao", "getTitle"};
        Map<String, Method> methodIndex = Arrays.stream(getAllMethods(clazz))
                .filter(m -> m.getParameterCount() == 0)
                .collect(Collectors.toMap(m -> m.getName().toLowerCase(), Function.identity(), (a, b) -> a));
        for (String candidate : candidates) {
            Method m = methodIndex.get(candidate.toLowerCase());
            if (m != null) {
                Object value = invokeSilently(entity, m);
                String s = toNonBlankString(value);
                if (s != null) return s;
            }
        }

        // 3) Fallback: id como String
        ID id = extractId(entity);
        return String.valueOf(id);
    }

    // ==== Reflection helpers (somente utilitários internos) ====
    private boolean hasOptionLabelAnnotation(Method m) {
        return Arrays.stream(m.getAnnotations())
                .anyMatch(a -> isOptionLabelAnnotation(a.annotationType()));
    }

    private boolean hasOptionLabelAnnotation(Field f) {
        return Arrays.stream(f.getAnnotations())
                .anyMatch(a -> isOptionLabelAnnotation(a.annotationType()));
    }

    private boolean isOptionLabelAnnotation(Class<?> annotationType) {
        String simple = annotationType.getSimpleName();
        if ("OptionLabel".equals(simple)) return true;
        String name = annotationType.getName();
        return name.endsWith(".OptionLabel");
    }

    private String toNonBlankString(Object value) {
        if (value == null) return null;
        String s = String.valueOf(value);
        return (s != null && !s.isBlank()) ? s : null;
    }

    private Object invokeSilently(Object target, Method m) {
        try {
            if (!m.canAccess(target)) m.setAccessible(true);
            return m.invoke(target);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private Object getFieldValueSilently(Object target, Field f) {
        try {
            if (!f.canAccess(target)) f.setAccessible(true);
            return f.get(target);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    /**
     * Atualiza uma entidade existente.
     * @param id identificador da entidade a atualizar
     * @param entity dados a serem mesclados e persistidos
     * @return entidade atualizada
     * @throws jakarta.persistence.EntityNotFoundException quando a entidade não existe
     */
    default E update(ID id, E entity) {
        return getRepository()
                .findById(id)
                .map(existing -> mergeUpdate(existing, entity))
                .map(existing ->getRepository().save(existing))
                .orElseThrow(this::getNotFoundException);
    }

    /**
     * Exclui uma entidade pelo identificador (ignora quando inexistente).
     * @param id identificador da entidade a excluir
     */
    default void deleteById(ID id) { getRepository().findById(id).ifPresent(e -> getRepository().delete(e)); }

    /**
     * Exclui todos os registros correspondentes aos IDs fornecidos.
     *
     * @param ids Coleção de identificadores a serem removidos
     */
    default void deleteAllById(Iterable<ID> ids) {
        if (ids == null) {
            throw new IllegalArgumentException("ids must not be null");
        }
        getRepository().deleteAllById(ids);
    }

    /**
     * <h3>📄 Paginação com Ordenação Padrão Inteligente</h3>
     * 
     * <p>Retorna uma página de entidades aplicando ordenação padrão automaticamente 
     * quando nenhuma ordenação é especificada no {@code Pageable}.</p>
     * 
     * <h4>🧠 Lógica Inteligente:</h4>
     * <ol>
     *   <li><strong>Se Pageable tem ordenação:</strong> Usa a ordenação especificada</li>
     *   <li><strong>Se Pageable sem ordenação:</strong> Aplica {@link #getDefaultSort()} automaticamente</li>
     *   <li><strong>Se sem @DefaultSortColumn:</strong> Usa ordenação do banco (pode ser imprevisível)</li>
     * </ol>
     * 
     * <h4>📋 Exemplos de Comportamento:</h4>
     * <pre>{@code
     * // Caso 1: Com ordenação especificada
     * Pageable pageable = PageRequest.of(0, 10, Sort.by("salario").descending());
     * Page<Funcionario> page = findAll(pageable);
     * // → SQL: ... ORDER BY salario DESC LIMIT 10
     * 
     * // Caso 2: Sem ordenação (aplica @DefaultSortColumn automaticamente)
     * Pageable pageable = PageRequest.of(0, 10);
     * Page<Funcionario> page = findAll(pageable);  
     * // → SQL: ... ORDER BY nome ASC LIMIT 10 (se @DefaultSortColumn no campo nome)
     * }</pre>
     * 
     * <h4>🔗 Uso em Controllers:</h4>
     * <pre>{@code
     * // Controller recebe Pageable do cliente:
     * @GetMapping("/all")
     * public ResponseEntity<Page<FuncionarioDTO>> getAll(Pageable pageable) {
     *     Page<Funcionario> page = service.findAll(pageable); // Ordenação aplicada aqui
     *     return ResponseEntity.ok(page.map(this::toDto));
     * }
     * 
     * // URLs suportadas:
     * GET /api/funcionarios/all?page=0&amp;size=10                    // Usa @DefaultSortColumn
     * GET /api/funcionarios/all?page=0&amp;size=10&amp;sort=nome,asc      // Usa ordenação específica
     * GET /api/funcionarios/all?page=0&amp;size=10&amp;sort=salario,desc  // Usa ordenação específica
     * }</pre>
     * 
     * @param pageable Parâmetros de paginação e ordenação opcional
     * @return Página de entidades com ordenação aplicada
     * @see #getDefaultSort()
     * @see DefaultSortColumn
     */
    default Page<E> findAll(Pageable pageable) {
        Pageable sortedPageable = pageable;
        if (!pageable.getSort().isSorted()) {
            sortedPageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), getDefaultSort());
        }
        return getRepository().findAll(sortedPageable);
    }

    /**
     * <h3>🔍 Filtro Avançado com Ordenação Padrão Inteligente</h3>
     * 
     * <p>Aplica filtros dinâmicos baseados em DTO de filtro e paginação, com ordenação 
     * padrão aplicada automaticamente quando não especificada.</p>
     * 
     * <h4>🔄 Fluxo de Processamento:</h4>
     * <ol>
     *   <li><strong>Ordenação:</strong> Aplica {@link #getDefaultSort()} se Pageable sem ordenação</li>
     *   <li><strong>Filtros:</strong> Constrói JPA Specification baseada no FilterDTO</li>
     *   <li><strong>Consulta:</strong> Executa query otimizada com filtros + paginação + ordenação</li>
     * </ol>
     * 
     * <h4>📋 Exemplo de Uso Completo:</h4>
     * <pre>{@code
     * // Entidade com ordenação padrão:
     * @Entity
     * public class Funcionario {
     *     @DefaultSortColumn(priority = 1)
     *     private String departamento;
     *     
     *     @DefaultSortColumn(priority = 2)  
     *     private String nome;
     * }
     * 
     * // DTO de filtro:
     * public class FuncionarioFilterDTO extends GenericFilterDTO {
     *     @Filterable(operation = CONTAINS)
     *     private String nome;
     *     
     *     @Filterable(operation = EQUALS)
     *     private String departamento;
     * }
     * 
     * // No Controller:
     * @PostMapping("/filter")
     * public Page<FuncionarioDTO> filter(@RequestBody FuncionarioFilterDTO filterDTO, Pageable pageable) {
     *     return service.filter(filterDTO, pageable).map(this::toDto);
     * }
     * }</pre>
     * 
     * <h4>🌐 Exemplos de Requisições:</h4>
     * <pre>
     * POST /api/funcionarios/filter?page=0&amp;size=10
     * Body: {"nome": "João", "departamento": "TI"}
     * → SQL: SELECT * FROM funcionarios 
     *        WHERE nome LIKE '%João%' AND departamento = 'TI'
     *        ORDER BY departamento ASC, nome ASC  -- @DefaultSortColumn aplicada
     *        LIMIT 10
     * 
     * POST /api/funcionarios/filter?page=0&amp;size=10&amp;sort=salario,desc  
     * Body: {"nome": "Maria"}
     * → SQL: SELECT * FROM funcionarios 
     *        WHERE nome LIKE '%Maria%'
     *        ORDER BY salario DESC  -- ordenação específica sobrescreve @DefaultSortColumn
     *        LIMIT 10
     * </pre>
     * 
     * <h4>🎯 Benefícios:</h4>
     * <ul>
     *   <li><strong>Flexibilidade:</strong> Combina filtros dinâmicos com ordenação inteligente</li>
     *   <li><strong>Performance:</strong> Queries otimizadas com índices corretos</li>
     *   <li><strong>UX Consistente:</strong> Resultados sempre organizados de forma lógica</li>
     *   <li><strong>Zero Config:</strong> Funciona automaticamente sem código extra</li>
     * </ul>
     * 
     * @param filterDTO DTO com critérios de filtro anotados com @Filterable
     * @param pageable Parâmetros de paginação e ordenação opcional  
     * @return Página filtrada de entidades com ordenação aplicada
     * @see #getDefaultSort()
     * @see DefaultSortColumn
     * @see org.praxisplatform.uischema.filter.annotation.Filterable
     */
    default Page<E> filter(FD filterDTO, Pageable pageable) {
        Pageable sortedPageable = pageable;
        if (!pageable.getSort().isSorted()) {
            sortedPageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), getDefaultSort());
        }

        GenericSpecification<E> specification = getSpecificationsBuilder().buildSpecification(filterDTO, sortedPageable);
        return getRepository().findAll(specification.spec(), specification.pageable());

    }

    /**
     * Aplica o filtro informado e injeta entidades adicionais no topo da primeira página.
     * <p>
     * Os IDs fornecidos em {@code includeIds} são removidos da página retornada para evitar
     * duplicação. Quando a página solicitada for a primeira ({@code page = 0}), entidades
     * ausentes são buscadas via {@link #findAllById(Collection)} e inseridas no topo na mesma
     * ordem dos IDs fornecidos. Nas páginas subsequentes apenas a remoção de duplicados é
     * realizada, evitando que itens sejam reexibidos em múltiplas páginas.
     * </p>
     *
     * @param filter      critérios de filtro
     * @param pageable    informações de paginação
     * @param includeIds  IDs que devem aparecer no topo da primeira página
     * @return página resultante com injeção opcional de entidades
     */
    default Page<E> filterWithIncludeIds(FD filter, Pageable pageable, Collection<ID> includeIds) {
        Page<E> page = filter(filter, pageable);
        if (includeIds == null || includeIds.isEmpty()) return page;

        // Usamos LinkedHashSet para preservar a ordem e eliminar duplicados
        Set<ID> orderedIds = new LinkedHashSet<>(includeIds);

        Map<ID, E> ensured = new HashMap<>();
        List<E> remaining = new ArrayList<>();
        for (E e : page.getContent()) {
            ID id = extractId(e);
            if (orderedIds.contains(id)) ensured.put(id, e); else remaining.add(e);
        }

        if (pageable.getPageNumber() != 0) {
            return new PageImpl<>(remaining, pageable, page.getTotalElements());
        }

        List<ID> missing = orderedIds.stream().filter(id -> !ensured.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            findAllById(missing).forEach(e -> ensured.put(extractId(e), e));
        }

        List<E> merged = new ArrayList<>(orderedIds.size() + remaining.size());
        orderedIds.forEach(id -> {
            E e = ensured.get(id);
            if (e != null) merged.add(e);
        });
        merged.addAll(remaining);

        return new PageImpl<>(merged, pageable, page.getTotalElements());
    }

    /**
     * Executa o filtro utilizando paginação por cursor.
     * <p>
     * Implementações devem aplicar uma ordenação estável e retornar
     * os cursores codificados para navegação. O método padrão lança
     * {@link UnsupportedOperationException} indicando que a entidade
     * ainda não suporta paginação por cursor.
     * </p>
     *
     * @param filter critérios de filtro
     * @param sort   ordenação estável a ser aplicada
     * @param after  cursor para avançar na lista
     * @param before cursor para retroceder na lista
     * @param size   quantidade de registros a recuperar
     * @return página baseada em cursor
     * @throws UnsupportedOperationException caso não seja implementado
     */
    default CursorPage<E> filterByCursor(FD filter, Sort sort, String after, String before, int size) {
        throw new UnsupportedOperationException("Cursor pagination not implemented");
    }

    /**
     * Localiza a posição absoluta de um registro considerando um filtro e ordenação.
     * <p>
     * Implementações devem retornar o índice zero-based do registro dentro do
     * conjunto filtrado. O método padrão retorna {@link OptionalLong#empty()},
     * indicando que a entidade não suporta a operação.
     * </p>
     *
     * @param filter critérios de filtro
     * @param sort   ordenação aplicada
     * @param id     identificador do registro
     * @return posição absoluta quando suportado
     */
    default OptionalLong locate(FD filter, Sort sort, ID id) {
        return OptionalLong.empty();
    }

    /**
     * Executa o filtro padrão e projeta cada entidade para {@link OptionDTO} usando
     * o {@link OptionMapper} configurado.
     *
     * @param filter   critérios de filtro
     * @param pageable informações de paginação
     * @return página de opções reduzidas
     */
    default Page<OptionDTO<ID>> filterOptions(FD filter, Pageable pageable) {
        return filter(filter, pageable).map(getOptionMapper()::toOption);
    }

    /**
     * Busca entidades pelos IDs fornecidos e as projeta para {@link OptionDTO},
     * preservando a ordem da coleção de entrada.
     *
     * @param ids identificadores a serem buscados
     * @return lista de opções na ordem solicitada
     */
    default List<OptionDTO<ID>> byIdsOptions(Collection<ID> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        List<OptionDTO<ID>> list = findAllById(ids).stream()
                .map(getOptionMapper()::toOption)
                .toList();
        Map<ID, OptionDTO<ID>> byId = list.stream()
                .collect(Collectors.toMap(OptionDTO::id, Function.identity()));
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    /**
     * <h3>📊 Constrói Ordenação Padrão Baseada em Anotações @DefaultSortColumn</h3>
     * 
     * <p>Escaneia a classe da entidade em busca de campos anotados com {@link DefaultSortColumn}
     * e constrói automaticamente um objeto {@code Sort} para uso em consultas JPA.</p>
     * 
     * <h4>🔄 Algoritmo de Processamento:</h4>
     * <ol>
     *   <li><strong>Escaneamento:</strong> Percorre todos os campos da entidade (incluindo herança)</li>
     *   <li><strong>Filtragem:</strong> Identifica campos com @DefaultSortColumn</li>
     *   <li><strong>Ordenação:</strong> Ordena por prioridade (menor valor = maior prioridade)</li>
     *   <li><strong>Construção:</strong> Monta Sort.Order para cada campo</li>
     * </ol>
     * 
     * <h4>📋 Exemplo de Uso:</h4>
     * <pre>{@code
     * // Na entidade:
     * @Entity
     * public class Produto {
     *     @DefaultSortColumn(priority = 1, ascending = false)
     *     private LocalDateTime dataLancamento;
     *     
     *     @DefaultSortColumn(priority = 2, ascending = true)
     *     private String nome;
     * }
     * 
     * // Resultado deste método:
     * Sort.by(
     *     Sort.Order.desc("dataLancamento"),    // prioridade 1
     *     Sort.Order.asc("nome")                // prioridade 2  
     * )
     * 
     * // SQL gerado:
     * ORDER BY dataLancamento DESC, nome ASC
     * }</pre>
     * 
     * <h4>⚡ Performance:</h4>
     * <ul>
     *   <li><strong>Reflection Cache:</strong> Fields são cached pelo JVM</li>
     *   <li><strong>Lazy Execution:</strong> Só executa quando ordenação não especificada</li>
     *   <li><strong>Single Scan:</strong> Uma única varredura por classe</li>
     * </ul>
     * 
     * <h4>🔗 Aplicação Automática:</h4>
     * <p>Este método é chamado automaticamente por:</p>
     * <ul>
     *   <li>{@link #findAll()} - Lista simples</li>
     *   <li>{@link #findAll(Pageable)} - Quando Pageable.sort não está definido</li>
     *   <li>{@link #filter(GenericFilterDTO, Pageable)} - Quando Pageable.sort não está definido</li>
     * </ul>
     * 
     * @return {@link Sort} baseado nos campos @DefaultSortColumn ou {@link Sort#unsorted()} se nenhum campo anotado
     * @see DefaultSortColumn
     */
    default Sort getDefaultSort() {
        List<Field> sortedFields = getAllFields(getEntityClass()).stream()
                .filter(field -> field.isAnnotationPresent(DefaultSortColumn.class))
                .sorted(Comparator.comparingInt(field -> field.getAnnotation(DefaultSortColumn.class).priority()))
                .toList();

        if(sortedFields.isEmpty()) {
            return Sort.unsorted();
        }

        List<Sort.Order> orders = sortedFields.stream()
                .map(field -> {
                    DefaultSortColumn annotation = field.getAnnotation(DefaultSortColumn.class);
                    return new Sort.Order(
                            annotation.ascending() ? Sort.Direction.ASC : Sort.Direction.DESC,
                            field.getName()
                    );
                })
                .toList();

        return Sort.by(orders);
    }

    default EntityNotFoundException getNotFoundException() {
        return new EntityNotFoundException("Registro não encontrado");
    }

    // Helper method to get all fields from class and its superclasses
    private List<Field> getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        while (clazz != null && clazz != Object.class) {
            fields.addAll(Arrays.asList(clazz.getDeclaredFields()));
            clazz = clazz.getSuperclass();
        }
        return fields;
    }

    // Helper to get all methods from class and its superclasses
    private Method[] getAllMethods(Class<?> clazz) {
        List<Method> methods = new ArrayList<>();
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            methods.addAll(Arrays.asList(c.getDeclaredMethods()));
            c = c.getSuperclass();
        }
        return methods.toArray(new Method[0]);
    }
}
