# Updates resource-oriented com `If-Match`

Use este contrato quando um recurso mutavel possui uma versao persistida e duas
escritas concorrentes nao podem sobrescrever silenciosamente uma a outra.

## Fronteira canonica

O fluxo e:

```text
GET /resource/{id} -> ETag forte
PUT /resource/{id} + If-Match
  -> controller valida somente a forma do header
  -> command service abre a transacao e carrega/bloqueia o registro
  -> ResourceVersionUpdatePrecondition compara a versao corrente
  -> mapper e persistencia executam somente depois da comparacao
  -> resposta publica o novo ETag
```

Nunca consulte a versao no controller para depois executar o update. Essa
separacao cria uma janela de time-of-check/time-of-use.

## Adocao em `AbstractBaseResourceService`

Um recurso JPA versionado opta explicitamente pelo contrato:

```java
@Override
public boolean requiresResourceVersionPrecondition() {
    return true;
}

@Override
protected OptionalLong getManagedResourceVersion(MyEntity entity) {
    return OptionalLong.of(entity.getVersion());
}

@Override
public OptionalLong getResourceVersion(Long id) {
    return repository.findVersionById(id);
}
```

`getManagedResourceVersion` deve ler a entidade pertencente a mesma transacao
que aplicara a mudanca. A estrategia de persistencia do host deve garantir lock
ou optimistic concurrency real; apenas possuir um numero de versao nao elimina
lost updates.

Servicos DB-backed ou JDBC podem implementar diretamente a sobrecarga
`update(id, dto, precondition)`, desde que carreguem/bloqueiem a linha, invoquem
`precondition.requireMatch(id, currentVersion)` e executem a escrita na mesma
transacao e conexao.

## Resultados HTTP

| Condicao | Resultado |
| --- | --- |
| Recurso nao versionado | `If-Match` nao e exigido; compatibilidade preservada |
| Header ausente em recurso versionado | `428 RESOURCE_VERSION_REQUIRED` |
| Header fraco, curinga, multiplo ou malformado | `400 INVALID_RESOURCE_VERSION` |
| ETag forte de outra versao/recurso | `412 STALE_RESOURCE_VERSION` |
| ETag correspondente | update executado e novo `ETag` retornado |

Availability, `_links` e capabilities continuam sendo discovery. Eles nao
substituem autorizacao, lock, transacao nem a validacao do `If-Match` no command
service.
