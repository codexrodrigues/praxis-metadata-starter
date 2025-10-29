package org.praxisplatform.uischema.controller.base;

public class SimpleEntity {
    private Long id;
    public SimpleEntity() {}
    public SimpleEntity(Long id) { this.id = id; }
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
}