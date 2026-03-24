# Sandbox Recomendado para a Prova

## Objetivo

Padronizar o ambiente minimo da prova para que falhas sejam atribuiveis ao guia ou ao codigo gerado, e nao a improvisos de infraestrutura local.

## Forma recomendada

Use um projeto sandbox descartavel e isolado do quickstart.

Opcoes validas:

- repo separado
- pasta temporaria fora do starter
- subdiretorio descartavel como `tmp/ai-guide-proof/`

Evite:

- rodar a prova dentro do `praxis-api-quickstart`
- reaproveitar codigo pronto do quickstart
- executar varias rodadas sobre o mesmo projeto ja corrigido manualmente

## Convencoes sugeridas

- porta default: `8080`
- contexto base dos recursos: `/api`
- grupo OpenAPI inicial: `catalog`
- entidade simples inicial: `Categoria`
- entidade com relacao: `Produto -> Categoria`

## Banco local para rodadas H2

Valores sugeridos:

```properties
spring.datasource.url=jdbc:h2:mem:guideproof;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE
spring.datasource.driver-class-name=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=
spring.h2.console.enabled=true
```

## Banco local para rodadas PostgreSQL

Valores sugeridos:

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/guideproof
spring.datasource.username=guideproof
spring.datasource.password=guideproof
```

Se usar Docker local, prefira algo equivalente a:

```bash
docker run --name guideproof-pg \
  -e POSTGRES_DB=guideproof \
  -e POSTGRES_USER=guideproof \
  -e POSTGRES_PASSWORD=guideproof \
  -p 5432:5432 \
  -d postgres:16
```

## Regra de limpeza entre rodadas

- apagar o projeto sandbox ao fim da rodada reprovada
- recriar o projeto do zero na repeticao
- nao reaproveitar `target/`, banco persistido ou migrations ajustadas manualmente

## Artefatos a preservar

Mesmo sendo sandbox descartavel, preserve:

- prompt usado
- log de build
- log de startup
- evidencias HTTP
- relatorio preenchido da rodada
