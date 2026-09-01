package io.dataease.company.bi.auth;

import io.dataease.api.permissions.login.dto.PwdLoginDTO;
import io.dataease.auth.vo.TokenVO;
import io.dataease.exception.DEException;
import io.dataease.utils.LogUtil;
import io.dataease.utils.RsaUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Component("loginServer")
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
@RestController
@RequestMapping
public class CompanyLoginServer {

    private final CompanyLoginService loginService;

    public CompanyLoginServer(CompanyLoginService loginService) {
        this.loginService = loginService;
    }

    @PostMapping("/login/localLogin")
    public TokenVO localLogin(@RequestBody PwdLoginDTO dto) {
        String name;
        String pwd;
        try {
            name = RsaUtils.decryptStr(dto.getName());
            pwd = RsaUtils.decryptStr(dto.getPwd());
        } catch (Exception e) {
            LogUtil.error("RSA decrypt failed for localLogin", e);
            DEException.throwException("登录参数无效");
            return null;
        }
        return loginService.login(name, pwd);
    }

    @GetMapping("/logout")
    public void logout() {
        LogUtil.info("company bi logout");
    }
}
