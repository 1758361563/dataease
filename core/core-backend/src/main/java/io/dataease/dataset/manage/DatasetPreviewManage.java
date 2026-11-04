package io.dataease.dataset.manage;

import io.dataease.api.dataset.union.DatasetGroupInfoDTO;
import io.dataease.api.permissions.auth.dto.BusiPerCheckDTO;
import io.dataease.constant.AuthEnum;
import io.dataease.dataset.utils.DatasetUtils;
import io.dataease.exception.DEException;
import io.dataease.system.manage.CorePermissionManage;
import io.dataease.utils.JsonUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class DatasetPreviewManage {
    @Resource private DatasetGroupManage groups;
    @Resource private DatasetDataManage data;
    @Resource private CorePermissionManage permissions;

    public DatasetGroupInfoDTO resolve(DatasetPreviewRequest request, boolean export) throws Exception {
        if (request.getId() != null && request.getId() > 0) {
            require(request.getId(), export ? AuthEnum.EXPORT : AuthEnum.READ);
            if (request.getDraft() != null) require(request.getId(), AuthEnum.MANAGE);
        } else if (request.getDraft() == null) {
            throw new IllegalArgumentException("请选择数据集");
        }
        DatasetGroupInfoDTO dto;
        if (request.getDraft() != null) {
            dto = JsonUtil.parseObject(JsonUtil.toJSONString(request.getDraft()).toString(), DatasetGroupInfoDTO.class);
            DatasetUtils.dsDecode(dto);
            dto.setId(request.getId());
        } else {
            dto = groups.getDatasetGroupInfoDTO(request.getId(), null);
        }
        if (dto == null || dto.getAllFields() == null || dto.getAllFields().isEmpty()) {
            throw new IllegalArgumentException("数据集不存在或没有输出字段");
        }
        dto.setUnionSql(null);
        return dto;
    }

    private void require(Long id, AuthEnum auth) {
        if (!permissions.checkAuth(new BusiPerCheckDTO(id, auth))) DEException.throwException("没有数据集访问权限");
    }

    public Object fields(DatasetPreviewRequest request) throws Exception {
        return data.previewFields(resolve(request, false));
    }
    public void cancel(String queryId) {
        PreviewExecution.cancel(queryId, io.dataease.utils.AuthUtils.getUser().getUserId());
    }
    public Map<String, Object> query(DatasetPreviewRequest request, boolean countOnly, boolean values) throws Exception {
        long started = System.nanoTime();
        try (PreviewExecution run = PreviewExecution.open(request.getQueryId(), io.dataease.utils.AuthUtils.getUser().getUserId(), countOnly, Math.min(201, request.getSize() + 1))) {
            run.check();
            Map<String,Object> result;
            try {
                result = execute(request, countOnly, values);
            } catch (Exception e) {
                run.check();
                String message = Objects.toString(e.getMessage(), "").toLowerCase(Locale.ROOT);
                if (message.contains("timeout") || message.contains("timed out")) {
                    DEException.throwException("查询超时，请缩小筛选范围或减少显示列");
                }
                throw e;
            }
            run.check();
            return result;
        } catch (IllegalArgumentException e) {
            DEException.throwException(e.getMessage());
            return null;
        } finally {
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            if (elapsed >= 1000) org.slf4j.LoggerFactory.getLogger(getClass()).info(
                    "Dataset preview finished: dataset={}, query={}, kind={}, elapsedMs={}",
                    request.getId(), request.getQueryId(), countOnly ? "count" : values ? "values" : "rows", elapsed);
        }
    }
    private Map<String, Object> execute(DatasetPreviewRequest request, boolean countOnly, boolean values) throws Exception {
        if (request.getPage() < 1 || request.getSize() < 1 || request.getSize() > 200
                || (long) request.getPage() * request.getSize() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("分页参数无效，每页最多200行");
        }
        if (!countOnly && (long)(request.getPage() - 1) * request.getSize() >= 10000) {
            throw new IllegalArgumentException("预览已超过一万行，请按日期或编码筛选定位；完整数据可使用导出");
        }
        DatasetGroupInfoDTO dto = resolve(request, false);
        if (values) {
            if (request.getValueFieldId() == null) throw new IllegalArgumentException("请选择候选值字段");
            request.setRules(new ArrayList<>());
            request.setLogic("and");
            request.setColumnIds(List.of(request.getValueFieldId()));
            if (request.getSearchText() != null && !request.getSearchText().isBlank()) {
                DatasetPreviewRequest.Rule rule = new DatasetPreviewRequest.Rule();
                rule.setFieldId(request.getValueFieldId());
                rule.setOperator("like");
                rule.setValues(List.of(request.getSearchText()));
                request.getRules().add(rule);
            }
        }
        boolean checkPermissions = dto.getId() != null && dto.getId() > 0;
        Map<String, Object> result = data.previewDataWithLimit(dto, (request.getPage() - 1) * request.getSize(),
                request.getSize() + (countOnly ? 0 : 1), checkPermissions, false, request, countOnly, values);
        if (!countOnly) {
            Map<String,Object> page = (Map<String,Object>) result.get("data");
            List<?> rows = (List<?>) page.get("data");
            boolean hasMore = rows.size() > request.getSize();
            result.put("hasMore", hasMore);
            result.put("orderConfigured", values || (dto.getSortFields() != null && dto.getSortFields().stream()
                    .anyMatch(sort -> request.getColumnIds() == null || request.getColumnIds().contains(String.valueOf(sort.getId())))));
            if (hasMore) page.put("data", new ArrayList<>(rows.subList(0, request.getSize())));
        }
        result.remove("sql");
        result.remove("allFields");
        return result;
    }

    /** Store a decoded definition in task parameters, then re-encode for the normal request boundary. */
    public void snapshot(DatasetPreviewRequest request) throws Exception {
        DatasetGroupInfoDTO dto = resolve(request, true);
        request.conditionTree(dto.getAllFields());
        DatasetUtils.listEncode(dto.getAllFields());
        // dsDecode/listEncode also handle originName encoding used by the editor API.
        request.setDraft(dto);
    }

    public Map<String, Object> exportPage(DatasetPreviewRequest snapshot, int start, int size, boolean count) throws Exception {
        if (snapshot.getId() != null && snapshot.getId() > 0) require(snapshot.getId(), AuthEnum.EXPORT);
        DatasetGroupInfoDTO dto = JsonUtil.parseObject(JsonUtil.toJSONString(snapshot.getDraft()).toString(), DatasetGroupInfoDTO.class);
        DatasetUtils.dsDecode(dto);
        return data.previewDataWithLimit(dto, start, size, dto.getId() != null && dto.getId() > 0,
                false, snapshot, count, false);
    }
}
