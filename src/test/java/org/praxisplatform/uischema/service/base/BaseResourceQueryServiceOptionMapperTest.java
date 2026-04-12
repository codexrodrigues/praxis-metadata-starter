package org.praxisplatform.uischema.service.base;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.dto.OptionDTO;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.praxisplatform.uischema.filter.specification.GenericSpecificationsBuilder;
import org.praxisplatform.uischema.mapper.base.ResourceMapper;
import org.praxisplatform.uischema.repository.base.BaseCrudRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BaseResourceQueryServiceOptionMapperTest {

    static class AnnGetterEntity {
        private final Long id;
        private final String nome;

        AnnGetterEntity(Long id, String nome) {
            this.id = id;
            this.nome = nome;
        }

        public Long getId() { return id; }

        @org.praxisplatform.uischema.annotation.OptionLabel
        public String getNome() { return nome; }
    }

    static class AnnFieldEntity {
        private final Long id;

        @org.praxisplatform.uischema.annotation.OptionLabel
        private final String nome;

        AnnFieldEntity(Long id, String nome) {
            this.id = id;
            this.nome = nome;
        }

        public Long getId() { return id; }

        public String getNome() { return nome; }
    }

    static class ParentLabeled {
        private final Long id;
        private final String nomeCompleto;

        ParentLabeled(Long id, String nomeCompleto) {
            this.id = id;
            this.nomeCompleto = nomeCompleto;
        }

        public Long getId() { return id; }

        @org.praxisplatform.uischema.annotation.OptionLabel
        public String getNomeCompleto() { return nomeCompleto; }
    }

    static class ChildEntity extends ParentLabeled {
        ChildEntity(Long id, String nomeCompleto) {
            super(id, nomeCompleto);
        }
    }

    static class HeuristicEntity {
        private final Long id;
        private String label;
        private String nomeCompleto;
        private String nome;
        private String descricao;
        private String title;

        HeuristicEntity(Long id) { this.id = id; }

        public Long getId() { return id; }
        public String getLabel() { return label; }
        public String getNomeCompleto() { return nomeCompleto; }
        public String getNome() { return nome; }
        public String getDescricao() { return descricao; }
        public String getTitle() { return title; }

        public HeuristicEntity withLabel(String value) { this.label = value; return this; }
        public HeuristicEntity withNomeCompleto(String value) { this.nomeCompleto = value; return this; }
        public HeuristicEntity withNome(String value) { this.nome = value; return this; }
        public HeuristicEntity withDescricao(String value) { this.descricao = value; return this; }
        public HeuristicEntity withTitle(String value) { this.title = value; return this; }
    }

    static final class MinimalFilter implements GenericFilterDTO {}

    abstract static class SimpleService<E> extends AbstractBaseQueryResourceService<E, E, Long, MinimalFilter> {

        SimpleService(Class<E> entityClass) {
            super((BaseCrudRepository<E, Long>) null, new GenericSpecificationsBuilder<>(), entityClass);
        }

        @Override
        protected ResourceMapper<E, E, ?, ?, Long> getResourceMapper() {
            return new ResourceMapper<>() {
                @Override
                public E toResponse(E entity) {
                    return entity;
                }

                @Override
                public E newEntity(Object dto) {
                    throw new UnsupportedOperationException("query-only test service");
                }

                @Override
                public void applyUpdate(E entity, Object dto) {
                    throw new UnsupportedOperationException("query-only test service");
                }

                @Override
                public Long extractId(E entity) {
                    return SimpleService.this.extractId(entity);
                }
            };
        }

        @Override
        protected Long extractId(E entity) {
            try {
                return (Long) entity.getClass().getMethod("getId").invoke(entity);
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException(ex);
            }
        }

        @Override
        public Page<E> filter(MinimalFilter filter, Pageable pageable, Collection<Long> includeIds) {
            return Page.empty();
        }

        @Override
        protected List<E> findEntitiesById(Collection<Long> ids) {
            return List.of();
        }
    }

    @Test
    void usesOptionLabelOnGetter() {
        AnnGetterEntity entity = new AnnGetterEntity(10L, "Nome Getter");
        SimpleService<AnnGetterEntity> service = new SimpleService<>(AnnGetterEntity.class) {};

        OptionDTO<Long> option = service.getOptionMapper().toOption(entity);

        assertEquals(10L, option.id());
        assertEquals("Nome Getter", option.label());
        assertNull(option.extra());
    }

    @Test
    void usesOptionLabelOnField() {
        AnnFieldEntity entity = new AnnFieldEntity(11L, "Nome Field");
        SimpleService<AnnFieldEntity> service = new SimpleService<>(AnnFieldEntity.class) {};

        OptionDTO<Long> option = service.getOptionMapper().toOption(entity);

        assertEquals(11L, option.id());
        assertEquals("Nome Field", option.label());
    }

    @Test
    void detectsAnnotationOnSuperclass() {
        ChildEntity entity = new ChildEntity(21L, "DaSuperclasse");
        SimpleService<ChildEntity> service = new SimpleService<>(ChildEntity.class) {};

        OptionDTO<Long> option = service.getOptionMapper().toOption(entity);

        assertEquals(21L, option.id());
        assertEquals("DaSuperclasse", option.label());
    }

    @Test
    void usesHeuristicGettersInOrder() {
        HeuristicEntity entity = new HeuristicEntity(12L)
                .withNome("NOME")
                .withNomeCompleto("NOME COMPLETO");
        SimpleService<HeuristicEntity> service = new SimpleService<>(HeuristicEntity.class) {};

        OptionDTO<Long> first = service.getOptionMapper().toOption(entity);
        assertEquals("NOME COMPLETO", first.label());

        entity.withLabel("LABEL");
        OptionDTO<Long> second = service.getOptionMapper().toOption(entity);
        assertEquals("LABEL", second.label());
    }

    @Test
    void fallsBackToIdStringWhenNoLabel() {
        class OnlyId {
            private final Long id;

            OnlyId(Long id) { this.id = id; }

            public Long getId() { return id; }
        }

        OnlyId entity = new OnlyId(13L);
        SimpleService<OnlyId> service = new SimpleService<>(OnlyId.class) {};

        OptionDTO<Long> option = service.getOptionMapper().toOption(entity);

        assertEquals("13", option.label());
    }

    @Test
    void filterOptionsProjectsLabelsForNonJpaStub() {
        AnnGetterEntity first = new AnnGetterEntity(1L, "A");
        AnnGetterEntity second = new AnnGetterEntity(2L, "B");

        SimpleService<AnnGetterEntity> service = new SimpleService<>(AnnGetterEntity.class) {
            @Override
            public Page<AnnGetterEntity> filter(MinimalFilter filter, Pageable pageable, Collection<Long> includeIds) {
                return new PageImpl<>(List.of(first, second), PageRequest.of(0, 2), 2);
            }

            @Override
            public Page<OptionDTO<Long>> filterOptions(MinimalFilter filter, Pageable pageable) {
                return filter(filter, pageable, List.of()).map(getOptionMapper()::toOption);
            }
        };

        Page<OptionDTO<Long>> page = service.filterOptions(new MinimalFilter(), PageRequest.of(0, 2));

        assertEquals(2, page.getTotalElements());
        assertEquals(1L, page.getContent().get(0).id());
        assertEquals("A", page.getContent().get(0).label());
        assertEquals(2L, page.getContent().get(1).id());
        assertEquals("B", page.getContent().get(1).label());
    }

    @Test
    void byIdsOptionsPreservesOrderForNonJpaStub() {
        HeuristicEntity first = new HeuristicEntity(1L).withNome("N1");
        HeuristicEntity second = new HeuristicEntity(2L).withNome("N2");
        HeuristicEntity third = new HeuristicEntity(3L).withNome("N3");

        SimpleService<HeuristicEntity> service = new SimpleService<>(HeuristicEntity.class) {
            @Override
            protected List<HeuristicEntity> findEntitiesById(Collection<Long> ids) {
                return List.of(third, first, second);
            }
        };

        List<OptionDTO<Long>> options = service.byIdsOptions(List.of(2L, 1L, 3L));

        assertEquals(List.of(2L, 1L, 3L), options.stream().map(OptionDTO::id).toList());
        assertEquals(List.of("N2", "N1", "N3"), options.stream().map(OptionDTO::label).toList());
    }
}
