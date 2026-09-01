package io.dataease.company.bi.auth;

import io.dataease.auth.bo.TokenUserBO;
import io.dataease.exception.DEException;
import io.dataease.utils.AuthUtils;
import io.dataease.utils.LogUtil;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public class CompanyUserStatusFilter implements Filter {

    private static final String HEAD_NAME = "DE-GATEWAY-FLAG";

    private final CompanyUserStatusGuard statusGuard;

    public CompanyUserStatusFilter(CompanyUserStatusGuard statusGuard) {
        this.statusGuard = statusGuard;
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
            throws IOException, ServletException {
        TokenUserBO user = AuthUtils.getUser();
        if (user == null || user.getUserId() == null) {
            filterChain.doFilter(servletRequest, servletResponse);
            return;
        }
        try {
            statusGuard.requireActive(user.getUserId());
        } catch (DEException e) {
            LogUtil.error(e.getMessage(), e);
            HttpServletResponse res = (HttpServletResponse) servletResponse;
            String msg = URLEncoder.encode(e.getMessage(), StandardCharsets.UTF_8).replace("+", "%20");
            HttpHeaders headers = new HttpHeaders();
            headers.add(HEAD_NAME, msg);
            sendResponseEntity(res, new ResponseEntity<>(e.getMessage(), headers, HttpStatus.UNAUTHORIZED));
            return;
        }
        filterChain.doFilter(servletRequest, servletResponse);
    }

    private void sendResponseEntity(HttpServletResponse httpResponse, ResponseEntity<String> responseEntity) throws IOException {
        httpResponse.setStatus(responseEntity.getStatusCode().value());
        for (String name : responseEntity.getHeaders().keySet()) {
            httpResponse.setHeader(name, responseEntity.getHeaders().getFirst(name));
        }
        httpResponse.getWriter().write(Objects.requireNonNull(responseEntity.getBody()));
    }
}
