package com.threathunter.web.http.handler;


import com.threathunter.web.http.route.HandlerExecutionChain;
import com.threathunter.web.http.route.HandlerMethod;
import com.threathunter.web.http.constant.HttpMediaType;
import com.threathunter.web.http.route.HttpHandlerMapping;
import com.threathunter.web.http.server.HttpRequest;
import com.threathunter.web.http.server.HttpResponse;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.CharsetUtil;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.Map;

public class HttpChannelHandler extends SimpleChannelInboundHandler<HttpObject> {

    private static final Logger logger = LoggerFactory.getLogger(HttpChannelHandler.class);

    private final HttpHandlerMapping handlerMapping;

    private com.threathunter.web.http.server.HttpRequest httpRequest;

    private HttpResponse httpResponse;

    public HttpChannelHandler(HttpHandlerMapping handlerMapping) {
        if (handlerMapping == null) {
            throw new IllegalArgumentException("handlerMapping can't be null.");
        }
        this.handlerMapping = handlerMapping;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) throws Exception {
        try {
            if (!(msg instanceof FullHttpRequest)) {
                return;
            }
            FullHttpRequest fullHttpRequest = (FullHttpRequest) msg;
            if (HttpUtil.is100ContinueExpected(fullHttpRequest)) {
                ctx.write(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE));
                return;
            }
            logger.info("channelRead id={}, uri={}", ctx.channel().id(), fullHttpRequest.uri());
            if ("/favicon.ico".equals(fullHttpRequest.uri())) {
                FullHttpResponse fullHttpResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND);
                ctx.writeAndFlush(fullHttpResponse).addListener(ChannelFutureListener.CLOSE);
                return;
            }


