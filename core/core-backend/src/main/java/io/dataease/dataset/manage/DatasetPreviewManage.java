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

    public Map<String, Object> query(DatasetPreviewRequest request, boolean countOnly, boolean values) throws Exception {
        if (request.getPage() < 1 || request.getSize() < 1 || request.getSize() > 1000
                || (long) request.getPage() * request.getSize() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("分页参数无效，每页最多1000行");
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
                request.getSize(), checkPermissions, false, request, countOnly, values);
        result.remove("sql");
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
