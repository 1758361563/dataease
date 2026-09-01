package io.dataease.company.bi.permission;

import io.dataease.company.bi.auth.CompanyAuthSupport;
import io.dataease.dataset.dao.auto.entity.CoreDatasetGroup;
import io.dataease.dataset.dao.auto.mapper.CoreDatasetGroupMapper;
import io.dataease.datasource.dao.auto.entity.CoreDatasource;
import io.dataease.datasource.dao.auto.mapper.CoreDatasourceMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class CompanyResourceTypeLookup {

    public enum Kind {
        DATASET,
        DATASOURCE,
        UNKNOWN
    }

    private static final ThreadLocal<Map<Long, Kind>> REQUEST_CACHE = ThreadLocal.withInitial(HashMap::new);

    private final CoreDatasetGroupMapper datasetGroupMapper;
    private final CoreDatasourceMapper datasourceMapper;

    public CompanyResourceTypeLookup(CoreDatasetGroupMapper datasetGroupMapper,
                                     CoreDatasourceMapper datasourceMapper) {
        this.datasetGroupMapper = datasetGroupMapper;
        this.datasourceMapper = datasourceMapper;
    }

    public Kind identify(Long id) {
        if (id == null) {
            return Kind.UNKNOWN;
        }
        return REQUEST_CACHE.get().computeIfAbsent(id, this::lookup);
    }

    private Kind lookup(Long id) {
        CoreDatasetGroup dataset = datasetGroupMapper.selectById(id);
        if (dataset != null) {
            return Kind.DATASET;
        }
        CoreDatasource datasource = datasourceMapper.selectById(id);
        if (datasource != null) {
            return Kind.DATASOURCE;
        }
        return Kind.UNKNOWN;
    }

    public static void clearRequestCache() {
        REQUEST_CACHE.remove();
    }
}
