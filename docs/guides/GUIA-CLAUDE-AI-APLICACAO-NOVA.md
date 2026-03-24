# Guia para Agentes de IA - Criar Nova Aplicacao Spring Boot com Praxis Metadata Starter

## Objetivo

Este guia orienta um agente de IA a criar uma nova aplicacao Spring Boot que use o `praxis-metadata-starter` como fonte canônica de contrato metadata-driven.

O objetivo nao e gerar um monolito cheio de dependencias opcionais. O objetivo e criar uma base minima, coerente e evolutiva que:

- publique `OpenAPI + x-ui`
- exponha `/schemas/filtered`
- use `@ApiResource`, `@ApiGroup`, `@UISchema`, `AbstractCrudController` e `AbstractBaseCrudService`
- possa ser consumida diretamente por `praxis-ui-angular`

## Hierarquia canônica

O agente deve seguir esta ordem:

1. `praxis-metadata-starter`
   - define o contrato canônico
2. `praxis-api-quickstart`
   - mostra a aplicacao operacional de referencia
3. `praxis-ui-angular`
   - mostra como o contrato e consumido em runtime

## O que este guia deve gerar

A base minima de uma nova aplicacao deve conter:

- `pom.xml`
- classe `@SpringBootApplication`
- `ApiPaths` local da aplicacao
- configuracao basica de `application.properties`
- ao menos um modulo com DTO, FilterDTO, mapper, repository, service e controller

## O que este guia nao deve assumir como baseline

Nao trate como obrigatorio por padrao:

- `praxis-bulk-starter`
- `praxis-bulk-web`
- `praxis-files-starter`
- `praxis-config-starter`
- scans manuais de pacotes externos de bulk

Esses modulos podem existir na plataforma, mas nao fazem parte do baseline canônico deste starter.

## Dependencia minima recomendada

O baseline do projeto deve partir da dependencia publicada do starter:

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

Se o projeto usar MapStruct explicitamente para os mappers, adicione a dependencia e o processor correspondentes.

Importante:

- o starter ja traz o baseline web/JPA/OpenAPI necessario para o contrato metadata-driven
- a aplicacao host ainda precisa declarar o driver de banco que realmente vai usar
- para build executavel, o host deve declarar o `spring-boot-maven-plugin`
- para MapStruct funcionar de forma reproduzivel em CI e IDE, o host deve declarar `maven-compiler-plugin` com annotation processor

## Template de pom.xml

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

    <!-- Escolha um driver real para a aplicacao host -->
    <dependency>
      <groupId>org.postgresql</groupId>
      <artifactId>postgresql</artifactId>
      <scope>runtime</scope>
    </dependency>

    <!-- Se o projeto usar MapStruct -->
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

Se a aplicacao nao usar MapStruct, remova `mapstruct` e o bloco `annotationProcessorPaths`.

Se o host usar H2 apenas para bootstrap local, troque o driver acima por `com.h2database:h2` em `runtime`.

## Classe principal

Na maior parte dos casos, o starter deve ser descoberto pela auto-configuração normal do Spring Boot.

Template simples:

```java
@SpringBootApplication
public class DemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
```

Nao adicione `scanBasePackages` para pacotes externos arbitrarios sem necessidade comprovada.

## Estrutura recomendada

```text
src/main/java/{base-package}/
├── {App}Application.java
├── constants/
│   └── ApiPaths.java
└── {modulo}/
    ├── controller/
    ├── dto/
    │   └── filter/
    ├── entity/
    ├── mapper/
    ├── repository/
    └── service/
```

## ApiPaths local

Cada aplicacao deve definir sua propria classe `ApiPaths`.

Exemplo:

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

## Properties minimas

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

# Ajuste para o banco escolhido no host
spring.datasource.url=jdbc:postgresql://localhost:5432/{db-name}
spring.datasource.username={db-user}
spring.datasource.password={db-password}

