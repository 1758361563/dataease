package io.dataease.company.bi.feishu;

import io.dataease.auth.vo.TokenVO;
import io.dataease.company.bi.auth.CompanyAuthSupport;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/login/feishu")
@ConditionalOnProperty(name = CompanyAuthSupport.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
public class FeishuLoginController {
    private final FeishuLoginService login;
    private final FeishuLoginSettings settings;
    private final java.util.LinkedHashMap<String, long[]> limits = new java.util.LinkedHashMap<>();
    private synchronized void limit(String address) {
        long now = System.currentTimeMillis();
        limits.entrySet().removeIf(e -> now - e.getValue()[0] > 60_000);
        long[] bucket = limits.get(address);
        if (bucket == null) {
            if (limits.size() >= 4096) {
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "请稍后重试");
            }
            bucket = new long[]{now, 0}; limits.put(address, bucket);
        }
        if (++bucket[1] > 60) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "登录请求过于频繁，请稍后重试");
    }
    public FeishuLoginController(FeishuLoginService login, FeishuLoginSettings settings) { this.login=login; this.settings=settings; }
    @GetMapping("/config")
    public Map<String,Object> config(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Map.of("enabled", Boolean.TRUE.equals(settings.get().get("enabled")));
    }
    @PostMapping("/start")
    public Map<String,Object> start(@RequestBody Map<String,String> body, jakarta.servlet.http.HttpServletRequest request, HttpServletResponse response) {
        limit(request.getRemoteAddr());
        response.setHeader("Cache-Control", "no-store");
        return login.start(body.get("verifier"), body.get("mode"));
    }
    @PostMapping("/complete")
    public TokenVO complete(@RequestBody Map<String,String> body, jakarta.servlet.http.HttpServletRequest request, HttpServletResponse response) {
        limit(request.getRemoteAddr());
        response.setHeader("Cache-Control", "no-store");
        return login.complete(body.get("state"), body.get("verifier"), body.get("code"));
    }
    @GetMapping("/callback")
    public void callback(@RequestParam(required=false) String code, @RequestParam(required=false) String state,
                         HttpServletResponse response) throws IOException {
        var config = settings.requireEnabled();
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        if (code == null || state == null || code.length()>2048 || state.length()>128) {
            response.sendRedirect(config.get("baseUrl") + "/#/login?feishu_error=1"); return;
        }
        response.sendRedirect(config.get("baseUrl") + "/#/login?feishu_code=" + FeishuLoginService.enc(code)
                + "&feishu_state=" + FeishuLoginService.enc(state));
    }
}
