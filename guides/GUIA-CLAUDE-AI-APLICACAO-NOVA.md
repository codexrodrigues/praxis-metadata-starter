# 🚀 Guia Completo Claude AI - Criar Aplicação Praxis do Zero

## 📋 Objetivo

Este guia fornece instruções detalhadas para que o Claude AI possa criar uma aplicação Spring Boot completa usando a arquitetura Praxis Platform, incluindo configuração, estrutura de pacotes, dependências e padrões organizacionais.

## 🎯 Entrada Esperada

O usuário deve fornecer:
1. **Nome da aplicação** (ex: `sistema-vendas`, `gestao-estoque`)
2. **Domínio base** (ex: `com.empresa.vendas`, `com.empresa.estoque`)
3. **Módulos desejados** (ex: `produtos`, `clientes`, `vendas`)
4. **Porta do servidor** (opcional, padrão: 8080)

## 🏗️ Estrutura Completa da Aplicação

```
{nome-aplicacao}/
├── pom.xml                                   # Maven configuration
├── README.md                                 # Project documentation
├── .gitignore                               # Git ignore rules
└── src/
    ├── main/
    │   ├── java/
    │   │   └── {dominio-base}/
    │   │       ├── {NomeApp}Application.java         # Main Spring Boot class
    │   │       ├── common/
    │   │       │   ├── config/
    │   │       │   │   └── DevDataSeeder.java        # Development data seeder
    │   │       │   ├── constants/
    │   │       │   │   └── ApiPaths.java             # Centralized API paths
    │   │       │   └── filter/
    │   │       │       └── NotFoundLoggingFilter.java # 404 logging filter
    │   │       ├── config/
    │   │       │   ├── WebConfig.java                # CORS and web configuration
    │   │       │   └── EndpointMappingsLogger.java   # Endpoint logging
    │   │       └── {modulo}/                         # Para cada módulo
    │   │           ├── entity/
    │   │           ├── dto/
    │   │           ├── mapper/
    │   │           ├── repository/
    │   │           ├── service/
    │   │           └── controller/
    │   └── resources/
    │       ├── application.properties                # Main properties
    │       ├── application-dev.properties            # Development config
    │       ├── application-prod.properties           # Production config
    │       ├── data.sql                             # Sample data
    │       └── db/
    │           └── migration/
    │               └── common/
    │                   └── V1__initial_schema.sql   # Flyway migration
    └── test/
        └── java/
            └── {dominio-base}/
                ├── {NomeApp}ApplicationTests.java    # Main test class
                └── {modulo}/                         # Tests por módulo
```

## 📝 Templates Essenciais

