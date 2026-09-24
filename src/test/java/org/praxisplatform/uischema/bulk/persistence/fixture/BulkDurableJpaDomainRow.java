package org.praxisplatform.uischema.bulk.persistence.fixture;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Test-only JPA domain effect used to prove the receipt shares the physical transaction. */
@Entity
@Table(name = "bulk_durable_jpa_domain")
public class BulkDurableJpaDomainRow {
    @Id
    private Long id;
    private int writes;

    protected BulkDurableJpaDomainRow() { }

    public BulkDurableJpaDomainRow(long id) {
        this.id = id;
    }

    public void recordWrite() {
        writes++;
    }
}
