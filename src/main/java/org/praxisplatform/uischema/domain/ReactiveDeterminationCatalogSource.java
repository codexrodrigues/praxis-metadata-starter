package org.praxisplatform.uischema.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.praxisplatform.uischema.capability.CanonicalCapabilityResolver;
import org.praxisplatform.uischema.determination.ReactiveDeterminationDefinitionRegistry;
import org.praxisplatform.uischema.determination.ReactiveDeterminationMetadataCompiler;
import org.praxisplatform.uischema.openapi.CanonicalOperationRef;
import org.praxisplatform.uischema.openapi.CanonicalOperationResolver;
import org.praxisplatform.uischema.openapi.OpenApiDocumentService;
import org.praxisplatform.uischema.schema.ApiResourceIdentityResolver;
import org.praxisplatform.uischema.schema.CanonicalSchemaRef;
import org.praxisplatform.uischema.schema.FilteredSchemaProjection;
import org.praxisplatform.uischema.schema.SchemaReferenceResolver;

/** Resolves exact form scopes and consumes the same compiler and schema selector as /schemas/filtered. */
public final class ReactiveDeterminationCatalogSource {
    private final ReactiveDeterminationDefinitionRegistry registry;
    private final ReactiveDeterminationMetadataCompiler compiler;
    private final CanonicalOperationResolver operations;
    private final OpenApiDocumentService documents;
    private final ApiResourceIdentityResolver identities;
    private final SchemaReferenceResolver schemas;
    private final CanonicalCapabilityResolver capabilities;
    private final FilteredSchemaProjection projection;

    public ReactiveDeterminationCatalogSource(ReactiveDeterminationDefinitionRegistry registry,
            ReactiveDeterminationMetadataCompiler compiler, CanonicalOperationResolver operations,
            OpenApiDocumentService documents, ApiResourceIdentityResolver identities,
            SchemaReferenceResolver schemas, CanonicalCapabilityResolver capabilities, ObjectMapper mapper) {
        this.registry=registry; this.compiler=compiler; this.operations=operations; this.documents=documents;
        this.identities=identities; this.schemas=schemas; this.capabilities=capabilities;
        this.projection=new FilteredSchemaProjection(mapper);
    }

    /** No URL-prefix discovery, interpretation of raw definitions, or fallback to another operation. */
    public List<CompiledForm> find(String resourceKey, String group) {
        List<CompiledForm> result=new ArrayList<>();
        Map<String, String> documentHashes = new java.util.HashMap<>();
        for (String id : registry.schemaOperationIds().stream().sorted().toList()) {
            CanonicalOperationRef operation=operations.resolveByOperationId(id)
                    .orElseThrow(()->new IllegalStateException("Unresolved determination form operation: "+id));
            String owner=identities.resolveResourceKey(operation.path(),operation.path(),operation.method())
                    .orElseThrow(()->new IllegalStateException("Determination form has no explicit resource identity: "+id));
            if (resourceKey!=null&&!resourceKey.equals(owner) || group!=null&&!group.equals(operation.group())) continue;
            JsonNode document=documents.getDocumentForGroup(operation.group());
            if(document==null) throw new IllegalStateException("Missing form OpenAPI document: "+id);
            String documentHash = documentHashes.computeIfAbsent(operation.group(), ignored ->
                    org.praxisplatform.uischema.hash.SchemaHashUtil.sha256Hex(
                            new org.praxisplatform.uischema.hash.SchemaCanonicalizer().canonicalize(document)));
            String path=documents.resolveDocumentPath(document.path("paths"),operation.path(),operation.method());
            JsonNode published=document.path("paths").path(path).path(operation.method().toLowerCase(java.util.Locale.ROOT));
            if(!id.equals(published.path("operationId").asText()))
                throw new IllegalStateException("Determination form identity differs from published operation: "+id);
            CanonicalOperationRef selected=new CanonicalOperationRef(operation.group(),id,path,operation.method());
            JsonNode form=projection.select(document,path,operation.method(),"request").schema();
            List<Map<String,Object>> compiled=compiler.compile(selected,"request",document,form);
            CanonicalSchemaRef ref=projection.resolve(document,selected,"request",schemas,capabilities,null,null).reference();
            Map<String,JsonNode> fields=new java.util.TreeMap<>();
            for(Map<String,Object> determination:compiled) for(String side:List.of("inputs","outputs")) {
                for(Object item:(List<?>)determination.get(side)) {
                    String pointer=(String)((Map<?,?>)item).get("fieldPath");
                    fields.put(pointer,compiler.resolveFieldSchema(form,document,pointer));
                }
            }
            result.add(new CompiledForm(owner,selected,ref,form,compiled,Map.copyOf(fields),documentHash));
        }
        return List.copyOf(result);
    }

    /** Captured form evidence contributes to the catalog fingerprint; scope and compiled metadata remain intact. */
    public record CompiledForm(String resourceKey, CanonicalOperationRef operation, CanonicalSchemaRef schema,
            JsonNode formSchema, List<Map<String,Object>> determinations, Map<String,JsonNode> fields,
            String sourceDocumentHash) { }
}