### 1. **pom.xml** - Configuração Maven Completa

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

    <groupId>{dominio-base}</groupId>
    <artifactId>{nome-aplicacao}</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <name>{nome-aplicacao}</name>
    <description>{Descrição da aplicação}</description>

    <properties>
        <java.version>21</java.version>
        <praxis.core.version>1.0.0-SNAPSHOT</praxis.core.version>
        <praxis.bulk.version>0.1.0-SNAPSHOT</praxis.bulk.version>
        <org.mapstruct.version>1.5.5.Final</org.mapstruct.version>
        <springdoc-openapi.version>2.6.0</springdoc-openapi.version>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <project.reporting.outputEncoding>UTF-8</project.reporting.outputEncoding>
    </properties>

    <dependencyManagement>
        <dependencies>
            <!-- Praxis Platform Dependencies -->
            <dependency>
                <groupId>io.github.codexrodrigues</groupId>
                <artifactId>praxis-metadata-starter</artifactId>
                <version>${praxis.core.version}</version>
            </dependency>
            <dependency>
                <groupId>org.praxisplatform</groupId>
                <artifactId>praxis-spring-boot-starter</artifactId>
                <version>${praxis.core.version}</version>
            </dependency>
            <dependency>
                <groupId>org.praxisplatform</groupId>
                <artifactId>praxis-metadata-springdoc</artifactId>
                <version>${praxis.core.version}</version>
            </dependency>
            <dependency>
                <groupId>org.praxisplatform</groupId>
                <artifactId>praxis-bulk-starter</artifactId>
                <version>${praxis.bulk.version}</version>
            </dependency>
            <dependency>
                <groupId>org.praxisplatform</groupId>
                <artifactId>praxis-bulk-web</artifactId>
                <version>${praxis.bulk.version}</version>
            </dependency>
            <dependency>
                <groupId>org.springdoc</groupId>
                <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
                <version>${springdoc-openapi.version}</version>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <!-- Spring Boot Core Dependencies -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>

        <!-- Praxis Platform Dependencies -->
        <dependency>
            <groupId>org.praxisplatform</groupId>
            <artifactId>praxis-metadata-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>org.praxisplatform</groupId>
            <artifactId>praxis-spring-boot-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>org.praxisplatform</groupId>
            <artifactId>praxis-metadata-springdoc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.praxisplatform</groupId>
            <artifactId>praxis-bulk-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>org.praxisplatform</groupId>
            <artifactId>praxis-bulk-web</artifactId>
        </dependency>

        <!-- File Management (opcional) -->
        <dependency>
            <groupId>br.com.praxis</groupId>
            <artifactId>praxis-files-starter</artifactId>
            <version>1.1.0-SNAPSHOT</version>
        </dependency>

        <!-- Database & Migration -->
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>com.vladmihalcea</groupId>
            <artifactId>hibernate-types-60</artifactId>
            <version>2.21.1</version>
        </dependency>

        <!-- OpenAPI/Swagger Documentation -->
        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
        </dependency>

        <!-- Database Driver (escolha um) -->
        <!-- H2 para desenvolvimento -->
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>runtime</scope>
        </dependency>
        
        <!-- PostgreSQL para produção (descomentar se usar) -->
        <!--
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
        -->

        <!-- MapStruct para mapeamento avançado -->
        <dependency>
            <groupId>org.mapstruct</groupId>
            <artifactId>mapstruct</artifactId>
            <version>${org.mapstruct.version}</version>
        </dependency>

        <!-- Test Dependencies -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>junit</groupId>
            <artifactId>junit</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <resources>
            <resource>
                <directory>src/main/resources</directory>
                <filtering>false</filtering>
            </resource>
        </resources>
        
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
            
            <!-- Maven Compiler Plugin com MapStruct -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.11.0</version>
                <configuration>
                    <source>${java.version}</source>
                    <target>${java.version}</target>
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

    <!-- Profile para desenvolvimento offline -->
    <profiles>
        <profile>
            <id>go-offline</id>
            <build>
                <plugins>
                    <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-dependency-plugin</artifactId>
                        <version>3.6.0</version>
                        <executions>
                            <execution>
                                <id>go-offline</id>
                                <goals>
                                    <goal>go-offline</goal>
                                </goals>
                            </execution>
                        </executions>
                    </plugin>
                </plugins>
            </build>
        </profile>
    </profiles>
</project>
```

### 2. **Classe Principal - `{NomeApp}Application.java`**

```java
package {dominio-base};

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * <h1>🚀 {Nome da Aplicação}</h1>
 * 
 * <p>Classe principal da aplicação Spring Boot usando a arquitetura Praxis Platform.</p>
 * 
 * <h2>🔧 Configurações Automáticas</h2>
 * <ul>
 *   <li><strong>@SpringBootApplication:</strong> Configuração automática do Spring Boot</li>
 *   <li><strong>@EntityScan:</strong> Escaneia entidades JPA nos pacotes especificados</li>
 *   <li><strong>@EnableJpaRepositories:</strong> Habilita repositórios JPA</li>
 *   <li><strong>@EnableScheduling:</strong> Habilita funcionalidades de agendamento</li>
 * </ul>
 * 
 * <h2>📦 Pacotes Escaneados</h2>
 * <ul>
 *   <li><strong>{dominio-base}:</strong> Todos os componentes da aplicação</li>
 *   <li><strong>org.praxisplatform.bulk:</strong> Componentes de operações em lote</li>
 * </ul>
 * 
 * <h2>🌐 Endpoints Principais</h2>
 * <ul>
 *   <li><strong>Swagger UI:</strong> http://localhost:{porta}/swagger-ui.html</li>
 *   <li><strong>API Docs:</strong> http://localhost:{porta}/v3/api-docs</li>
 *   <li><strong>Actuator:</strong> http://localhost:{porta}/actuator</li>
 *   <li><strong>H2 Console:</strong> http://localhost:{porta}/h2-console (dev only)</li>
 * </ul>
 */
