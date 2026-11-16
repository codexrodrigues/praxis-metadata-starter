package org.praxisplatform.uischema.repository.base;

import org.springframework.data.repository.NoRepositoryBean;

/**
 * Repositório base canônico orientado a recursos da plataforma Praxis, combinando
 * operações de persistência JPA e consultas dinâmicas governadas via
 * {@link org.springframework.data.jpa.domain.Specification}.
 * <p>
 * Marcado com {@link NoRepositoryBean} para evitar instanciação direta; deve ser
 * estendido pelos repositórios concretos de recursos nas aplicações.
 * </p>
 *
 * @param <E> tipo da entidade de domínio
 * @param <ID> tipo do identificador
 * @since 8.0.0
 */
@NoRepositoryBean
public interface BaseResourceRepository<E, ID> extends BaseCrudRepository<E, ID> {
}
