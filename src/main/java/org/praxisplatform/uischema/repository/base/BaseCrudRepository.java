package org.praxisplatform.uischema.repository.base;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.NoRepositoryBean;

/**
 * Repositório base com suporte a CRUD e Specifications.
 * <p>
 * Marcado com {@link NoRepositoryBean} para evitar instanciação direta; deve ser
 * estendido por repositórios concretos nas aplicações.
 * </p>
 *
 * @param <E> tipo da entidade
 * @param <ID> tipo do identificador
 * @since 1.0.0
 */
@NoRepositoryBean
public interface BaseCrudRepository<E, ID> extends JpaRepository<E, ID>, JpaSpecificationExecutor<E> {
}
