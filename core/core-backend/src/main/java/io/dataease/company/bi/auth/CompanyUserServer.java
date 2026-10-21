package io.dataease.company.bi.auth;

import io.dataease.api.permissions.user.dto.LangSwitchRequest;
import io.dataease.api.permissions.user.dto.ModifyPwdRequest;
import io.dataease.api.permissions.user.vo.CurIpVO;
import io.dataease.api.permissions.user.vo.CurUserVO;
import io.dataease.api.permissions.user.vo.UserFormVO;
import io.dataease.auth.bo.TokenUserBO;
import io.dataease.company.bi.user.CompanyBiUser;
import io.dataease.company.bi.user.CompanyBiUserService;
import io.dataease.exception.DEException;
import io.dataease.i18n.Lang;
import io.dataease.utils.AuthUtils;
import io.dataease.utils.CacheUtils;
import io.dataease.utils.IPUtils;
import io.dataease.utils.RsaUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static io.dataease.constant.CacheConstant.UserCacheConstant.USER_COMMUNITY_LANGUAGE;

@Component("userServer")
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
@RestController
@RequestMapping("/user")
public class CompanyUserServer {

    private final CompanyBiUserService userService;
    private final CompanyUserStatusGuard statusGuard;
    private final CompanyPasswordChangeService passwordChangeService;

    public CompanyUserServer(CompanyBiUserService userService, CompanyUserStatusGuard statusGuard,
                             CompanyPasswordChangeService passwordChangeService) {
        this.userService = userService;
        this.statusGuard = statusGuard;
        this.passwordChangeService = passwordChangeService;
    }

    @PostMapping("/modifyPwd")
    public void modifyPwd(@RequestBody ModifyPwdRequest request) {
        CompanyBiUser user = currentUser();
        if (request == null || (request.getUid() != null && !user.getId().equals(request.getUid()))) {
            DEException.throwException("只能修改当前用户的密码");
        }
        String oldPassword;
        String newPassword;
        try {
            oldPassword = RsaUtils.decryptStr(request.getPwd());
            newPassword = RsaUtils.decryptStr(request.getNewPwd());
        } catch (Exception e) {
            DEException.throwException("密码参数无效，请刷新页面重试");
            return;
        }
        passwordChangeService.change(user, oldPassword, newPassword);
    }

    @GetMapping("/info")
    public CurUserVO info() {
        CompanyBiUser user = currentUser();
        CurUserVO vo = new CurUserVO();
        vo.setId(user.getId());
        vo.setName(displayName(user));
        vo.setOid(CompanyTokenIssuer.DEFAULT_OID);
        vo.setLanguage(currentLanguage());
        return vo;
    }

    @GetMapping("/personInfo")
    public UserFormVO personInfo() {
        CompanyBiUser user = currentUser();
        UserFormVO vo = new UserFormVO();
        vo.setId(user.getId());
        vo.setAccount(user.getUsername());
        vo.setName(displayName(user));
        vo.setIp(IPUtils.get());
        vo.setEnable(user.isEnabled());
        vo.setModel("lose");
        return vo;
    }

    @GetMapping("/ipInfo")
    public CurIpVO ipInfo() {
        CompanyBiUser user = currentUser();
        CurIpVO vo = new CurIpVO();
        vo.setAccount(user.getUsername());
        vo.setName(displayName(user));
        vo.setIp(IPUtils.get());
        return vo;
    }

    @PostMapping("/switchLanguage")
    public void switchLanguage(@RequestBody LangSwitchRequest request) {
        String lang = request.getLang();
        if (StringUtils.equalsIgnoreCase(Lang.zh_CN.getDesc(), lang)) {
            lang = Lang.zh_CN.getDesc();
        } else if (StringUtils.equalsAnyIgnoreCase(lang, "en", "tw")) {
            lang = lang.toLowerCase();
        } else {
            DEException.throwException("无效language");
        }
        CacheUtils.put(USER_COMMUNITY_LANGUAGE, "de", lang);
    }

    private CompanyBiUser currentUser() {
        TokenUserBO tokenUser = AuthUtils.getUser();
        if (tokenUser == null || tokenUser.getUserId() == null) {
            DEException.throwException("用户未登录");
        }
        return statusGuard.requireActive(tokenUser.getUserId());
    }

    private static String displayName(CompanyBiUser user) {
        return StringUtils.isNotBlank(user.getDisplayName()) ? user.getDisplayName() : user.getUsername();
    }

    private static String currentLanguage() {
        Object langObj = CacheUtils.get(USER_COMMUNITY_LANGUAGE, "de");
        if (ObjectUtils.isNotEmpty(langObj) && StringUtils.isNotBlank(langObj.toString())) {
            return langObj.toString();
        }
        return Lang.zh_CN.getDesc();
    }
}
