package org.praxisplatform.uischema.capability;

import org.praxisplatform.uischema.exporting.CollectionExportCapability;
import org.praxisplatform.uischema.stats.StatsFieldRegistry;
import org.praxisplatform.uischema.stats.StatsSupportMode;

/**
 * Agrega operacoes canonicas, surfaces e actions para uma colecao ou instancia.
 *
 * <p>
 * Esta interface expõe o snapshot consolidado de capabilities usado por consumidores runtime e
 * documentais. Ela agrega discovery semantico e availability contextual, mas nao redefine schemas
 * ou payloads das operacoes publicadas pelo recurso.
 * </p>
 */
public interface CapabilityService {

    /**
     * Resolve o snapshot de capabilities no escopo da colecao do recurso.
     */
    CapabilitySnapshot collectionCapabilities(String resourceKey, String resourcePath);

    default CapabilitySnapshot collectionCapabilities(
            String resourceKey,
            String resourcePath,
            boolean collectionExportSupported
    ) {
        return collectionCapabilities(resourceKey, resourcePath);
    }

    default CapabilitySnapshot collectionCapabilities(
            String resourceKey,
            String resourcePath,
            CollectionExportCapability collectionExportCapability
    ) {
        return collectionCapabilities(resourceKey, resourcePath, false, collectionExportCapability);
    }

    default CapabilitySnapshot collectionCapabilities(
            String resourceKey,
            String resourcePath,
            boolean collectionExportSupported,
            CollectionExportCapability collectionExportCapability
    ) {
        return collectionCapabilities(resourceKey, resourcePath, collectionExportSupported);
    }

    default CapabilitySnapshot collectionCapabilities(
            String resourceKey,
            String resourcePath,
            boolean collectionExportSupported,
            CollectionExportCapability collectionExportCapability,
            StatsFieldRegistry statsFieldRegistry
    ) {
        return collectionCapabilities(resourceKey, resourcePath, collectionExportSupported, collectionExportCapability);
    }

    default CapabilitySnapshot collectionCapabilities(
            String resourceKey,
            String resourcePath,
            boolean collectionExportSupported,
            CollectionExportCapability collectionExportCapability,
            StatsFieldRegistry statsFieldRegistry,
            StatsSupportMode groupByStatsSupportMode,
            StatsSupportMode timeSeriesStatsSupportMode,
            StatsSupportMode distributionStatsSupportMode
    ) {
        return collectionCapabilities(
                resourceKey,
                resourcePath,
                collectionExportSupported,
                collectionExportCapability,
                statsFieldRegistry
        );
    }

    default CapabilitySnapshot collectionCapabilities(
            String resourceKey, String resourcePath, boolean collectionExportSupported,
            CollectionExportCapability collectionExportCapability, StatsFieldRegistry statsFieldRegistry,
            StatsSupportMode groupByStatsSupportMode, StatsSupportMode timeSeriesStatsSupportMode,
            StatsSupportMode distributionStatsSupportMode, StatsSupportMode comparisonStatsSupportMode
    ) {
        return collectionCapabilities(resourceKey, resourcePath, collectionExportSupported, collectionExportCapability,
                statsFieldRegistry, groupByStatsSupportMode, timeSeriesStatsSupportMode, distributionStatsSupportMode);
    }

    /**
     * Resolve o snapshot de capabilities no escopo de um item especifico do recurso.
     */
    CapabilitySnapshot itemCapabilities(String resourceKey, String resourcePath, Object resourceId);

    default CapabilitySnapshot itemCapabilities(
            String resourceKey,
            String resourcePath,
            Object resourceId,
            StatsFieldRegistry statsFieldRegistry
    ) {
        return itemCapabilities(resourceKey, resourcePath, resourceId);
    }

    default CapabilitySnapshot itemCapabilities(
            String resourceKey,
            String resourcePath,
            Object resourceId,
            StatsFieldRegistry statsFieldRegistry,
            StatsSupportMode groupByStatsSupportMode,
            StatsSupportMode timeSeriesStatsSupportMode,
            StatsSupportMode distributionStatsSupportMode
    ) {
        return itemCapabilities(resourceKey, resourcePath, resourceId, statsFieldRegistry);
    }

    default CapabilitySnapshot itemCapabilities(
            String resourceKey, String resourcePath, Object resourceId, StatsFieldRegistry statsFieldRegistry,
            StatsSupportMode groupByStatsSupportMode, StatsSupportMode timeSeriesStatsSupportMode,
            StatsSupportMode distributionStatsSupportMode, StatsSupportMode comparisonStatsSupportMode
    ) {
        return itemCapabilities(resourceKey, resourcePath, resourceId, statsFieldRegistry,
                groupByStatsSupportMode, timeSeriesStatsSupportMode, distributionStatsSupportMode);
    }

    /**
     * Resolve somente a disponibilidade contextual de uma operacao canonica de colecao.
     */
    default AvailabilityDecision collectionOperationAvailability(
            String resourceKey,
            String resourcePath,
            String operationId
    ) {
        return AvailabilityDecision.allowAll();
    }

    /**
     * Resolve somente a disponibilidade contextual de uma operacao canonica de item.
     */
    default AvailabilityDecision itemOperationAvailability(
            String resourceKey,
            String resourcePath,
            String operationId,
            Object resourceId
    ) {
        return AvailabilityDecision.allowAll();
    }
}
