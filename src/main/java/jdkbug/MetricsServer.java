package jdkbug;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.util.AsciiString;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.DefaultThreadFactory;
import sun.misc.Signal;
import sun.misc.SignalHandler;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;

import static io.netty.handler.codec.http.HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN;
import static io.netty.handler.codec.http.HttpHeaderNames.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

public class MetricsServer {
    public static void main(String[] args) throws IOException, InterruptedException {
        SignalHandler interruptHandler = signal -> {
            System.err.println("signal handler called with " + signal.getName() + ", " + signal.getNumber());
        };

        Signal.handle(new Signal("BUS"), interruptHandler);

        final String hostname = "localhost";// InetAddress.getLocalHost().getHostName();
        final int port = 9999;

        final EventLoopGroup acceptGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("MetricsUI-Acceptor-Threads"));
        final EventLoopGroup serverGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("MetricsUI-Server-Threads"));

        try {
            ServerBootstrap bootstrap = new ServerBootstrap().group(acceptGroup, serverGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 100)
                    .childOption(ChannelOption.SO_KEEPALIVE, false)
                    .childOption(ChannelOption.TCP_NODELAY, false)
                    .childOption(ChannelOption.SO_REUSEADDR, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(SocketChannel channel) throws Exception {
                            channel.pipeline().addLast(new HttpServerCodec());
                            channel.pipeline().addLast("metricsUIHandler", new MetricsUIHandler());
                        }
                    });

            ChannelFuture channelFuture = bootstrap.bind(hostname, port);
            System.out.println(String.format("Started UI Server on IP [%s:%d]", hostname, port));
            channelFuture.sync().channel().closeFuture().sync();
        } finally {
            acceptGroup.shutdownGracefully();
            serverGroup.shutdownGracefully();
        }
    }

    private static class MetricsUIHandler extends ChannelInboundHandlerAdapter {
        private static final CharSequence RESPONSE_COUNT_HEADER = new AsciiString("x-response-count");
        private static final AtomicInteger RESPONSE_COUNT = new AtomicInteger();
        private HttpRequest request;

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) {
            ctx.flush();
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof HttpRequest) {
                request = (HttpRequest) msg;
            } else if (msg instanceof HttpContent) {
                HttpContent content = (HttpContent) msg;
                content.release();

                String contentType = getResponseContentType(request.uri());
                FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK);
                try {
                    response.headers().set(CONTENT_TYPE, contentType);
                    response.headers().set(ACCESS_CONTROL_ALLOW_ORIGIN, "*");
                    response.headers().setInt(RESPONSE_COUNT_HEADER, RESPONSE_COUNT.incrementAndGet());


                    if (request.method().equals(HttpMethod.GET)) {
                        // serve up index.html resource if we get a request with no uri
                        String resourceName = (request.uri().equals("/")) ? "/index.html" : request.uri();

                        InputStream is = MetricsUIHandler.class.getClassLoader().getResourceAsStream("web" + resourceName);
                        if (is == null) {
                            // we don't have a resource for the path -- return 404
                            response.setStatus(NOT_FOUND);
                        } else {
                            try {
                                byte[] buffer = new byte[1024];
                                int length;
                                while ((length = is.read(buffer)) != -1) {
                                    response.content().writeBytes(buffer, 0, length);
                                }
                            } finally {
                                is.close();
                            }
                        }
                    }

                    response.headers().set(CONTENT_LENGTH, response.content().readableBytes());
                    response.headers().set(CONNECTION, HttpHeaderValues.KEEP_ALIVE);
                } catch (Throwable cause) {
                    response.release();
                    cause.printStackTrace();
                    ctx.close();
                }
                ctx.write(response);
            } else {
                ReferenceCountUtil.release(msg);
            }
        }

        private String getResponseContentType(String requestURI) {
            if (requestURI.startsWith("/js")) {
                return "application/javascript";
            } else if (requestURI.startsWith("/fonts")) {
                return "application/octet-stream";
            } else if (requestURI.startsWith("/css")) {
                return "text/css";
            } else if (requestURI.startsWith("/json") || requestURI.startsWith("/instance")) {
                return "application/json; charset=utf-8";
            } else if (requestURI.endsWith(".html") || requestURI.equals("/")) {
                return "text/html; charset=utf-8";
            } else {
                return "application/octet-stream";
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            cause.printStackTrace();
            ctx.close();
        }
    }
}