@SpringBootApplication(scanBasePackages = {"{dominio-base}", "org.praxisplatform.bulk"})
@EntityScan(basePackages = {"{dominio-base}.*.entity", "org.praxisplatform.bulk.entity"})
@EnableJpaRepositories(basePackages = {"{dominio-base}.*.repository", "org.praxisplatform.bulk.repository"})
@EnableScheduling
public class {NomeApp}Application {

    public static void main(String[] args) {
        SpringApplication.run({NomeApp}Application.class, args);
    }
}
```

### 3. **Constantes de API - `ApiPaths.java`**

```java
package {dominio-base}.common.constants;

/**
 * <h2>🗂️ Constantes Centralizadas de Paths da API</h2>
 * 
 * <p>Esta classe centraliza todos os paths das APIs do sistema, garantindo consistência
 * e facilitando manutenção. Os paths são organizados hierarquicamente por domínio.</p>
 * 
 * <h3>🎯 Benefícios</h3>
 * <ul>
 *   <li><strong>Consistência:</strong> Único local para definição de paths</li>
 *   <li><strong>Manutenção:</strong> Mudanças de path em uma única localização</li>
 *   <li><strong>Integração:</strong> Compatível com @ApiResource e DynamicSwaggerConfig</li>
 *   <li><strong>Refactoring:</strong> Suporte completo do IDE para mudanças</li>
 * </ul>
 * 
 * <h3>📋 Exemplo de Uso</h3>
 * <pre>{@code
 * @ApiResource(ApiPaths.{Modulo}.{ENTIDADES})
 * @ApiGroup("{grupo}")
 * public class {Entidade}Controller extends AbstractCrudController<...> {
 *     // Path detectado automaticamente, grupo OpenAPI criado
 * }
 * }</pre>
 */
public final class ApiPaths {
    
    /**
     * Path base para todas as APIs da aplicação.
     */
    public static final String BASE = "/api";
    
    // ⚠️ PARA CADA MÓDULO, CRIE UMA CLASSE INTERNA:
    
    /**
     * <h3>📦 {Nome do Módulo}</h3>
     * <p>Paths para operações relacionadas ao módulo de {descrição do módulo}.</p>
     */
    public static final class {Modulo} {
        private static final String {MODULO}_BASE = BASE + "/{modulo-kebab-case}";
        
        /**
         * Endpoint para gestão de {entidades}.
         * <br><strong>Padrão:</strong> /api/{modulo-kebab-case}/{entidades-kebab-case}
         * <br><strong>Usado por:</strong> {Entidade}Controller, {Entidade}BulkController, {Entidade}CapabilitiesController
         */
        public static final String {ENTIDADES} = {MODULO}_BASE + "/{entidades-kebab-case}";
        
        // ⚠️ REPITA PARA CADA ENTIDADE DO MÓDULO
        
        // Construtor privado para evitar instanciação
        private {Modulo}() {
            throw new IllegalStateException("Classe de constantes não deve ser instanciada");
        }
    }
    
    /**
     * <h3>🔧 Utilitários e Configurações</h3>
     * <p>Paths para operações utilitárias e de configuração.</p>
     */
    public static final class Utils {
        private static final String UTILS_BASE = BASE + "/utils";
        
        /**
         * Endpoint para testes e validações.
         * <br><strong>Padrão:</strong> /api/utils/test
         */
        public static final String TEST = UTILS_BASE + "/test";
        
        // Construtor privado para evitar instanciação
        private Utils() {
            throw new IllegalStateException("Classe de constantes não deve ser instanciada");
        }
    }
    
