# Guia 01 - IA Backend - Aplicacao Nova com Praxis Metadata Starter

## Objetivo

Este guia orienta uma LLM a criar uma nova aplicacao Spring Boot que use o
`praxis-metadata-starter` como fonte canonica do contrato metadata-driven.

O objetivo nao e gerar um projeto "parecido com os apps do time". O objetivo e
gerar uma base minima que:

- publique `OpenAPI + x-ui`
- exponha `GET /{resource}/schemas`
- exponha `/schemas/filtered`
- use `@ApiResource`, `@ApiGroup`, `@UISchema`,
  `AbstractCrudController` e `AbstractBaseCrudService`
- possa ser consumida por `praxis-ui-angular` sem ajuste local de contrato

## Ordem de leitura para a LLM

Use esta ordem:

1. este guia
2. `GUIA-02-AI-BACKEND-CRUD-METADATA.md`
3. `CHECKLIST-VALIDACAO-IA.md`

Este guia precisa ser suficiente por si so. Nao dependa de nenhum app externo
como fonte necessaria.

## O que este guia deve gerar

A base minima de uma nova aplicacao deve conter:

- `pom.xml`
- classe `@SpringBootApplication`
- `ApiPaths` local da aplicacao
- `application.properties` ou perfis equivalentes
- ao menos um modulo com DTO, FilterDTO, mapper, repository, service e controller

## O que nao faz parte do baseline

Nao trate como obrigatorio por padrao:

- `praxis-bulk-starter`
- `praxis-bulk-web`
- `praxis-files-starter`
- `praxis-config-starter`
- scans manuais de pacotes externos

Esses modulos sao opcionais. So entram se o pedido exigir explicitamente.

## Dependencia minima recomendada

```xml
<dependency>
  <groupId>io.github.codexrodrigues</groupId>
  <artifactId>praxis-metadata-starter</artifactId>
  <version>2.0.0-rc.7</version>
</dependency>
```

Dependencias complementares comuns:

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-validation</artifactId>
</dependency>

<dependency>
  <groupId>org.flywaydb</groupId>
  <artifactId>flyway-core</artifactId>
</dependency>
```

Se o projeto usar MapStruct, adicione `mapstruct` e o annotation processor.

Importante:

- o starter ja traz o baseline web/JPA/OpenAPI necessario
- a aplicacao host ainda precisa declarar o driver real do banco
- para build executavel, o host deve declarar `spring-boot-maven-plugin`
- para MapStruct funcionar em CI e IDE, o host deve declarar
  `maven-compiler-plugin` com processor

## Template de `pom.xml`

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.2.5</version>
    <relativePath/>
  </parent>

  <groupId>{groupId}</groupId>
  <artifactId>{artifactId}</artifactId>
  <version>1.0.0-SNAPSHOT</version>

  <properties>
    <java.version>21</java.version>
    <praxis.metadata.version>2.0.0-rc.7</praxis.metadata.version>
    <org.mapstruct.version>1.5.5.Final</org.mapstruct.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>io.github.codexrodrigues</groupId>
      <artifactId>praxis-metadata-starter</artifactId>
      <version>${praxis.metadata.version}</version>
    </dependency>

    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>

    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-core</artifactId>
    </dependency>

    <dependency>
      <groupId>org.postgresql</groupId>
      <artifactId>postgresql</artifactId>
      <scope>runtime</scope>
    </dependency>

    <dependency>
      <groupId>org.mapstruct</groupId>
      <artifactId>mapstruct</artifactId>
      <version>${org.mapstruct.version}</version>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>

      <plugin>
        <artifactId>maven-compiler-plugin</artifactId>
        <configuration>
          <annotationProcessorPaths>
            <path>
              <groupId>org.mapstruct</groupId>
              <artifactId>mapstruct-processor</artifactId>
              <version>${org.mapstruct.version}</version>
            </path>
          </annotationProcessorPaths>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

Se a aplicacao nao usar MapStruct, remova o bloco correspondente.

## Classe principal

Template simples:

```java
@SpringBootApplication
public class DemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
```

Nao adicione `scanBasePackages` arbitrario sem necessidade comprovada.

## Estrutura recomendada

```text
src/main/java/{base-package}/
|-- {App}Application.java
|-- constants/
|   `-- ApiPaths.java
`-- {modulo}/
    |-- controller/
    |-- dto/
    |   `-- filter/
    |-- entity/
    |-- mapper/
    |-- repository/
    `-- service/
```

## `ApiPaths` local

Cada aplicacao deve definir sua propria classe `ApiPaths`.

```java
public final class ApiPaths {
    public static final String BASE = "/api";

