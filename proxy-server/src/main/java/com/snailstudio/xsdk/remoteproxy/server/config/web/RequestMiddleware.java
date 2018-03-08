package com.snailstudio.xsdk.remoteproxy.server.config.web;

import io.netty.handler.codec.http.FullHttpRequest;

/**
 * 请求拦截器
 */
public interface RequestMiddleware {

    /**
     * 请求预处理
     *
     * @param request
     */
    void preRequest(FullHttpRequest request);
}
