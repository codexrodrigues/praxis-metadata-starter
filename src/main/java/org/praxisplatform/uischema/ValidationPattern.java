package org.praxisplatform.uischema;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ValidationPattern {
    EMAIL("^[\\w\\.-]+@[\\w\\.-]+\\.[a-zA-Z]{2,}$"),
    URL("^(https?|ftp)://[\\w\\.-]+(:\\d+)?(/[\\w\\.-]*)*/?$"),
    DATE("^(0[1-9]|[12][0-9]|3[01])/(0[1-9]|1[012])/(\\d{4})$"),
    TIME("^(0[0-9]|1[0-9]|2[0-3]):([0-5][0-9])(:([0-5][0-9]))?$"),
    DATETIME("^(0[1-9]|[12][0-9]|3[01])/(0[1-9]|1[012])/(\\d{4}) (0[0-9]|1[0-9]|2[0-3]):([0-5][0-9])(:([0-5][0-9]))?$"),
    CPF("^\\d{3}\\.\\d{3}\\.\\d{3}-\\d{2}$"),
    CEP_BR("^\\d{5}-\\d{3}$"),
    RG("^\\d{1,2}\\.\\d{3}\\.\\d{3}-[0-9X]$"),
    CNPJ("^\\d{2}\\.\\d{3}\\.\\d{3}/\\d{4}-\\d{2}$"),
    CELULAR_BR("^\\(\\d{2}\\) \\d{5}-\\d{4}$"),
    TELEFONE_BR("^\\(\\d{2}\\) \\d{4}-\\d{4}$"),
    PHONE("^\\(\\d{2}\\) \\d{4,5}-\\d{4}$"),
    ONLY_NUMBERS("^\\d+$"),
    ONLY_NUMBERS_WITH_DASH("^\\d+-?\\d+$"),
    ONLY_LETTERS("^[A-Za-zÀ-ÿ\\s]+$"),
    ONLY_LETTERS_WITH_DASH("^[A-Za-zÀ-ÿ\\s-]+$"),
    ONLY_LETTERS_WITH_DASH_AND_NUMBERS("^[A-Za-zÀ-ÿ\\s\\d-]+$"),
    ONLY_LETTERS_WITH_NUMBERS("^[A-Za-zÀ-ÿ\\d\\s]+$"),
    ONLY_LETTERS_WITH_NUMBERS_AND_DASH("^[A-Za-zÀ-ÿ\\d\\s-]+$"),
    ONLY_LETTERS_WITH_NUMBERS_AND_UNDERSCORE("^[A-Za-zÀ-ÿ\\d_\\s]+$"),
    // Padrões para nomes de pessoa
    NAME_SIMPLE("^[A-Za-zÀ-ÿ]{2,}$"),                                       // Nome simples sem espaços
    NAME_WITH_SPACES("^[A-Za-zÀ-ÿ\\s]{2,}$"),                               // Nome com espaços
    FULL_NAME("^[A-Za-zÀ-ÿ]+\\s[A-Za-zÀ-ÿ\\s]+$"),                          // Nome e sobrenome (pelo menos dois componentes)
    FULL_NAME_WITH_PREPOSITIONS("^[A-Za-zÀ-ÿ]+(?:\\s(?:da|de|do|das|dos|e|[A-Za-zÀ-ÿ])+)+$"), // Nome com preposições comuns em português
    NAME_WITH_APOSTROPHE("^[A-Za-zÀ-ÿ'\\s-]+$"),                            // Nomes com apóstrofo (D'Artagnan, O'Brien)
    NAME_WITH_SPECIAL_CHARS("^[A-Za-zÀ-ÿ'\\s\\-\\.]+$"),                    // Nomes com caracteres especiais (hífen, ponto)
    INITIALS_WITH_LAST_NAME("^(?:[A-Z]\\.\\s)*[A-Za-zÀ-ÿ]+(?:\\s[A-Za-zÀ-ÿ]+)+$"), // Iniciais com sobrenome (J. R. Santos)

    // Documentos e identificadores corporativos
    PASSPORT_BR("^[A-Z]{2}[0-9]{6}$"),                            // Passaporte brasileiro padrão
    PIS_PASEP("^\\d{3}\\.\\d{5}\\.\\d{2}-\\d{1}$"),              // PIS/PASEP formato 000.00000.00-0
    TITULO_ELEITOR("^\\d{4}\\.\\d{4}\\.\\d{4}$"),                // Título de eleitor formato 0000.0000.0000
    CARTEIRA_TRABALHO("^\\d{7,8}-\\d{1}$"),                      // CTPS formato 0000000-0
    REGISTRO_PROFISSIONAL("^[A-Z]{2,3}-\\d{4,6}$"),              // Registro profissional (CRM, OAB, CREA)

    // Dados bancários
    AGENCIA_BANCARIA("^\\d{4}(-\\d{1})?$"),                     // Agência bancária com ou sem dígito
    CONTA_BANCARIA("^\\d{5,12}(-\\d{1,2})?$"),                  // Conta bancária com ou sem dígito
    CODIGO_BANCO("^\\d{3}$"),                                   // Código de banco (3 dígitos)

    // Endereço
    ENDERECO_COMPLETO("^[A-Za-zÀ-ÿ0-9\\s,.\\-]{10,}$"),        // Endereço com número e complemento
    NUMERO_ENDERECO("^\\d{1,6}([A-Za-z]|-\\d{1,3})?$"),        // Número de endereço (pode incluir letras ou complementos)

    // Códigos e referências
    UUID("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"), // UUID padrão
    CODIGO_BARRAS_EAN13("^\\d{13}$"),                           // Código de barras EAN-13
    CODIGO_BARRAS_BOLETO("^\\d{44,48}$"),                       // Código de barras de boleto bancário
    CODIGO_RASTREIO_CORREIOS("^[A-Z]{2}\\d{9}[A-Z]{2}$"),      // Código de rastreio dos correios

    // Segurança
    PASSWORD_MEDIUM("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,}$"),  // Senha média (maiúscula, minúscula, número, 8+ caracteres)
    PASSWORD_STRONG("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@#$%^&+=!]).{10,}$"), // Senha forte com caracteres especiais
    TOKEN_AUTH("^[A-Za-z0-9-_]{20,}\\.[A-Za-z0-9-_]{20,}\\.[A-Za-z0-9-_]{20,}$"), // Formato token JWT

    // Comunicação
    IP_V4("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$"),                  // Endereço IPv4
    IP_V6("^(?:[A-F0-9]{1,4}:){7}[A-F0-9]{1,4}$"),             // Endereço IPv6 simplificado
    MAC_ADDRESS("^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$"),  // Endereço MAC
    DOMAIN_NAME("^([a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,}$"), // Nome de domínio

    // Fiscal
    INSCRICAO_ESTADUAL("^\\d{8,14}$"),                         // Inscrição Estadual (formato simplificado)
    INSCRICAO_MUNICIPAL("^\\d{8,15}$"),                        // Inscrição Municipal (formato simplificado)
    PROCESSO_JUDICIAL("^\\d{7}-\\d{2}\\.\\d{4}\\.\\d{1}\\.\\d{2}\\.\\d{4}$"), // Número de processo judicial

    // Internacionais
    PASSPORT_INTL("^[A-Z0-9]{6,9}$"),                         // Passaporte internacional (formato genérico)
    PHONE_INTL("^\\+\\d{1,3}\\s?\\(\\d{1,3}\\)\\s?\\d{3,10}$"), // Telefone internacional
    SSN_US("^\\d{3}-\\d{2}-\\d{4}$"),

    CUSTOM(""); // permite custom regex pelo dev

    private final String pattern;
    ValidationPattern(String pattern) { this.pattern = pattern; }
    @JsonValue
    public String getPattern() { return pattern; }
}

