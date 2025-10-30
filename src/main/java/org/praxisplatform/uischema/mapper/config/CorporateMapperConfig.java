package org.praxisplatform.uischema.mapper.config;

import org.mapstruct.MapperConfig;
import org.mapstruct.ReportingPolicy;

/**
 * Configuração compartilhada do MapStruct para mapeadores corporativos.
 * <ul>
 *   <li>{@code componentModel = "spring"} para integração via DI</li>
 *   <li>{@code unmappedTargetPolicy = ERROR} para fail-fast em campos não mapeados</li>
 * </ul>
 *
 * @since 1.0.0
 */
@MapperConfig(
    componentModel = "spring",
    unmappedTargetPolicy = ReportingPolicy.ERROR
)
public interface CorporateMapperConfig {}

