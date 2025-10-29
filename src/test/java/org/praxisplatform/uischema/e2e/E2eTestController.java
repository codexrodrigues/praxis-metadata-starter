package org.praxisplatform.uischema.e2e;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.praxisplatform.uischema.extension.annotation.UISchema;
import org.praxisplatform.uischema.rest.response.RestApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/e2e")
@Tag(name = "E2E Test Controller")
public class E2eTestController {

    @GetMapping("/all")
    public ResponseEntity<RestApiResponse<SimpleDTO>> all() {
        return ResponseEntity.ok(RestApiResponse.success(new SimpleDTO("john", 30), null));
    }

    @PostMapping
    public ResponseEntity<RestApiResponse<String>> post(@RequestBody SimpleDTO dto) {
        return ResponseEntity.ok(RestApiResponse.success(dto.name, null));
    }

    @GetMapping("/with-ref")
    public ResponseEntity<RestApiResponse<TopDTO>> withRef() {
        TopDTO top = new TopDTO();
        top.nested = new NestedDTO();
        top.nested.code = "X";
        return ResponseEntity.ok(RestApiResponse.success(top, null));
    }

    public static class SimpleDTO {
        @UISchema(label = "Name")
        public String name;
        @UISchema(label = "Age")
        public Integer age;

        public SimpleDTO() {}
        public SimpleDTO(String name, Integer age) { this.name = name; this.age = age; }
    }

    public static class TopDTO {
        @UISchema(label = "Nested")
        public NestedDTO nested;
    }

    public static class NestedDTO {
        @UISchema(label = "Code")
        public String code;
    }
}