    public static final class HumanResources {
        private static final String HR = BASE + "/human-resources";
        public static final String FUNCIONARIOS = HR + "/funcionarios";
        public static final String CARGOS = HR + "/cargos";
    }

    private ApiPaths() {}
}
```

## Propriedades minimas

Exemplo de `application.properties`:

```properties
spring.application.name={app-name}
server.port=8080

springdoc.api-docs.enabled=true
springdoc.api-docs.path=/v3/api-docs
springdoc.api-docs.groups.enabled=true
springdoc.swagger-ui.enabled=true

spring.jpa.open-in-view=false
spring.flyway.enabled=true

spring.datasource.url=jdbc:postgresql://localhost:5432/{db-name}
spring.datasource.username={db-user}
spring.datasource.password={db-password}

# Opcional quando URL publica e interna divergem
# app.openapi.internal-base-url=http://localhost:8080
```

Observacoes:

- `springdoc.swagger-ui.path=/swagger-ui.html` nao e obrigatorio
- se houver proxy ou balanceador, documente `app.openapi.internal-base-url`
- se o host usar H2 local, troque o datasource de acordo com o perfil real
- Flyway sem datasource compativel nao resolve bootstrap sozinho

## Bootstrap inicial do banco

Trilha preferencial:

- manter `spring.flyway.enabled=true`
- criar a migration inicial, por exemplo `V1__init.sql`
- garantir que a primeira tabela exista antes do primeiro `POST` ou `/filter`

Trilha temporaria apenas para sandbox:

- usar `spring.jpa.hibernate.ddl-auto=update` ou `create-drop` em perfil local
- documentar que isso e contingencia
- remover a dependencia de `ddl-auto` assim que a migration existir

Regra:

- para aplicacao real, prefira migration
- para prova local guiada por LLM, `ddl-auto` so entra como contingencia explicita

## Primeiro recurso da aplicacao

A aplicacao nova deve nascer com pelo menos um recurso que demonstre:

- DTO com `@UISchema`
- `FilterDTO` com `@Filterable`
- controller sobre `AbstractCrudController`
- `/schemas/filtered` retornando contrato consumivel

Os detalhes do recurso ficam no `GUIA-02-AI-BACKEND-CRUD-METADATA.md`.

## Como o frontend vai consumir este backend

O `GenericCrudService` espera:

- `GET {resource}/schemas`
- `/schemas/filtered?path={resource}/all&operation=get&schemaType=response`
- `/schemas/filtered?path={resource}/filter&operation=post&schemaType=request`
- `ETag`
- `X-Schema-Hash`
- `x-ui.resource.idField`

Implicacoes:

- a aplicacao deve publicar contrato estavel, nao apenas CRUD funcional
- o runtime Angular resolve a estrutura via `/schemas/filtered`
- o `resourcePath` consumido no frontend deve apontar para o recurso base

## Prompt recomendado para IA

```text
Voce esta criando uma nova aplicacao Spring Boot com praxis-metadata-starter.

Siga apenas o contrato canonico publicado neste guia e no guia de CRUD.
Considere praxis-ui-angular como consumidor final esperado do contrato.

Gere:
- pom.xml minimo e coerente
- classe principal Spring Boot
- ApiPaths local
- application.properties baseline
- primeiro modulo CRUD metadata-driven completo

Nao trate bulk, files ou config-store como obrigatorios.
So adicione modulos opcionais se o pedido disser explicitamente.

Entrada:
- Nome da aplicacao: {app-name}
- GroupId base: {group-id}
- ArtifactId: {artifact-id}
- Modulo inicial: {modulo}
- Resource path inicial: {resource-path}
- Api group inicial: {api-group}
```

## Checklist minimo

Antes de concluir:

- `mvn clean package`
- `GET /v3/api-docs`
- Swagger UI
- `GET /schemas/filtered`
- um schema `request` e um `response`
- `options/filter` quando houver relacao remota

Se houver persistencia real:

- `POST /{resource}` sem erro de tabela inexistente
- `POST /{resource}/filter` sem erro de schema ou tabela inexistente
- migration inicial ou `ddl-auto` temporario explicitamente documentado

## Referencias publicas

- starter canonico: `praxis-metadata-starter/README.md`
- repositório público do runtime Angular: `https://github.com/codexrodrigues/praxis-ui-angular`
- pacote publicado de consumo de contrato: `@praxisui/core`
- guia relacionado: `praxis-metadata-starter/docs/guides/GUIA-02-AI-BACKEND-CRUD-METADATA.md`
