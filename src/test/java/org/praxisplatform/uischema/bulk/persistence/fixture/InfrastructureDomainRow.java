package org.praxisplatform.uischema.bulk.persistence.fixture;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "bulk_test_domain")
public class InfrastructureDomainRow {
    @Id
    private Long id;
    protected InfrastructureDomainRow() { }
    public InfrastructureDomainRow(long id) { this.id = id; }
}
