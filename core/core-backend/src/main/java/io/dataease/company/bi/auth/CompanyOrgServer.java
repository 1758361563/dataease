package io.dataease.company.bi.auth;

import io.dataease.api.permissions.org.vo.MountedVO;
import io.dataease.model.KeywordRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@Component("orgServer")
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
@RestController
@RequestMapping("/org")
public class CompanyOrgServer {

    @PostMapping("/mounted")
    public List<MountedVO> mounted(KeywordRequest request) {
        MountedVO mountedVO = new MountedVO();
        mountedVO.setId(CompanyTokenIssuer.DEFAULT_OID);
        mountedVO.setName("默认组织");
        mountedVO.setReadOnly(false);
        List<MountedVO> result = new ArrayList<>();
        result.add(mountedVO);
        return result;
    }
}
