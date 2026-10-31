package org.praxisplatform.uischema.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PartialUpdateInputTest {
    static final class Input extends PartialUpdateInput {
        private String contact;
        public String getContact() { return contact; }
        @JsonSetter public void setContact(String contact) { supplied("contact"); this.contact=contact; }
    }
    @Test void omittedNullAndValueRemainDistinctWithoutLeakingTrackingState() throws Exception {
        var mapper=new ObjectMapper();
        assertThat(mapper.readValue("{}",Input.class).wasSupplied("contact")).isFalse();
        var cleared=mapper.readValue("{\"contact\":null}",Input.class);
        assertThat(cleared.wasSupplied("contact")).isTrue();
        assertThat(cleared.getContact()).isNull();
        var changed=mapper.readValue("{\"contact\":\"new\"}",Input.class);
        assertThat(changed.wasSupplied("contact")).isTrue();
        assertThat(mapper.writeValueAsString(changed)).isEqualTo("{\"contact\":\"new\"}");
        assertThatThrownBy(() -> mapper.readValue("{\"suppliedProperties\":[\"contact\"]}",Input.class))
            .isInstanceOf(com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException.class);
        var forged=mapper.copy().disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .readValue("{\"suppliedProperties\":[\"contact\"]}",Input.class);
        assertThat(forged.wasSupplied("contact")).isFalse();
    }
}
