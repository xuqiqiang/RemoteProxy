package com.snailstudio.xsdk.remoteproxy.server.config.web.routes;

import com.google.gson.reflect.TypeToken;
import com.snailstudio.xsdk.remoteproxy.server.ProxyChannelManager;
import com.snailstudio.xsdk.remoteproxy.server.config.ProxyConfig;
import com.snailstudio.xsdk.remoteproxy.server.config.web.ApiRoute;
import com.snailstudio.xsdk.remoteproxy.server.config.web.RequestHandler;
import com.snailstudio.xsdk.remoteproxy.server.config.web.RequestMiddleware;
import com.snailstudio.xsdk.remoteproxy.server.config.web.ResponseInfo;
import com.snailstudio.xsdk.remoteproxy.server.config.web.exception.ContextException;
import com.snailstudio.xsdk.remoteproxy.server.metrics.MetricsCollector;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import com.snailstudio.xsdk.remoteproxy.common.JsonUtil;
import com.snailstudio.xsdk.remoteproxy.server.config.ProxyConfig.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 接口实现
 */
public class RouteConfig {

    protected static final String AUTH_COOKIE_KEY = "token";

    private static Logger logger = LoggerFactory.getLogger(RouteConfig.class);

    /**
     * 管理员不能同时在多个地方登录
     */
    private static String token;

    public static boolean isAuthenticated(FullHttpRequest request) {
        String cookieHeader = request.headers().get(HttpHeaders.Names.COOKIE);
        boolean authenticated = false;
        if (cookieHeader != null) {
            String[] cookies = cookieHeader.split(";");
            for (String cookie : cookies) {
                String[] cookieArr = cookie.split("=");
                if (AUTH_COOKIE_KEY.equals(cookieArr[0].trim())) {
                    if (cookieArr.length == 2 && cookieArr[1].equals(token)) {
                        authenticated = true;
                    }
                }
            }
        }

        String auth = request.headers().get(HttpHeaders.Names.AUTHORIZATION);
        if (!authenticated && auth != null) {
            String[] authArr = auth.split(" ");
            if (authArr.length == 2 && authArr[0].equals(ProxyConfig.getInstance().getConfigAdminUsername()) && authArr[1].equals(ProxyConfig.getInstance().getConfigAdminPassword())) {
                authenticated = true;
            }
        }
        return authenticated;
    }

    public static void init() {

        ApiRoute.addMiddleware(new RequestMiddleware() {

            @Override
            public void preRequest(FullHttpRequest request) {

                if (!request.getUri().equals("/login") && !isAuthenticated(request)) {
                    throw new ContextException(ResponseInfo.CODE_UNAUTHORIZED);
                }

                logger.info("handle request for api {}", request.getUri());
            }
        });

        // 获取配置详细信息
        ApiRoute.addRoute("/config/detail", new RequestHandler() {

            @Override
            public ResponseInfo request(FullHttpRequest request) {
                List<Client> clients = ProxyConfig.getInstance().getClients();
                for (Client client : clients) {
                    Channel channel = ProxyChannelManager.getCmdChannel(client.getClientKey());
                    if (channel != null) {
                        client.setStatus(1);// online
                    } else {
                        client.setStatus(0);// offline
                    }
                }
                return ResponseInfo.build(ProxyConfig.getInstance().getClients());
            }
        });

        // 更新配置
        ApiRoute.addRoute("/config/update", new RequestHandler() {

            @Override
            public ResponseInfo request(FullHttpRequest request) {
                byte[] buf = new byte[request.content().readableBytes()];
                request.content().readBytes(buf);
                String config = new String(buf, Charset.forName("UTF-8"));
                List<Client> clients = JsonUtil.json2object(config, new TypeToken<List<Client>>() {
                });
                if (clients == null) {
                    return ResponseInfo.build(ResponseInfo.CODE_INVILID_PARAMS, "Error json config");
                }

                try {
                    ProxyConfig.getInstance().update(config);
                } catch (Exception ex) {
                    logger.error("config update error", ex);
                    return ResponseInfo.build(ResponseInfo.CODE_INVILID_PARAMS, ex.getMessage());
                }

                return ResponseInfo.build(ResponseInfo.CODE_OK, "success");
            }
        });

        ApiRoute.addRoute("/login", new RequestHandler() {

            @Override
            public ResponseInfo request(FullHttpRequest request) {
                byte[] buf = new byte[request.content().readableBytes()];
                request.content().readBytes(buf);
                String config = new String(buf);
                Map<String, String> loginParams = JsonUtil.json2object(config, new TypeToken<Map<String, String>>() {
                });
                if (loginParams == null) {
                    return ResponseInfo.build(ResponseInfo.CODE_INVILID_PARAMS, "Error login info");
                }

                String username = loginParams.get("username");
                String password = loginParams.get("password");
                if (username == null || password == null) {
                    return ResponseInfo.build(ResponseInfo.CODE_INVILID_PARAMS, "Error username or password");
                }

                if (username.equals(ProxyConfig.getInstance().getConfigAdminUsername()) && password.equals(ProxyConfig.getInstance().getConfigAdminPassword())) {
                    token = UUID.randomUUID().toString().replace("-", "");
                    return ResponseInfo.build(token);
                }

                return ResponseInfo.build(ResponseInfo.CODE_INVILID_PARAMS, "Error username or password");
            }
        });

        ApiRoute.addRoute("/logout", new RequestHandler() {

            @Override
            public ResponseInfo request(FullHttpRequest request) {
                token = null;
                return ResponseInfo.build(ResponseInfo.CODE_OK, "success");
            }
        });

        ApiRoute.addRoute("/metrics/get", new RequestHandler() {

            @Override
            public ResponseInfo request(FullHttpRequest request) {
                return ResponseInfo.build(MetricsCollector.getAllMetrics());
            }
        });

        ApiRoute.addRoute("/metrics/getandreset", new RequestHandler() {

            @Override
            public ResponseInfo request(FullHttpRequest request) {
                return ResponseInfo.build(MetricsCollector.getAndResetAllMetrics());
            }
        });
    }

}
