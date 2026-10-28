package io.dataease.company.bi.etl;

import io.dataease.datasource.manage.DataSourceManage;
import io.dataease.datasource.provider.CalciteProvider;
import io.dataease.extensions.datasource.dto.ConnectionObj;
import io.dataease.extensions.datasource.provider.Provider;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.sql.Connection;
import static io.dataease.company.bi.etl.EtlModel.*;

@Component
@ConditionalOnProperty(name="company.bi.auth.enabled", havingValue="true", matchIfMissing=true)
public class EtlConnections {
    private final DataSourceManage sources;
    private final CalciteProvider provider;
    public EtlConnections(DataSourceManage sources, CalciteProvider provider) { this.sources = sources; this.provider = provider; }
    public Handle open(long datasource) throws Exception {
        var dto = sources.getDs(datasource);
        require(dto != null && "mysql".equals(dto.getType()), "请选择 MySQL 刷新专用数据源");
        return new Handle(provider.getConnection(dto));
    }
    public static final class Handle implements AutoCloseable {
        private final ConnectionObj object;
        public Handle(ConnectionObj object) { this.object = object; }
        public Connection connection() { return object.getConnection(); }
        public void close() throws Exception {
            try { if (object.getConnection() != null && !object.getConnection().isClosed()) object.getConnection().close(); }
            finally {
                try { if (object.getSession() != null) object.getSession().disconnect(); }
                finally { if (object.getLPort() != null) Provider.getLPorts().remove(object.getLPort().longValue()); }
            }
        }
    }
}