    // Construtor privado para evitar instanciação
    private ApiPaths() {
        throw new IllegalStateException("Classe de constantes não deve ser instanciada");
    }
}
```

### 4. **Configuração Web - `WebConfig.java`**

```java
package {dominio-base}.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * <h2>🌐 Configuração Web Global</h2>
 * 
 * <p>Configurações gerais para o comportamento web da aplicação, incluindo
 * políticas CORS, interceptadores e configurações de segurança básicas.</p>
 * 
 * <h3>🔧 Funcionalidades</h3>
 * <ul>
 *   <li><strong>CORS:</strong> Configuração de origens permitidas</li>
 *   <li><strong>Headers:</strong> Configuração de headers permitidos</li>
 *   <li><strong>Métodos HTTP:</strong> GET, POST, PUT, DELETE, OPTIONS</li>
 * </ul>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${app.cors.allowed-origins:http://localhost:4200}")
    private String[] allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
```

### 5. **Seeder de Dados - `DevDataSeeder.java`**

```java
package {dominio-base}.common.config;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;

/**
 * <h2>🌱 Configuração de População de Dados para Desenvolvimento</h2>
 * 
 * <p>Esta classe é responsável por popular o banco de dados com dados iniciais
 * durante o desenvolvimento, garantindo que a aplicação tenha dados para testes.</p>
 * 
 * <h3>🎯 Características</h3>
 * <ul>
 *   <li><strong>Profile:</strong> Ativa apenas no profile "dev"</li>
 *   <li><strong>Idempotente:</strong> Verifica se dados já existem antes de inserir</li>
 *   <li><strong>Flyway-Safe:</strong> Executa após migrações do Flyway</li>
 * </ul>
 */
@Configuration
@Profile("dev")
public class DevDataSeeder {

    @Bean
    ApplicationRunner seedData(DataSource dataSource, JdbcTemplate jdbcTemplate) {
        return args -> {
            // ⚠️ SUBSTITUA 'tabela_principal' pela tabela principal do seu sistema
            Integer count = 0;
            try {
                count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM {tabela_principal}", Integer.class);
            } catch (Exception ignored) {
                // Tabela pode não existir ainda; o ResourceDatabasePopulator cuidará disso
            }
            
            if (count != null && count > 0) {
                return; // Dados já existem, não popular novamente
            }

            ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
            populator.setContinueOnError(false);
            populator.addScript(new ClassPathResource("data.sql"));
            populator.execute(dataSource);
        };
    }
}
```

### 6. **Filtro de Log - `NotFoundLoggingFilter.java`**

```java
package {dominio-base}.common.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * <h2>🔍 Filtro de Log para Recursos Não Encontrados</h2>
 * 
 * <p>Filtro que intercepta todas as requisições HTTP e registra em log
 * quando recursos não são encontrados (HTTP 404), facilitando a depuração
 * e monitoramento da aplicação.</p>
 * 
 * <h3>📊 Funcionalidades</h3>
 * <ul>
 *   <li><strong>Log 404:</strong> Registra URLs que retornam 404</li>
 *   <li><strong>Debugging:</strong> Facilita identificação de endpoints quebrados</li>
 *   <li><strong>Monitoramento:</strong> Permite análise de padrões de acesso</li>
 * </ul>
 */
@Component
@WebFilter("/*")
public class NotFoundLoggingFilter implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(NotFoundLoggingFilter.class);

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) 
            throws IOException, ServletException {
        
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        chain.doFilter(request, response);

        // Log 404 errors para debugging
        if (httpResponse.getStatus() == 404) {
            logger.warn("🔍 Resource not found: {} {} - Status: 404", 
                    httpRequest.getMethod(), 
                    httpRequest.getRequestURI());
        }
    }
}
```

## 📊 **Configurações de Properties**

### **application.properties** (Base)

```properties
# Database Configuration
spring.datasource.url=jdbc:h2:mem:{nome-app};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE
spring.datasource.driverClassName=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=

# JPA / Hibernate Configuration
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.database-platform=org.hibernate.dialect.H2Dialect

# Flyway Migration
spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration/common,classpath:db/migration/h2

# Server Configuration
server.port={porta-personalizada:8080}

# Management/Actuator
management.endpoints.web.exposure.include=*
management.endpoint.health.show-details=always

