package org.praxisplatform.uischema.controller.base;

public class SimpleDto {
    private Long id;
    public SimpleDto() {}
    public SimpleDto(Long id) { this.id = id; }
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
}