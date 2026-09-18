package io.dataease.company.bi.feishu;

import java.util.Map;

public interface CompanyFeishuDirectoryGateway {

    Map<String, Object> search(String query);

    Map<String, Object> resolve(String userId);
}