# OpenAPI/Swagger Configuration
springdoc.api-docs.enabled=true
springdoc.swagger-ui.path=/swagger-ui.html
springdoc.swagger-ui.display-request-duration=true
springdoc.api-docs.groups.enabled=true
springdoc.api-docs.path=/v3/api-docs

# Active Profile
spring.profiles.active=dev
```

### **application-dev.properties** (Desenvolvimento)

```properties
# CORS Configuration for Development
app.cors.allowed-origins=http://localhost:4200,http://localhost:3000,http://localhost:8080

# Enable SQL logging for development
spring.jpa.show-sql=true
spring.jpa.properties.hibernate.format_sql=true
spring.jpa.hibernate.ddl-auto=update
spring.sql.init.mode=never

# H2 Console for Development
spring.h2.console.enabled=true
spring.h2.console.path=/h2-console

# Bulk Operations Configuration (se usar praxis-bulk)
praxis.bulk.export.base-path=./target/bulk-exports
praxis.bulk.export.url-ttl-minutes=60
praxis.bulk.maxInlineFailures=100

# File Management Configuration (se usar praxis-files)
file.management.security.permit-file-endpoints=true
file.management.security.require-authentication-by-default=false

# ⚠️ ADICIONE políticas bulk para CADA entidade:
# praxis.bulk.policy.{Entidade}.{campo}.operators=SET,INCREMENT,DECREMENT
# praxis.bulk.policy.{Entidade}.{campo}.min=0
```

### **application-prod.properties** (Produção)

```properties
# CORS Configuration for Production
app.cors.allowed-origins=https://app.{dominio}.com,https://{dominio}.com

# Database Configuration (PostgreSQL example)
spring.datasource.url=${DATABASE_URL}
spring.datasource.username=${DATABASE_USERNAME}
spring.datasource.password=${DATABASE_PASSWORD}
spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect

# Production JPA Settings
spring.jpa.show-sql=false
spring.jpa.hibernate.ddl-auto=validate

# Disable H2 Console in Production
spring.h2.console.enabled=false

# File Management Security (Production)
file.management.security.permit-file-endpoints=false
file.management.security.require-authentication-by-default=true

# Actuator Security
management.endpoints.web.exposure.include=health,info,metrics
management.endpoint.health.show-details=when_authorized
```

## 🗃️ **Schema de Banco de Dados**

### **db/migration/common/V1__initial_schema.sql**

```sql
-- ⚠️ TEMPLATE - SUBSTITUA pelas suas entidades