# Recomendado quando a URL publica e a URL interna divergem
# app.openapi.internal-base-url=http://localhost:8080
```

Observacoes:

- `springdoc.swagger-ui.path=/swagger-ui.html` nao e obrigatorio; o quickstart publicado usa `/swagger-ui/index.html`
- se houver proxy, Render ou balanceador, documente `app.openapi.internal-base-url`
- se o host usar H2 local, troque as propriedades de datasource de acordo com o perfil real
- nao deixe o guia implicar que Flyway sozinho resolve bootstrap; sem datasource compativel a aplicacao nao sobe

## Bootstrap inicial do banco local

Se a aplicacao nova precisar persistir de verdade no primeiro CRUD, o guia deve mandar a LLM escolher explicitamente uma destas trilhas:

Trilha preferencial de plataforma:

- manter `spring.flyway.enabled=true`
- criar a migration inicial do modulo, por exemplo `src/main/resources/db/migration/V1__init.sql`
- garantir que a tabela da primeira entidade exista antes do primeiro `POST` ou `POST /filter`

Trilha temporaria apenas para sandbox/prova local:

- usar `spring.jpa.hibernate.ddl-auto=update` ou `create-drop` em perfil local
- documentar que isso nao substitui a migration canônica
- remover a dependencia de `ddl-auto` assim que a migration inicial for criada

Regra:

- para aplicacao real ou exemplo de plataforma, prefira migration
- para prova local guiada por LLM, `ddl-auto` pode ser aceito apenas como contingencia explicita quando o objetivo imediato for validar o fluxo end-to-end

## Primeiro recurso da aplicacao

A aplicacao nova deve nascer com pelo menos um recurso que demonstre o fluxo completo:

- DTO com `@UISchema`
- `FilterDTO` com `@Filterable`
- controller sobre `AbstractCrudController`
- `/schemas/filtered` retornando contrato consumivel

## Como a aplicacao sera consumida pelo Angular

O `GenericCrudService` do `praxis-ui-angular` espera:

- `GET {resource}/schemas` como endpoint de descoberta/compatibilidade do recurso
- `/schemas/filtered?path={resource}/all&operation=get&schemaType=response`
- `/schemas/filtered?path={resource}/filter&operation=post&schemaType=request`
- `ETag` e `X-Schema-Hash`
- `x-ui.resource.idField`

Implicacoes:

- a aplicacao deve publicar um contrato estável, nao apenas CRUD funcional
- o runtime do Angular efetivamente resolve o payload estrutural via `/schemas/filtered`
- o `resourcePath` consumido pelo Angular deve apontar para o recurso base, sem repetir `/api` quando o host ja tiver `baseUrl='/api'`

## Uso real de referencia

Ao gerar uma nova aplicacao, o agente deve se espelhar em padroes do `praxis-api-quickstart`:

- README publica a superficie operacional e o deploy no Render
- recursos usam `@ApiResource` + `@ApiGroup`
- selects remotos usam `/options/filter` e `displayField = "label"`
- a cadeia `/schemas/filtered` funciona em producao e e consumida pelo Angular

## Prompt recomendado para IA

```text
Voce esta criando uma nova aplicacao Spring Boot com praxis-metadata-starter.

Siga o contrato canônico do starter.
Use praxis-api-quickstart como host operacional de referencia.
Use praxis-ui-angular como consumidor final esperado do contrato.

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

Antes de concluir, o agente deve validar:

- `mvn clean package`
- `/v3/api-docs`
- Swagger UI
- `/schemas/filtered`
- um schema `request` e um `response`
- um endpoint `options/filter` quando houver relacao remota

Se o projeto usar MapStruct, valide tambem que os fontes gerados foram produzidos no build e que nao ha erro de annotation processor.

Se houver persistencia real no fluxo validado, confirme tambem:

- `POST /{resource}` sem erro de tabela inexistente
- `POST /{resource}/filter` sem erro de schema/tabela inexistente
- existencia explicita de migration inicial ou de `ddl-auto` temporario no perfil local

## Referencias cruzadas

- `praxis-metadata-starter/README.md`
- `praxis-api-quickstart/README.md`
- `praxis-ui-angular/projects/praxis-core/src/lib/services/generic-crud.service.ts`
- `praxis-metadata-starter/docs/guides/GUIA-CLAUDE-AI-CRUD-BULK.md`
