# Seleções independentes em lookup JPA

O host Ergon passou a publicar os subcontratos ricos de seis diretórios. No PostgreSQL real, o mesmo atributo usado como código, descrição e campo visual provocava `Multi-select expressions defined duplicate alias : lookupCode`: Hibernate reutiliza o Path e alias modifica esse objeto compartilhado.

O executor canônico agora cria uma expressão independente `coalesce(path, path)` para cada seleção adicional. Isso preserva valor, tipo e nulidade, sem alterar os caminhos de predicados, identidade composta ou ordenação. Não há mudança no shape público nem DDL.

Validação: 34 testes focais passaram (descriptor, registry, publication diagnostics, composite executor e regressão de aliases). O consumidor Ergon foi compilado contra o candidato privado `8.0.0-ergon-bulk.308-SNAPSHOT`; a prova Neon confirmou a identidade e a indisponibilidade de um cargo extinto. A integração completa de Bulking continua pendente devido ao prazo de publicação LIVE e às provas transacionais de discovery. Esta correção não certifica o ciclo de lote.

Não houve publicação Maven Central. O PR do host registra os resultados detalhados e os limites. O contrato de opções permanece o existente; não exige atualização de schema, landing page ou runtime Angular.
