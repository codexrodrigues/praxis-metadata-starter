package org.praxisplatform.uischema.bulk;

import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageNotReadableException;

/**
 * Exceção de transporte lançada quando o corpo de uma requisição do protocolo bulk viola o
 * contrato estrito de entrada (JSON malformado, propriedades desconhecidas/duplicadas, tipo de
 * identidade incompatível ou ausência de campos obrigatórios).
 *
 * <p>Mantém deliberadamente uma mensagem pública fixa e não anexa causa Jackson ou conteúdo da
 * requisição para evitar vazamento de payloads ou dados protegidos em diagnósticos públicos.</p>
 */
public final class BulkProtocolPayloadException extends HttpMessageNotReadableException {

    public static final String PUBLIC_MESSAGE =
            "Invalid JSON payload or payload incompatible with the bulk protocol contract.";

    public BulkProtocolPayloadException(HttpInputMessage httpInputMessage) {
        super(PUBLIC_MESSAGE, httpInputMessage);
    }
}
