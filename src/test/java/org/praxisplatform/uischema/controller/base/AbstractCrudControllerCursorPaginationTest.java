package org.praxisplatform.uischema.controller.base;

import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.praxisplatform.uischema.util.CursorEncoder;
import org.praxisplatform.uischema.service.base.BaseCrudService;
import org.praxisplatform.uischema.dto.CursorPage;

@WebMvcTest(value = AbstractCrudControllerCursorPaginationTest.CursorController.class,
        properties = "praxis.pagination.max-size=20")
class AbstractCrudControllerCursorPaginationTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean(answer = Answers.CALLS_REAL_METHODS)
    CursorService service;

    @Test
    void returnsNextAndPrevCursors() throws Exception {
        String nextToken = CursorEncoder.BASE64_URL.encode("2");

        mockMvc.perform(post("/cursor/filter/cursor")
                        .param("size", "2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Data-Version", "1"))
                .andExpect(jsonPath("$.data.content[0].id").value(3))
                .andExpect(jsonPath("$.data.content[1].id").value(2))
                .andExpect(jsonPath("$.data.next").value(nextToken))
                .andExpect(jsonPath("$.data.prev", nullValue()));

        mockMvc.perform(post("/cursor/filter/cursor")
                        .param("after", nextToken)
                        .param("size", "2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Data-Version", "1"))
                .andExpect(jsonPath("$.data.content[0].id").value(1))
                .andExpect(jsonPath("$.data.next", nullValue()))
                .andExpect(jsonPath("$.data.prev").value(nextToken));
    }

    interface CursorService extends BaseCrudService<CursorEntity, CursorDto, Long, SimpleFilterDTO> {
        List<CursorEntity> DATA = List.of(
                new CursorEntity(3L, Instant.parse("2024-05-03T00:00:00Z")),
                new CursorEntity(2L, Instant.parse("2024-05-02T00:00:00Z")),
                new CursorEntity(1L, Instant.parse("2024-05-01T00:00:00Z"))
        );

        @Override
        default CursorPage<CursorEntity> filterByCursor(SimpleFilterDTO filter, Sort sort,
                                                        String after, String before, int size) {
            int start = 0;
            if (after != null) {
                long id = Long.parseLong(CursorEncoder.BASE64_URL.decode(after));
                start = indexOf(id) + 1;
            } else if (before != null) {
                long id = Long.parseLong(CursorEncoder.BASE64_URL.decode(before));
                start = Math.max(0, indexOf(id) - size);
            }
            int end = Math.min(start + size, DATA.size());
            List<CursorEntity> slice = DATA.subList(start, end);
            String next = end < DATA.size() ? CursorEncoder.BASE64_URL.encode(DATA.get(end - 1).getId().toString()) : null;
            String prev = start > 0 ? CursorEncoder.BASE64_URL.encode(DATA.get(start - 1).getId().toString()) : null;
            return new CursorPage<>(slice, next, prev, size);
        }

        private static int indexOf(long id) {
            for (int i = 0; i < DATA.size(); i++) {
                if (DATA.get(i).getId() == id) return i;
            }
            return -1;
        }

        @Override
        default Optional<String> getDatasetVersion() { return Optional.of("1"); }
    }

    static class CursorEntity {
        private Long id;
        private Instant updatedAt;
        CursorEntity(Long id, Instant updatedAt) { this.id = id; this.updatedAt = updatedAt; }
        Long getId() { return id; }
        void setId(Long id) { this.id = id; }
        Instant getUpdatedAt() { return updatedAt; }
        void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    }

    static class CursorDto {
        private Long id;
        CursorDto() {}
        CursorDto(Long id) { this.id = id; }
        Long getId() { return id; }
        void setId(Long id) { this.id = id; }
    }

    @org.springframework.web.bind.annotation.RestController
    @org.springframework.web.bind.annotation.RequestMapping("/cursor")
    static class CursorController extends AbstractCrudController<CursorEntity, CursorDto, Long, SimpleFilterDTO> {
        @Autowired
        CursorService service;

        @Override
        protected CursorService getService() { return service; }

        @Override
        protected CursorDto toDto(CursorEntity entity) { return new CursorDto(entity.getId()); }

        @Override
        protected CursorEntity toEntity(CursorDto dto) { return new CursorEntity(dto.getId(), Instant.now()); }

        @Override
        protected Long getEntityId(CursorEntity entity) { return entity.getId(); }

        @Override
        protected Long getDtoId(CursorDto dto) { return dto.getId(); }

        @Override
        protected String getBasePath() { return "/cursor"; }
    }
}

