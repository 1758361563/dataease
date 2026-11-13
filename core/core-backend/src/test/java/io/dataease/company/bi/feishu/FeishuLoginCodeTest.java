package io.dataease.company.bi.feishu;

import io.dataease.auth.vo.TokenVO;
import io.dataease.company.bi.auth.CompanyTokenIssuer;
import io.dataease.company.bi.integration.IntegrationConnectionStore;
import io.dataease.company.bi.user.CompanyBiUser;
import io.dataease.company.bi.user.CompanyBiUserService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;


/** Isolated code tests: H2 only, no Feishu calls and no business database. */
public class FeishuLoginCodeTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        testOAuthProtocols();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:feishu_login;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        for (String file : new String[]{"company_feishu_login_attempt", "company_feishu_recipient", "company_feishu_identity_map", "company_feishu_subscription_recipient", "company_feishu_app_config"})
            jdbc.execute(Files.readString(Path.of("core/core-backend/src/main/resources/db/company/"+file+".sql")));
        jdbc.execute("CREATE TABLE company_bi_user(id BIGINT PRIMARY KEY,username VARCHAR(64),display_name VARCHAR(128),status VARCHAR(16))");
        jdbc.execute("CREATE TABLE company_feishu_subscription(id BIGINT PRIMARY KEY,name VARCHAR(128),status VARCHAR(16))");
        jdbc.update("INSERT INTO company_feishu_subscription VALUES(1,'Daily','ENABLED')");
        jdbc.update("INSERT INTO company_bi_user VALUES(10,'a','甲','ENABLED'),(11,'b','乙','ENABLED')");
        var store = new CompanyFeishuJdbcStore(jdbc);
        var app = new CompanyFeishuRepository.AppConfig();
        app.id=1L; app.appId="cli_test"; app.appSecretRef="test"; app.status="ENABLED";
        app.createdAt=app.updatedAt=1L; store.insertAppConfig(app);
        var next = new AtomicLong(100);
        var directory = new CompanyFeishuService(store,null,null,null,null,next::incrementAndGet) {
          @Override public Map<String,Object> searchEmployees(String query) {
              return Map.of("matches",java.util.List.of(Map.of("userId","employee1","name","Employee")));
          }
          @Override public Map<String,Object> confirmEmployee(Map<String,Object> body,long operator) {
            String uid=body.get("userId").toString();
            var r=store.findRecipientByKey("cli_test","user_id",uid);
            if(r==null) {
                r=new CompanyFeishuRepository.Recipient(); r.id=next.incrementAndGet(); r.appId="cli_test";
                r.feishuId=uid; r.displayName=uid; r.recipientType="PERSONAL"; r.receiveIdType="user_id";
                r.identityStatus="CONFIRMED"; r.status="ENABLED"; r.verifyStatus="UNVERIFIED";
                r.createdAt=r.updatedAt=1L; store.insertRecipient(r);
            }
            return Map.of();
          }
        };
        var identities = new CompanyFeishuIdentityService(jdbc,store,directory,next::incrementAndGet);
        identities.bind(10,"employee1",1);
        var binding=identities.loginBinding("cli_test","employee1");
        check(binding.biUserId==10,"binding login owner");
        check(identities.boundRecipients().size()==1,"bound user selectable");
        var matches=(java.util.List<?>) identities.search("Employee").get("matches");
        check("10".equals(((Map<?,?>)matches.get(0)).get("boundUserId")),"search shows occupied BI user");
        identities.bind(10,"employee1",1);
        check(identities.loginBinding("cli_test","employee1").id.equals(binding.id),"same binding idempotent");
        rejects(()->identities.bind(11,"employee1",1),"duplicate binding");
        check(identities.sessionActive(binding.id,10,"cli_test"),"active binding session");
        jdbc.update("INSERT INTO company_feishu_subscription_recipient VALUES(1,1,?,1,1)",binding.recipientId);
        rejects(()->identities.bind(10,"",1),"subscribed recipient protected");
        jdbc.update("UPDATE company_feishu_subscription SET status='DELETED'");
        identities.bind(10,"employee2",1);
        check(!identities.sessionActive(binding.id,10,"cli_test"),"rebind revokes old session");
        rejects(()->identities.loginBinding("cli_test","employee1"),"old identity denied");
        jdbc.update("UPDATE company_feishu_identity_map SET source='MANUAL'");
        rejects(()->identities.loginBinding("cli_test","employee2"),"legacy map cannot log in");
        identities.bind(10,"employee2",1);

        var settings=new TestSettings();
        var oauth=new TestOAuth();
        var user=new CompanyBiUser(); user.setId(10L); user.setStatus("ENABLED");
        var users=new CompanyBiUserService(null) {
            @Override public CompanyBiUser findById(Long id) { return id==10L ? user : null; }
            @Override public void updateLastLogin(Long id,long at) {}
        };
        var expected=new TokenVO("test",0L);
        var issuer=new CompanyTokenIssuer(null) {
            @Override public TokenVO issueFeishu(Long userId,Long bindingId,String appId) { return expected; }
        };
        var service=new FeishuLoginService(settings,oauth,identities,users,issuer,jdbc);
        String verifier=FeishuLoginService.random();
        var attempt=service.start(verifier,"QR"); String state=attempt.get("state").toString();
        check(attempt.get("authorizeUrl").toString().startsWith("https://passport.feishu.cn/suite/passport/oauth/authorize?"),"QR authorization endpoint");
        rejects(()->service.complete(state,FeishuLoginService.random(),"code"),"browser verifier mismatch");
        check(service.complete(state,verifier,"code")==expected,"successful OAuth login");
        rejects(()->service.complete(state,verifier,"code"),"state replay");
        check(oauth.calls==1,"replay never calls Feishu");
        var expired=service.start(verifier,"QR"); jdbc.update("UPDATE company_feishu_login_attempt SET expires_at=0");
        rejects(()->service.complete(expired.get("state").toString(),verifier,"code"),"expired request");
        var cross=service.start(verifier,"CLIENT");
        oauth.tenant="other_tenant";
        rejects(()->service.complete(cross.get("state").toString(),verifier,"code"),"cross tenant denied");
        oauth.tenant="tenant_test";
        user.setStatus("DISABLED"); var disabled=service.start(verifier,"CLIENT");
        rejects(()->service.complete(disabled.get("state").toString(),verifier,"code"),"disabled user denied");
        user.setStatus("ENABLED");
        var changed=service.start(verifier,"QR");
        settings.appId="other_app";
        rejects(()->service.complete(changed.get("state").toString(),verifier,"code"),"app changed during login");
        rejects(()->FeishuLoginSettings.validateBase("http://bi.example.com"),"HTTPS required");
        rejects(()->FeishuLoginSettings.validateBase("https://name:password@bi.example.com"),"userinfo URL rejected");
        rejects(()->FeishuLoginSettings.validateBase("https://bi.example.com/#/login"),"fragment URL rejected");
        FeishuLoginSettings.validateBase("http://localhost:8100");
        identities.bind(10,"",1);
        check(identities.binding(10).isEmpty(),"unbind removes relation");
        check(store.findRecipientByKey("cli_test","user_id","employee2")!=null,"unbind retains recipient history");
        var realIssuer = new CompanyTokenIssuer(new io.dataease.company.bi.auth.CompanyTokenSecret("isolated-test-secret", "unused"));
        var issued = realIssuer.issueFeishu(10L,123L,"cli_test");
        var jwt=com.auth0.jwt.JWT.require(com.auth0.jwt.algorithms.Algorithm.HMAC256("isolated-test-secret")).build().verify(issued.getToken());
        check(jwt.getClaim("fs_binding").asLong()==123L,"signed token binding claim");
        check(jwt.getExpiresAt().getTime()>System.currentTimeMillis(),"signed token expiry");
        System.out.println("Feishu login code tests: " + checks + " checks passed");
    }
    private static class TestSettings extends FeishuLoginSettings {
        String appId="cli_test";
        TestSettings(){super(null);}
        @Override public Map<String,Object> requireEnabled() { return Map.of("enabled",true,"appId",appId,"tenantKey","tenant_test","baseUrl","https://bi.example.com"); }
        @Override public IntegrationConnectionStore.Connection app() { return new IntegrationConnectionStore.Connection(Map.of("appId",appId),Map.of("appSecret","secret")); }
    }
    private static class TestOAuth extends FeishuOAuthClient {
        TestOAuth() { super(null); }
        String tenant="tenant_test"; int calls;
        @Override public Identity exchange(String app,String secret,String code,String redirect,String mode) { calls++; return new Identity("employee2",tenant); }
    }
    private static void check(boolean value,String label) { if(!value)throw new AssertionError(label); checks++; }
    private static void rejects(Runnable action,String label) {
        try {action.run();} catch(io.dataease.exception.DEException ex){checks++;return;}
        throw new AssertionError(label);
    }
    private static void testOAuthProtocols() {
        var http = new ProtocolHttp();
        var client = new FeishuOAuthClient(http);
        http.responses.add("{\"access_token\":\"test-token\"}");
        http.responses.add("{\"user_id\":\"employee\",\"tenant_key\":\"tenant\"}");
        check(client.exchange("app", "secret", "code", "https://bi.example/callback", "QR").userId().equals("employee"), "QR flat user info");
        check(http.requests.get(0).uri().toString().equals("https://passport.feishu.cn/suite/passport/oauth/token"), "QR matching token endpoint");
        check(http.requests.get(0).headers().firstValue("Content-Type").orElse("").equals("application/x-www-form-urlencoded"), "QR form encoding");
        check(http.requests.get(1).uri().getPath().equals("/suite/passport/oauth/userinfo"), "QR matching user info endpoint");
        http.responses.add("{\"access_token\":\"test-token\"}");
        http.responses.add("{\"code\":0,\"data\":{\"user_id\":\"employee\",\"tenant_key\":\"tenant\"}}");
        check(client.exchange("app", "secret", "code", "https://bi.example/callback", "CLIENT").tenantKey().equals("tenant"), "client nested user info");
        check(http.requests.get(2).uri().getPath().equals("/open-apis/authen/v2/oauth/token"), "client token endpoint retained");
        check(http.requests.get(2).headers().firstValue("Content-Type").orElse("").equals("application/json"), "client JSON encoding retained");
    }
    private static class ProtocolHttp extends java.net.http.HttpClient {
        final java.util.List<java.net.http.HttpRequest> requests = new java.util.ArrayList<>();
        final java.util.Queue<String> responses = new java.util.ArrayDeque<>();
        public java.util.Optional<java.net.CookieHandler> cookieHandler() { return java.util.Optional.empty(); }
        public java.util.Optional<java.time.Duration> connectTimeout() { return java.util.Optional.empty(); }
        public Redirect followRedirects() { return Redirect.NEVER; }
        public java.util.Optional<java.net.ProxySelector> proxy() { return java.util.Optional.empty(); }
        public javax.net.ssl.SSLContext sslContext() { return null; }
        public javax.net.ssl.SSLParameters sslParameters() { return new javax.net.ssl.SSLParameters(); }
        public java.util.Optional<java.net.Authenticator> authenticator() { return java.util.Optional.empty(); }
        public Version version() { return Version.HTTP_1_1; }
        public java.util.Optional<java.util.concurrent.Executor> executor() { return java.util.Optional.empty(); }
        @SuppressWarnings("unchecked")
        public <T> java.net.http.HttpResponse<T> send(java.net.http.HttpRequest request, java.net.http.HttpResponse.BodyHandler<T> handler) {
            requests.add(request);
            String body = responses.remove();
            return new java.net.http.HttpResponse<T>() {
                public int statusCode() { return 200; }
                public java.net.http.HttpRequest request() { return request; }
                public java.util.Optional<java.net.http.HttpResponse<T>> previousResponse() { return java.util.Optional.empty(); }
                public java.net.http.HttpHeaders headers() { return java.net.http.HttpHeaders.of(Map.of(), (a,b) -> true); }
                public T body() { return (T) body; }
                public java.util.Optional<javax.net.ssl.SSLSession> sslSession() { return java.util.Optional.empty(); }
                public java.net.URI uri() { return request.uri(); }
                public Version version() { return Version.HTTP_1_1; }
            };
        }
        public <T> java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(java.net.http.HttpRequest r, java.net.http.HttpResponse.BodyHandler<T> h) { throw new UnsupportedOperationException(); }
        public <T> java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(java.net.http.HttpRequest r, java.net.http.HttpResponse.BodyHandler<T> h, java.net.http.HttpResponse.PushPromiseHandler<T> p) { throw new UnsupportedOperationException(); }
    }

}
