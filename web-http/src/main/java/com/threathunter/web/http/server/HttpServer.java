package com.threathunter.web.http.server;

import com.threathunter.web.common.utils.SpringUtils;
import com.threathunter.web.http.constant.HttpConstant;
import com.threathunter.web.http.handler.HttpChannelHandler;
import com.threathunter.web.http.route.HttpHandlerMapping;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.cors.CorsConfig;
import io.netty.handler.codec.http.cors.CorsConfigBuilder;
import io.netty.handler.codec.http.cors.CorsHandler;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

public class HttpServer {

    private static final Logger logger = LoggerFactory.getLogger(HttpServer.class);

    private int port = 8080;

    private boolean ssl = false;

    private HttpHandlerMapping handlerMapping;

    public HttpServer(int port) {
        this(port, false);
    }

    public HttpServer(int port, boolean ssl) {
        this.port = port;
        this.ssl = ssl;
        initHandlerMapping();
    }

    /**
     * 初始化handlerMapping
     */
    protected void initHandlerMapping() {
        logger.info("启动端口号:{}", port);
        logger.info("spring配置文件路径:{}", HttpConstant.HTTP_CONFIG_LOCATION);
        logger.info("超时时间:{}秒", HttpConstant.HTTP_TIMEOUT);
        logger.info("最大包大小:{}MB", HttpConstant.HTTP_MAX_CONTENT_LENGTH / 1024 / 1024);
        logger.info("boss线程数:{}，worker线程数:{}", HttpConstant.HTTP_BOSS_THREADS, HttpConstant.HTTP_WORKER_THREADS);
        ApplicationContext applicationContext = new ClassPathXmlApplicationContext(HttpConstant.HTTP_CONFIG_LOCATION);
        SpringUtils.setApplicationContext(applicationContext);
        this.handlerMapping = applicationContext.getBean(HttpHandlerMapping.class);
        if (handlerMapping == null) {
            throw new RuntimeException("handlerMapping is null!");
        }
    }

    public void start(long startTime) {
        EventLoopGroup bossGroup = new NioEventLoopGroup(HttpConstant.HTTP_BOSS_THREADS);
        EventLoopGroup workerGroup = new NioEventLoopGroup(HttpConstant.HTTP_WORKER_THREADS);
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .handler(new LoggingHandler(LogLevel.INFO))
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            ChannelPipeline pipeline = ch.pipeline();
                            if (ssl) {
                                SelfSignedCertificate ssc = new SelfSignedCertificate();
                                SslContext sslContext = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
                                pipeline.addLast(sslContext.newHandler(ch.alloc()));
                            }
                            pipeline.addLast(new IdleStateHandler(0, 0, HttpConstant.HTTP_TIMEOUT));
                            CorsConfig corsConfig = CorsConfigBuilder.forAnyOrigin().allowedRequestMethods(HttpMethod.OPTIONS).allowedRequestHeaders().build();
                            CorsHandler corsHandler = new CorsHandler(corsConfig);
                            pipeline.addLast(corsHandler);
                            // 服务端，对请求解码
                            pipeline.addLast(new HttpRequestDecoder());
                            // 聚合器，把多个消息转换为一个单一的FullHttpRequest或是FullHttpResponse
                            pipeline.addLast(new HttpObjectAggregator(HttpConstant.HTTP_MAX_CONTENT_LENGTH));
                            // 服务端，对响应编码
                            pipeline.addLast(new HttpResponseEncoder());
                            // 块写入处理器
                            pipeline.addLast(new ChunkedWriteHandler());
                            // 压缩处理器
                            pipeline.addLast(new HttpContentCompressor());
                            // 自定义服务端处理器
                            pipeline.addLast(new HttpChannelHandler(handlerMapping));
                        }
                    });
            Channel channel = bootstrap.bind(port).sync().channel();
            logger.info("SwiftHttpServer started on port {}", port);
            logger.info("Server startup in {} ms", (System.currentTimeMillis() - startTime));
            channel.closeFuture().sync();
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

}