            // 初始化HttpRequest
            httpRequest = new HttpRequest(fullHttpRequest);
            httpRequest.setIp(getClientIp(ctx, fullHttpRequest));
            if (fullHttpRequest.method() == HttpMethod.OPTIONS) {
                httpResponse = new HttpResponse(httpRequest.getRequestId());
                httpResponse.setResponseContentTypeJson();
                httpResponse.setResult("{\"status\":201,\"msg\":\"ok\"}");
                handleHttpResponse(ctx);
            } else {
                // 日志上下文中加入requestId
                MDC.put("requestId", httpRequest.getRequestId());
                // 查找处理类方法
                HandlerExecutionChain mappedHandler = handlerMapping.getHandler(httpRequest);
                if (mappedHandler == null) {
                    logger.warn("handler not find.");
                    FullHttpResponse fullHttpResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND);
                    ctx.writeAndFlush(fullHttpResponse).addListener(ChannelFutureListener.CLOSE);
                    return;
                }
                // 初始化HttpResponse
                httpResponse = new HttpResponse(httpRequest.getRequestId());
                // 拦截器处理前
                if (!mappedHandler.applyPreHandle(httpRequest, httpResponse)) {
                    handleHttpResponse(ctx);
                    return;
                }
                // 执行方法
                Object handler = mappedHandler.getHandler();
                if (handler instanceof HttpRequestHandler) {
                    HttpRequestHandler requestHandler = (HttpRequestHandler) handler;
                    requestHandler.handleRequest(httpRequest, httpResponse);
                } else if (handler instanceof HandlerMethod) {
                    HandlerMethod handlerMethod = (HandlerMethod) handler;
                    Method method = handlerMethod.getMethod();
                    Object[] args = new Object[]{httpRequest, httpResponse};
                    ReflectionUtils.makeAccessible(method);
                    method.invoke(handlerMethod.getBean(), args);
                } else {
                    logger.warn("not support handler : {}", handler);
                }
                // 拦截器处理后
                mappedHandler.applyPostHandle(httpRequest, httpResponse);
                // 返回处理结果
                handleHttpResponse(ctx);
            }
        } catch (Exception exception) {
            // 处理异常
            handlerMapping.exceptionHandler(httpRequest, httpResponse, exception);
            // 返回处理结果
            handleHttpResponse(ctx);
        } finally {
            reset();
        }
    }

    /**
     * 获取IP
     *
     * @param ctx
     * @param request
     * @return
     */
    protected String getClientIp(ChannelHandlerContext ctx, FullHttpRequest request) {
        String clientIP = request.headers().get("X-Forwarded-For");
        if (StringUtils.isBlank(clientIP)) {
            clientIP = request.headers().get("X-Real-IP");
        }
        if (StringUtils.isBlank(clientIP)) {
            InetSocketAddress socketAddress = (InetSocketAddress) ctx.channel().remoteAddress();
            clientIP = socketAddress.getAddress().getHostAddress();
        }
        if (StringUtils.isNotBlank(clientIP) && StringUtils.contains(clientIP, ",")) {
            clientIP = StringUtils.split(clientIP, ",")[0];
        }
        return clientIP;
    }

    /**
     * 处理返回结果
     *
     * @param ctx
     */
    protected void handleHttpResponse(ChannelHandlerContext ctx) {
        // 返回值处理
        FullHttpResponse response = null;
        int contentLength = 0;
        if (StringUtils.isEmpty(httpResponse.getResult())) {
            response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, httpResponse.getStatus());
        } else {
            ByteBuf buf = Unpooled.copiedBuffer(httpResponse.getResult(), CharsetUtil.UTF_8);
            response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, httpResponse.getStatus(), buf);
            contentLength = buf.readableBytes();
        }
        // 输出流
        if (httpResponse.getOutputStream() != null) {
            // 使用copiedBuffer会导致excel等文档打不开
            ByteBuf buf = Unpooled.wrappedBuffer(httpResponse.getOutputStream().toByteArray());
            response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, httpResponse.getStatus(), buf);
            contentLength = buf.readableBytes();
        }
        // 处理返回头信息
        for (Map.Entry<String, String> resHeader : httpResponse.getHeaderMap().entrySet()) {
            response.headers().add(resHeader.getKey(), resHeader.getValue());
        }
        // 处理cookie
        if (CollectionUtils.isNotEmpty(httpResponse.getCookieSet())) {
            for (Cookie cookie : httpResponse.getCookieSet()) {
                response.headers().add(HttpHeaderNames.SET_COOKIE, ServerCookieEncoder.STRICT.encode(cookie));
            }
        }
        // 输出返回结果
        /*
        boolean keepAlive = HttpUtil.isKeepAlive(httpRequest.getFullHttpRequest());
        if (keepAlive) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            ctx.writeAndFlush(response);
        } else {
            HttpUtil.setContentLength(response, contentLength);
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
        */
        // 不处理keepAlive，直接返回结果
        HttpUtil.setContentLength(response, contentLength);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * 重置变量
     */
    protected void reset() {
        // 清空swiftRequest
        if (httpRequest != null) {
            httpRequest.destroy();
            httpRequest = null;
        }
        if (httpResponse != null) {
            IOUtils.closeQuietly(httpResponse.getOutputStream());
            httpResponse = null;
        }
        MDC.remove("requestId");
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (httpRequest != null) {
            httpRequest.cleanFiles();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error(cause.getMessage(), cause);
        if (ctx.channel().isActive()) {
            ByteBuf buf = Unpooled.copiedBuffer("Failure: " + cause.getMessage() + "\r\n", CharsetUtil.UTF_8);
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.INTERNAL_SERVER_ERROR, buf);
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpMediaType.TEXT_PLAIN_UTF_8);
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        super.userEventTriggered(ctx, evt);
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent event = (IdleStateEvent) evt;
            if (ctx.channel().isActive() && IdleState.ALL_IDLE.equals(event.state())) {
                logger.info("close timeout channel {}", ctx.channel().id());
                ctx.channel().close();
            }
        }
    }

}

