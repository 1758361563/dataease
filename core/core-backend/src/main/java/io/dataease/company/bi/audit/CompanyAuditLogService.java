package io.dataease.company.bi.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dataease.company.bi.admin.CompanyAdminAuditContext;
import io.dataease.company.bi.auth.CompanyAuthSupport;
import io.dataease.utils.IDUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

@Component
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class CompanyAuditLogService implements CompanyAuditRecorder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CompanyBiAuditLogMapper mapper;
    private final LongSupplier idGenerator;

    @Autowired
    public CompanyAuditLogService(CompanyBiAuditLogMapper mapper) {
        this(mapper, IDUtils::snowID);
    }

    public CompanyAuditLogService(CompanyBiAuditLogMapper mapper, LongSupplier idGenerator) {
        this.mapper = mapper;
        this.idGenerator = idGenerator == null ? IDUtils::snowID : idGenerator;
    }

    @Override
    public void record(CompanyAdminAuditContext context, CompanyAuditActionType actionType, String targetType,
                       Long targetId, String beforeJson, String afterJson) {
        if (context == null || actionType == null) {
            throw new IllegalStateException("audit context required");
        }
        assertNoSecrets(beforeJson);
        assertNoSecrets(afterJson);
        CompanyBiAuditLog row = new CompanyBiAuditLog();
        row.setId(idGenerator.getAsLong());
        row.setOperatorUserId(context.operatorUserId());
        row.setActionType(actionType.name());
        row.setTargetType(targetType);
        row.setTargetId(targetId);
        row.setBeforeJson(beforeJson);
        row.setAfterJson(afterJson);
        row.setIp(context.ip());
        row.setRequestId(context.requestId());
        row.setCreatedAt(System.currentTimeMillis());
        mapper.insert(row);
    }

    public static String json(Map<String, ?> fields) {
        if (fields == null || fields.isEmpty()) {
            return null;
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : fields.entrySet()) {
            if (entry.getKey() == null || isSecretKey(entry.getKey())) {
                continue;
            }
            copy.put(entry.getKey(), entry.getValue());
        }
        if (copy.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(copy);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("audit json encode failed", e);
        }
    }

    public static String passwordChangedJson() {
        return "{\"passwordChanged\":true}";
    }

    public static void assertNoSecrets(String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        String lower = json.toLowerCase(Locale.ROOT);
        if (lower.contains("password") && !lower.contains("passwordchanged")) {
            throw new IllegalStateException("audit json must not contain password");
        }
        if (lower.contains("password_hash") || lower.contains("passwordhash")) {
            throw new IllegalStateException("audit json must not contain password hash");
        }
        if (lower.contains("\"token\"") || lower.contains("secret")) {
            throw new IllegalStateException("audit json must not contain token or secret");
        }
    }

    private static boolean isSecretKey(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.contains("password") || lower.contains("token") || lower.contains("secret") || lower.contains("hash");
    }
}
