package org.praxisplatform.uischema.numeric;

/**
 * Enum que define os estilos de formatação numérica para campos.
 * Estes estilos determinam como os valores numéricos devem ser apresentados na interface do usuário.
 */
public enum NumberFormatStyle {

    /**
     * Formato de moeda.
     * Exemplo: Exibe o valor como "$1,234.56".
     */
    CURRENCY("currency"),

    /**
     * Formato decimal padrão.
     * Exemplo: Exibe o valor como "1234.56".
     */
    DECIMAL("decimal"),

    /**
     * Formato de percentual.
     * Exemplo: Exibe o valor como "12.34%". (Multiplica por 100 e adiciona o símbolo '%')
     */
    PERCENT("percent"),

    /**
     * Formato científico (notação exponencial).
     * Exemplo: Exibe o valor como "1.23456E3".
     */
    SCIENTIFIC("scientific"),

    /**
     * Sem formatação especial.
     * Exemplo: Exibe o valor como está, sem separadores, símbolos ou formatação especial.
     */
    NONE("none");

    private final String value;

    /**
     * Construtor para o Enum NumberFormatStyle.
     * @param value O valor string associado ao estilo de formato.
     */
    NumberFormatStyle(String value) {
        this.value = value;
    }

    /**
     * Obtém o valor string do estilo de formato numérico.
     * Este valor é usado para identificar o estilo de formato em configurações ou APIs.
     *
     * @return O valor string do estilo de formato.
     */
    public String getValue() {
        return value;
    }

    /**
     * Converte um valor string para o Enum NumberFormatStyle correspondente.
     * A comparação é feita de forma case-insensitive.
     *
     * @param value O valor string a ser convertido (ex: "currency", "decimal").
     * @return O Enum NumberFormatStyle correspondente.
     * @throws IllegalArgumentException se o valor string não corresponder a nenhum estilo de formato conhecido.
     */
    public static NumberFormatStyle fromValue(String value) {
        for (NumberFormatStyle style : values()) {
            if (style.value.equalsIgnoreCase(value)) {
                return style;
            }
        }
        throw new IllegalArgumentException("Estilo de formato numérico desconhecido: " + value);
    }
}
