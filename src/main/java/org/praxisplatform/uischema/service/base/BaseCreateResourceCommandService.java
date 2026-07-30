package org.praxisplatform.uischema.service.base;

/**
 * Boundary canonico para resources que aceitam criacao, mas nao publicam atualizacao ou exclusao.
 *
 * <p>Esse recorte permite migracoes e dominios em que inserir uma nova entidade e seguro, enquanto
 * alterar uma entidade existente exige concorrencia, autorizacao ou invariantes ainda nao
 * disponiveis. A ausencia das demais operacoes faz parte do contrato HTTP; implementacoes nao
 * devem simula-las com respostas tardias de unsupported.</p>
 */
public interface BaseCreateResourceCommandService<ResponseDTO, ID, CreateDTO> {

    BaseCreateUpdateResourceCommandService.SavedResult<ID, ResponseDTO> create(CreateDTO dto);
}
