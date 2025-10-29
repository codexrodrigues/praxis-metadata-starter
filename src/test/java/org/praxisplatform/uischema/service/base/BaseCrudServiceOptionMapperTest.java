package org.praxisplatform.uischema.service.base;

import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.dto.OptionDTO;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BaseCrudServiceOptionMapperTest {

    // ---- Entities used in tests ----
    static class AnnGetterEntity {
        private Long id;
        private String nome;
        AnnGetterEntity(Long id, String nome) { this.id = id; this.nome = nome; }
        public Long getId() { return id; }
        @org.praxisplatform.uischema.annotation.OptionLabel
        public String getNome() { return nome; }
    }

    static class AnnFieldEntity {
        private Long id;
        @org.praxisplatform.uischema.annotation.OptionLabel
        private String nome;
        AnnFieldEntity(Long id, String nome) { this.id = id; this.nome = nome; }
        public Long getId() { return id; }
        public String getNome() { return nome; }
    }

    static class ParentLabeled {
        private Long id; private String nomeCompleto;
        public ParentLabeled(Long id, String nomeCompleto) { this.id = id; this.nomeCompleto = nomeCompleto; }
        public Long getId() { return id; }
        @org.praxisplatform.uischema.annotation.OptionLabel
        public String getNomeCompleto() { return nomeCompleto; }
    }
    static class ChildEntity extends ParentLabeled {
        public ChildEntity(Long id, String nomeCompleto) { super(id, nomeCompleto); }
    }

    static class HeuristicEntity {
        private Long id; private String label; private String nomeCompleto; private String nome; private String descricao; private String title;
        HeuristicEntity(Long id) { this.id = id; }
        public Long getId() { return id; }
        public String getLabel() { return label; }
        public String getNomeCompleto() { return nomeCompleto; }
        public String getNome() { return nome; }
        public String getDescricao() { return descricao; }
        public String getTitle() { return title; }
        public HeuristicEntity withLabel(String s) { this.label = s; return this; }
        public HeuristicEntity withNomeCompleto(String s) { this.nomeCompleto = s; return this; }
        public HeuristicEntity withNome(String s) { this.nome = s; return this; }
        public HeuristicEntity withDescricao(String s) { this.descricao = s; return this; }
        public HeuristicEntity withTitle(String s) { this.title = s; return this; }
    }

    static class MinimalFilter implements GenericFilterDTO {}

    // ---- Minimal service stub avoiding repository usage ----
    interface SimpleService<E> extends BaseCrudService<E, Object, Long, MinimalFilter> {
        @Override
        default Class<E> getEntityClass() { throw new UnsupportedOperationException(); }

        // Avoid repository-based default methods by overriding when used in tests
        @Override
        default Page<E> filter(MinimalFilter filterDTO, Pageable pageable) { return Page.empty(); }

        @Override
        default java.util.List<E> findAllById(Collection<Long> ids) { return java.util.List.of(); }
    }

    // ---- Tests ----
    @Test
    void usesOptionLabelOnGetter() {
        AnnGetterEntity e = new AnnGetterEntity(10L, "Nome Getter");

        SimpleService<AnnGetterEntity> service = new SimpleService<>() {
            @Override public org.praxisplatform.uischema.repository.base.BaseCrudRepository<AnnGetterEntity, Long> getRepository() { return null; }
            @Override public org.praxisplatform.uischema.filter.specification.GenericSpecificationsBuilder<AnnGetterEntity> getSpecificationsBuilder() { return null; }
            @Override public Class<AnnGetterEntity> getEntityClass() { return AnnGetterEntity.class; }
        };

        OptionDTO<Long> option = service.getOptionMapper().toOption(e);
        assertEquals(10L, option.id());
        assertEquals("Nome Getter", option.label());
        assertNull(option.extra());
    }

    @Test
    void usesOptionLabelOnField() {
        AnnFieldEntity e = new AnnFieldEntity(11L, "Nome Field");

        SimpleService<AnnFieldEntity> service = new SimpleService<>() {
            @Override public org.praxisplatform.uischema.repository.base.BaseCrudRepository<AnnFieldEntity, Long> getRepository() { return null; }
            @Override public org.praxisplatform.uischema.filter.specification.GenericSpecificationsBuilder<AnnFieldEntity> getSpecificationsBuilder() { return null; }
            @Override public Class<AnnFieldEntity> getEntityClass() { return AnnFieldEntity.class; }
        };

        OptionDTO<Long> option = service.getOptionMapper().toOption(e);
        assertEquals(11L, option.id());
        assertEquals("Nome Field", option.label());
    }

    @Test
    void detectsAnnotationOnSuperclass() {
        ChildEntity e = new ChildEntity(21L, "Herdado");

        SimpleService<ChildEntity> service = new SimpleService<>() {
            @Override public org.praxisplatform.uischema.repository.base.BaseCrudRepository<ChildEntity, Long> getRepository() { return null; }
            @Override public org.praxisplatform.uischema.filter.specification.GenericSpecificationsBuilder<ChildEntity> getSpecificationsBuilder() { return null; }
            @Override public Class<ChildEntity> getEntityClass() { return ChildEntity.class; }
        };

        OptionDTO<Long> option = service.getOptionMapper().toOption(e);
        assertEquals(21L, option.id());
        assertEquals("Herdado", option.label());
    }

    @Test
    void usesHeuristicGettersInOrder() {
        // Should prefer getLabel > getNomeCompleto > getNome > getDescricao > getTitle
        HeuristicEntity e = new HeuristicEntity(12L)
                .withNome("NOME")
                .withNomeCompleto("NOME COMPLETO");

        SimpleService<HeuristicEntity> service = new SimpleService<>() {
            @Override public org.praxisplatform.uischema.repository.base.BaseCrudRepository<HeuristicEntity, Long> getRepository() { return null; }
            @Override public org.praxisplatform.uischema.filter.specification.GenericSpecificationsBuilder<HeuristicEntity> getSpecificationsBuilder() { return null; }
            @Override public Class<HeuristicEntity> getEntityClass() { return HeuristicEntity.class; }
        };

        // Without label set, should pick nomeCompleto
        OptionDTO<Long> o1 = service.getOptionMapper().toOption(e);
        assertEquals("NOME COMPLETO", o1.label());

        // Now set label to override
        e.withLabel("LABEL");
        OptionDTO<Long> o2 = service.getOptionMapper().toOption(e);
        assertEquals("LABEL", o2.label());
    }

    @Test
    void fallsBackToIdStringWhenNoLabel() {
        class OnlyId { Long id; OnlyId(Long id){this.id=id;} public Long getId(){return id;} }
        OnlyId e = new OnlyId(13L);

        SimpleService<OnlyId> service = new SimpleService<>() {
            @Override public org.praxisplatform.uischema.repository.base.BaseCrudRepository<OnlyId, Long> getRepository() { return null; }
            @Override public org.praxisplatform.uischema.filter.specification.GenericSpecificationsBuilder<OnlyId> getSpecificationsBuilder() { return null; }
            @Override public Class<OnlyId> getEntityClass() { return OnlyId.class; }
        };

        OptionDTO<Long> option = service.getOptionMapper().toOption(e);
        assertEquals("13", option.label());
    }

    @Test
    void integrationFilterOptionsUsesDefaultMapper() {
        AnnGetterEntity e1 = new AnnGetterEntity(1L, "A");
        AnnGetterEntity e2 = new AnnGetterEntity(2L, "B");

        SimpleService<AnnGetterEntity> service = new SimpleService<>() {
            @Override public org.praxisplatform.uischema.repository.base.BaseCrudRepository<AnnGetterEntity, Long> getRepository() { return null; }
            @Override public org.praxisplatform.uischema.filter.specification.GenericSpecificationsBuilder<AnnGetterEntity> getSpecificationsBuilder() { return null; }
            @Override public Class<AnnGetterEntity> getEntityClass() { return AnnGetterEntity.class; }
            @Override public Page<AnnGetterEntity> filter(MinimalFilter filterDTO, Pageable pageable) {
                return new PageImpl<>(List.of(e1, e2), PageRequest.of(0, 2), 2);
            }
        };

        Page<OptionDTO<Long>> page = service.filterOptions(new MinimalFilter(), PageRequest.of(0,2));
        assertEquals(2, page.getTotalElements());
        assertEquals(1L, page.getContent().get(0).id());
        assertEquals("A", page.getContent().get(0).label());
        assertEquals(2L, page.getContent().get(1).id());
        assertEquals("B", page.getContent().get(1).label());
    }

    @Test
    void integrationByIdsOptionsPreservesOrder() {
        HeuristicEntity e1 = new HeuristicEntity(1L).withNome("N1");
        HeuristicEntity e2 = new HeuristicEntity(2L).withNome("N2");
        HeuristicEntity e3 = new HeuristicEntity(3L).withNome("N3");

        SimpleService<HeuristicEntity> service = new SimpleService<>() {
            @Override public org.praxisplatform.uischema.repository.base.BaseCrudRepository<HeuristicEntity, Long> getRepository() { return null; }
            @Override public org.praxisplatform.uischema.filter.specification.GenericSpecificationsBuilder<HeuristicEntity> getSpecificationsBuilder() { return null; }
            @Override public Class<HeuristicEntity> getEntityClass() { return HeuristicEntity.class; }
            @Override public java.util.List<HeuristicEntity> findAllById(Collection<Long> ids) {
                // Return shuffled to ensure method restores order by input
                return List.of(e3, e1, e2);
            }
        };

        List<OptionDTO<Long>> options = service.byIdsOptions(List.of(2L, 1L, 3L));
        assertEquals(List.of(2L, 1L, 3L), options.stream().map(OptionDTO::id).toList());
        assertEquals(List.of("N2", "N1", "N3"), options.stream().map(OptionDTO::label).toList());
    }
}
