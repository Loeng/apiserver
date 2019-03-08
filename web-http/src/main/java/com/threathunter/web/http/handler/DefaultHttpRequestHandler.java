package com.threathunter.web.http.handler;

import com.threathunter.web.http.server.HttpRequest;
import com.threathunter.web.http.server.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * 默认请求处理器
 */
public class DefaultHttpRequestHandler implements HttpRequestHandler {

    @Override
    public void handleRequest(HttpRequest request, HttpResponse response) throws Exception {
        response.setStatus(HttpResponseStatus.NOT_FOUND);
        response.setResult("request not found.");
    }

}
