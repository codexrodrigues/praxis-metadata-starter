package org.praxisplatform.uischema.bulk.p3fixture;

import jakarta.persistence.*;

@Entity @Table(name = "bulk_p3_item")
public class DeclarativeBulkItem {
    @Id public Long id;
    @Version public Long revision;
    public Integer tenant;
    public String name;
    public String note;
    public DeclarativeBulkItem() { }
}