-- Tabela exemplo: Categorias (entidade simples)
CREATE TABLE categorias (
    id BIGSERIAL PRIMARY KEY,
    nome VARCHAR(100) NOT NULL UNIQUE,
    descricao TEXT,
    ativo BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Tabela exemplo: Produtos (entidade com relacionamento)
CREATE TABLE produtos (
    id BIGSERIAL PRIMARY KEY,
    nome VARCHAR(200) NOT NULL,
    codigo VARCHAR(50) UNIQUE NOT NULL,
    descricao TEXT,
    preco DECIMAL(10,2) NOT NULL CHECK (preco >= 0),
    categoria_id BIGINT REFERENCES categorias(id),
    ativo BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Índices para performance
CREATE INDEX idx_produtos_categoria ON produtos(categoria_id);
CREATE INDEX idx_produtos_codigo ON produtos(codigo);
CREATE INDEX idx_produtos_ativo ON produtos(ativo);

-- ⚠️ ADICIONE suas tabelas seguindo o padrão:
-- 1. id BIGSERIAL PRIMARY KEY
-- 2. Campos de negócio
-- 3. Relacionamentos com REFERENCES
-- 4. ativo BOOLEAN DEFAULT TRUE (se aplicável)  
-- 5. created_at, updated_at TIMESTAMP
-- 6. Índices para campos frequentemente consultados
```

### **data.sql** - Dados de Exemplo

```sql
-- ⚠️ DADOS DE EXEMPLO - SUBSTITUA pelos seus dados

-- Categorias de exemplo
INSERT INTO categorias (nome, descricao, ativo) VALUES
('Eletrônicos', 'Produtos eletrônicos e tecnológicos', true),
('Roupas', 'Vestuário e acessórios', true),
('Casa', 'Produtos para casa e decoração', true);

-- Produtos de exemplo  
INSERT INTO produtos (nome, codigo, descricao, preco, categoria_id, ativo) VALUES
('Smartphone XYZ', 'PHONE001', 'Smartphone com 128GB de armazenamento', 1299.99, 1, true),
('Camiseta Básica', 'SHIRT001', 'Camiseta básica 100% algodão', 49.90, 2, true),
('Mesa de Centro', 'TABLE001', 'Mesa de centro em madeira maciça', 599.99, 3, true);

-- ⚠️ ADICIONE dados para suas entidades seguindo o padrão:
-- INSERT INTO {tabela} ({campos}) VALUES ({valores});
```

## 🎯 **Fluxo de Criação Automática**

### **1. Estrutura Base**
1. Criar diretório raiz `{nome-aplicacao}/`
2. Gerar `pom.xml` com todas as dependências Praxis
3. Criar estrutura de pacotes Java
4. Configurar arquivos de properties (dev, prod)

### **2. Classe Principal e Configuração**
1. Criar `{NomeApp}Application.java` com scan automático
2. Implementar `ApiPaths.java` com constantes centralizadas
3. Configurar `WebConfig.java` para CORS
4. Adicionar filtros e configurações auxiliares

### **3. Banco de Dados**
1. Criar migration inicial `V1__initial_schema.sql`
2. Configurar `data.sql` com dados de exemplo
3. Implementar `DevDataSeeder.java` para população automática

### **4. Módulos de Negócio**
1. Para cada módulo fornecido pelo usuário:
   - Criar estrutura de pacotes (entity, dto, etc.)
   - Implementar seguindo o guia CRUD+Bulk existente
   - Adicionar paths no `ApiPaths.java`
   - Configurar políticas bulk no `application-dev.properties`

### **5. Testes e Validação**
1. Criar testes básicos de inicialização
2. Configurar profiles e validações
3. Testar compilação e execução

## ✅ **Checklist de Validação**

### **📁 Estrutura**
- [ ] Todos os diretórios criados corretamente
- [ ] Pacotes Java organizados hierarquicamente  
- [ ] Resources (properties, SQL) no local correto

### **📦 Dependências**
- [ ] pom.xml com todas as dependências Praxis
- [ ] Versões compatíveis entre bibliotecas
- [ ] MapStruct configurado no maven-compiler-plugin

### **🔧 Configuração**
- [ ] Classe principal com annotations corretas
- [ ] ApiPaths com constantes para todos os módulos
- [ ] WebConfig com CORS configurado
- [ ] Properties para dev/prod separados

### **🗃️ Banco de Dados**
- [ ] Migration SQL com estrutura inicial
- [ ] data.sql com dados de exemplo
- [ ] DevDataSeeder funcionando

### **🚀 Execução**
- [ ] Aplicação compila sem erros
- [ ] Inicia corretamente na porta especificada
- [ ] Swagger UI acessível
- [ ] H2 Console funcionando (dev)
- [ ] Endpoints básicos respondem

## 🎮 **Comandos de Validação**

```bash
# 1. Compilar aplicação
mvn clean compile -DskipTests

# 2. Executar testes
mvn test

# 3. Executar aplicação
mvn spring-boot:run

# 4. Validar endpoints (em terminal separado)
curl http://localhost:{porta}/actuator/health
curl http://localhost:{porta}/v3/api-docs

# 5. Acessar interfaces
# Swagger: http://localhost:{porta}/swagger-ui.html  
# H2 Console: http://localhost:{porta}/h2-console
```

## 📈 **Próximos Passos**

Após criar a aplicação base:

1. **Implementar Entidades:** Use o guia CRUD+Bulk para cada entidade
2. **Configurar Segurança:** Implementar autenticação se necessário
3. **Deploy:** Configurar CI/CD e ambientes
4. **Monitoramento:** Configurar logs e métricas
5. **Documentação:** Expandir README com informações específicas

**🎯 Com este guia, o Claude AI pode criar uma aplicação Praxis completa e funcional, pronta para desenvolvimento de funcionalidades específicas!**
