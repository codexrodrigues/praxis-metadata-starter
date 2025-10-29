package org.praxisplatform.uischema.repository.base;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.NoRepositoryBean;

@NoRepositoryBean
public interface BaseCrudRepository<E, ID> extends JpaRepository<E, ID>, JpaSpecificationExecutor<E> {
}
