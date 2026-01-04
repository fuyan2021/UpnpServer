package com.eversolo.upnpserver.dlna.dms.component;

import android.os.Build;

import com.eversolo.upnpserver.dlna.dms.ContentNode;
import com.eversolo.upnpserver.dlna.dms.ContentTree;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.stream.ChunkedFile;
import io.netty.handler.stream.ChunkedWriteHandler;

/**
 * NettyHttpServer
 * <p>
 * 基于Netty实现的高性能HTTP服务器，用于DLNA媒体服务器。
 * 主要特性：
 * 1. 基于Netty的异步I/O模型，支持高并发
 * 2. 实现了内存缓存机制，减少磁盘I/O
 * 3. 支持断点续传功能(206 Partial Content)
 * 4. 与ContentTree集成，提供基于内容ID的媒体访问
 * 5. 支持多种MIME类型检测和设置
 */
public class NettyHttpServer {

    // 默认端口
    private static final int DEFAULT_PORT = 8080;
    // 默认根目录
    private static final File DEFAULT_ROOT_DIR = new File("/");
    // 最大缓存大小（100MB）
    private static final long MAX_CACHE_SIZE = 100 * 1024 * 1024;
    // 单个文件最大缓存大小（5MB）
    private static final long MAX_FILE_CACHE_SIZE = 5 * 1024 * 1024;

    private final int port;
    private final File rootDir;
    private final ContentCache contentCache;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private ChannelFuture channelFuture;

    // MIME类型映射
    private static final Map<String, String> MIME_TYPES = new HashMap<>();

    static {
        // 初始化MIME类型映射
        MIME_TYPES.put("css", "text/css");
        MIME_TYPES.put("js", "text/javascript");
        MIME_TYPES.put("htm", "text/html");
        MIME_TYPES.put("html", "text/html");
        MIME_TYPES.put("txt", "text/plain");
        MIME_TYPES.put("asc", "text/plain");
        MIME_TYPES.put("gif", "image/gif");
        MIME_TYPES.put("jpg", "image/jpeg");
        MIME_TYPES.put("jpeg", "image/jpeg");
        MIME_TYPES.put("png", "image/png");
        MIME_TYPES.put("mp3", "audio/mpeg");
        MIME_TYPES.put("m3u", "audio/mpeg-url");
        MIME_TYPES.put("pdf", "application/pdf");
        MIME_TYPES.put("doc", "application/msword");
        MIME_TYPES.put("ogg", "application/x-ogg");
        MIME_TYPES.put("zip", "application/octet-stream");
        MIME_TYPES.put("exe", "application/octet-stream");
        MIME_TYPES.put("class", "application/octet-stream");
    }

    /**
     * 构造函数，使用默认端口和根目录
     */
    public NettyHttpServer() {
        this(DEFAULT_PORT, DEFAULT_ROOT_DIR);
    }

    /**
     * 构造函数
     *
     * @param port 服务器监听端口
     */
    public NettyHttpServer(int port) {
        this(port, DEFAULT_ROOT_DIR);
    }

    /**
     * 构造函数
     *
     * @param port    服务器监听端口
     * @param rootDir 根目录
     */
    public NettyHttpServer(int port, File rootDir) {
        this.port = port;
        this.rootDir = Objects.requireNonNull(rootDir, "根目录不能为空");
        this.contentCache = new ContentCache();
    }

    /**
     * 启动服务器
     */
    public void start() throws InterruptedException {
        System.out.println("启动NettyHttpServer，端口：" + port + "，根目录：" + rootDir.getAbsolutePath());

        // 创建主从线程组
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class) // 使用NIO通道
                    .option(ChannelOption.SO_BACKLOG, 128) // 设置连接队列大小
                    .option(ChannelOption.SO_REUSEADDR, true) // 允许端口复用
                    .childOption(ChannelOption.SO_KEEPALIVE, true) // 保持连接活跃
                    .childOption(ChannelOption.TCP_NODELAY, true) // 禁用Nagle算法
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            // 配置ChannelPipeline
                            ch.pipeline()
                                    .addLast("httpServerCodec", new HttpServerCodec()) // HTTP编解码器
                                    .addLast("httpObjectAggregator", new HttpObjectAggregator(65536)) // HTTP对象聚合器
                                    .addLast("chunkedWriteHandler", new ChunkedWriteHandler()) // 支持大文件传输
                                    .addLast("httpRequestHandler", new HttpRequestHandler(rootDir, contentCache)); // 自定义请求处理器
                        }
                    });

            // 绑定端口并启动服务
            channelFuture = bootstrap.bind(port).sync();
            System.out.println("NettyHttpServer启动成功，监听端口：" + port);

            // 等待服务器关闭
            channelFuture.channel().closeFuture().sync();
        } finally {
            // 优雅关闭线程组
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
            System.out.println("NettyHttpServer已关闭");
        }
    }

    /**
     * 停止服务器
     */
    public void stop() {
        System.out.println("正在关闭NettyHttpServer...");

        if (channelFuture != null) {
            channelFuture.channel().close();
        }

        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }

        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }

        // 清空缓存
        contentCache.clear();
        System.out.println("NettyHttpServer已关闭");
    }

    /**
     * HTTP请求处理器
     */
    private static class HttpRequestHandler extends io.netty.channel.ChannelInboundHandlerAdapter {
        private final File rootDir;
        private final ContentCache contentCache;

        public HttpRequestHandler(File rootDir, ContentCache contentCache) {
            this.rootDir = rootDir;
            this.contentCache = contentCache;
        }

        @Override
        public void channelRead(io.netty.channel.ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof FullHttpRequest) {
                FullHttpRequest request = (FullHttpRequest) msg;

                try {
                    // 处理HTTP请求
                    handleHttpRequest(ctx, request);
                } catch (Exception e) {
                    System.err.println("处理HTTP请求出错：" + e.getMessage());
                    sendErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR);
                } finally {
                    // 释放请求资源
                    request.release();
                }
            }
        }

        @Override
        public void exceptionCaught(io.netty.channel.ChannelHandlerContext ctx, Throwable cause) {
            System.err.println("服务器异常：" + cause.getMessage());
            ctx.close();
        }

        /**
         * 处理HTTP请求
         */
        private void handleHttpRequest(io.netty.channel.ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
            String uri = request.uri();
            HttpMethod method = request.method();

            System.out.println("收到请求：" + method + " " + uri);

            // 只处理GET请求
            if (!HttpMethod.GET.equals(method)) {
                sendErrorResponse(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED);
                return;
            }

            // DLNA特定功能：将URI映射到ContentTree中的实际媒体文件
            String newUri = mapDlnaUri(uri);
            if (newUri != null) {
                uri = newUri;
            }

            // 处理URI，移除查询参数
            if (uri.contains("?")) {
                uri = uri.substring(0, uri.indexOf("?"));
            }

            // 防止目录遍历攻击
            if (uri.startsWith("../") || uri.endsWith("../") || uri.contains("/../")) {
                sendErrorResponse(ctx, HttpResponseStatus.FORBIDDEN);
                return;
            }

            // 构建文件路径
            File file = new File(rootDir, uri);
            if (!file.exists()) {
                sendErrorResponse(ctx, HttpResponseStatus.NOT_FOUND);
                return;
            }

            if (file.isDirectory()) {
                // 目录请求，返回403
                sendErrorResponse(ctx, HttpResponseStatus.FORBIDDEN);
                return;
            }

            // 发送文件响应
            sendFileResponse(ctx, file, request);
        }

        /**
         * DLNA URI映射：将ContentTree节点ID映射到实际文件路径
         */
        private String mapDlnaUri(String uri) {
            // 移除URI开头的斜杠
            String itemId = uri.replaceFirst("/", "");
            if (itemId.isEmpty()) {
                return null;
            }

            try {
                // URL解码
                itemId = URLDecoder.decode(itemId, "UTF-8");

                // 检查ContentTree中是否存在对应的节点
                if (ContentTree.hasNode(itemId)) {
                    ContentNode node = ContentTree.getNode(itemId);
                    // 只有项目类型的节点才能被直接访问和流式传输
                    if (node.isItem()) {
                        return node.getFullPath(); // 获取实际文件路径
                    }
                }
            } catch (Exception e) {
                System.err.println("DLNA URI映射出错：" + e.getMessage());
            }

            return null;
        }

        /**
         * 发送文件响应
         */
        private void sendFileResponse(io.netty.channel.ChannelHandlerContext ctx, File file, FullHttpRequest request) throws Exception {
            // 获取文件大小和最后修改时间
            long fileLength = file.length();
            long lastModified = file.lastModified();

            // 获取MIME类型
            String mimeType = getMimeType(file.getName());

            // 检查Range请求
            String range = request.headers().get(HttpHeaderNames.RANGE);

            // 非Range请求且文件大小小于阈值时使用缓存
            if (range == null && fileLength <= MAX_FILE_CACHE_SIZE) {
                String cacheKey = file.getAbsolutePath();

                // 尝试从缓存获取
                ContentCache.CacheEntry cacheEntry = contentCache.get(cacheKey, lastModified);
                if (cacheEntry != null) {
                    System.out.println("缓存命中：" + cacheKey);
                    // 缓存命中，直接返回
                    sendCachedResponse(ctx, cacheEntry, lastModified);
                    return;
                }
            }

            // 处理Range请求
            if (range != null) {
                handleRangeRequest(ctx, file, range, mimeType, lastModified);
            } else {
                // 完整文件请求
                handleFullRequest(ctx, file, mimeType, lastModified);
            }
        }

        /**
         * 处理完整文件请求
         */
        private void handleFullRequest(ChannelHandlerContext ctx, File file, String mimeType, long lastModified) throws Exception {
            long fileLength = file.length();
            String cacheKey = file.getAbsolutePath();

            // 创建HTTP响应
            DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, mimeType);
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, fileLength);
            response.headers().set(HttpHeaderNames.LAST_MODIFIED, formatDate(lastModified));
            response.headers().set(HttpHeaderNames.ACCEPT_RANGES, "bytes");
            response.headers().set(HttpHeaderNames.SERVER, "NettyHttpServer/1.0");

            // 发送响应头
            ctx.write(response);

            // 使用Netty的零拷贝文件传输
            RandomAccessFile raf = new RandomAccessFile(file, "r");
            ctx.write(new ChunkedFile(raf, 0, fileLength, 8192));

            // 发送结束标记
            ChannelFuture lastContentFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);

            // 缓存小文件
            if (fileLength <= MAX_FILE_CACHE_SIZE) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    cacheFile(file, cacheKey, mimeType, lastModified);
                }
            }

            // 关闭连接
            lastContentFuture.addListener(ChannelFutureListener.CLOSE);
        }

        /**
         * 处理Range请求
         */
        private void handleRangeRequest(io.netty.channel.ChannelHandlerContext ctx, File file, String range, String mimeType, long lastModified) throws Exception {
            long fileLength = file.length();

            // 解析Range头
            long start = 0;
            long end = fileLength - 1;

            if (range.startsWith("bytes=")) {
                range = range.substring(6);
                int minusIndex = range.indexOf("-");
                if (minusIndex > 0) {
                    try {
                        start = Long.parseLong(range.substring(0, minusIndex));
                        if (minusIndex < range.length() - 1) {
                            end = Long.parseLong(range.substring(minusIndex + 1));
                        }
                    } catch (NumberFormatException e) {
                        sendErrorResponse(ctx, HttpResponseStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
                        return;
                    }
                }
            }

            // 验证Range有效性
            if (start < 0 || end >= fileLength || start > end) {
                sendErrorResponse(ctx, HttpResponseStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
                return;
            }

            // 创建HTTP响应
            DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.PARTIAL_CONTENT);
            long contentLength = end - start + 1;

            response.headers().set(HttpHeaderNames.CONTENT_TYPE, mimeType);
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, contentLength);
            response.headers().set(HttpHeaderNames.CONTENT_RANGE, String.format("bytes %d-%d/%d", start, end, fileLength));
            response.headers().set(HttpHeaderNames.LAST_MODIFIED, formatDate(lastModified));
            response.headers().set(HttpHeaderNames.ACCEPT_RANGES, "bytes");
            response.headers().set(HttpHeaderNames.SERVER, "NettyHttpServer/1.0");

            // 发送响应头
            ctx.write(response);

            // 使用Netty的零拷贝文件传输
            RandomAccessFile raf = new RandomAccessFile(file, "r");
            ctx.write(new io.netty.handler.stream.ChunkedFile(raf, start, contentLength, 8192));

            // 发送结束标记
            ChannelFuture lastContentFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
            lastContentFuture.addListener(io.netty.channel.ChannelFutureListener.CLOSE);
        }

        /**
         * 发送缓存响应
         */
        private void sendCachedResponse(io.netty.channel.ChannelHandlerContext ctx, ContentCache.CacheEntry cacheEntry, long lastModified) {
            // 创建HTTP响应
            DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, cacheEntry.mimeType);
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, cacheEntry.content.length);
            response.headers().set(HttpHeaderNames.LAST_MODIFIED, formatDate(lastModified));
            response.headers().set(HttpHeaderNames.ACCEPT_RANGES, "bytes");
            response.headers().set(HttpHeaderNames.SERVER, "NettyHttpServer/1.0");
            response.headers().set("X-Cache", "HIT");

            // 发送响应头
            ctx.write(response);

            // 发送缓存内容
            ctx.write(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                    io.netty.buffer.Unpooled.wrappedBuffer(cacheEntry.content)));

            // 发送结束标记
            ChannelFuture lastContentFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
            lastContentFuture.addListener(io.netty.channel.ChannelFutureListener.CLOSE);
        }

        /**
         * 缓存文件内容
         */
        private void cacheFile(File file, String cacheKey, String mimeType, long lastModified) {
            try {
                // 读取文件内容
                byte[] content = null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Files.readAllBytes(file.toPath());
                    content = Files.readAllBytes(file.toPath());
                    // 添加到缓存
                    contentCache.put(cacheKey, content, lastModified, mimeType);
                    System.out.println("文件已缓存：" + cacheKey + "，大小：" + content.length / 1024 + "KB");
                }
            } catch (IOException e) {
                System.err.println("缓存文件失败：" + cacheKey + "，错误：" + e.getMessage());
            }
        }

        /**
         * 发送错误响应
         */
        private void sendErrorResponse(io.netty.channel.ChannelHandlerContext ctx, HttpResponseStatus status) {
            // 创建错误响应
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, status,
                    io.netty.buffer.Unpooled.copiedBuffer(status.toString(), io.netty.util.CharsetUtil.UTF_8));

            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
            response.headers().set(HttpHeaderNames.SERVER, "NettyHttpServer/1.0");

            // 发送响应并关闭连接
            ctx.writeAndFlush(response).addListener(io.netty.channel.ChannelFutureListener.CLOSE);
        }

        /**
         * 获取文件的MIME类型
         */
        private String getMimeType(String fileName) {
            int dotIndex = fileName.lastIndexOf('.');
            if (dotIndex > 0) {
                String extension = fileName.substring(dotIndex + 1).toLowerCase();
                return MIME_TYPES.getOrDefault(extension, "application/octet-stream");
            }
            return "application/octet-stream";
        }

        /**
         * 格式化日期为HTTP标准格式
         */
        private String formatDate(long time) {
            return new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.US)
                    .format(new java.util.Date(time));
        }
    }

    /**
     * 内容缓存实现
     */
    private static class ContentCache {
        // 缓存条目
        static class CacheEntry {
            final byte[] content;
            final long lastModified;
            final String mimeType;

            CacheEntry(byte[] content, long lastModified, String mimeType) {
                this.content = content;
                this.lastModified = lastModified;
                this.mimeType = mimeType;
            }
        }

        // 使用ConcurrentHashMap实现线程安全的缓存
        private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
        // 缓存大小统计
        private volatile long totalSize = 0;

        /**
         * 获取缓存条目
         */
        CacheEntry get(String key, long fileLastModified) {
            CacheEntry entry = cache.get(key);
            if (entry != null && entry.lastModified == fileLastModified) {
                return entry;
            }
            return null;
        }

        /**
         * 添加缓存条目
         */
        void put(String key, byte[] content, long lastModified, String mimeType) {
            // 检查是否超过单个文件缓存大小限制
            if (content.length > MAX_FILE_CACHE_SIZE) {
                return;
            }

            // 检查是否超过总缓存大小限制
            while (totalSize + content.length > MAX_CACHE_SIZE) {
                // 移除最早添加的条目（简单实现，实际可以使用LRU）
                String oldestKey = cache.keySet().iterator().next();
                CacheEntry oldestEntry = cache.remove(oldestKey);
                if (oldestEntry != null) {
                    totalSize -= oldestEntry.content.length;
                }
            }

            // 添加新条目
            CacheEntry oldEntry = cache.put(key, new CacheEntry(content, lastModified, mimeType));
            if (oldEntry != null) {
                totalSize -= oldEntry.content.length;
            }
            totalSize += content.length;
        }

        /**
         * 移除缓存条目
         */
        void remove(String key) {
            CacheEntry entry = cache.remove(key);
            if (entry != null) {
                totalSize -= entry.content.length;
            }
        }

        /**
         * 清空缓存
         */
        void clear() {
            cache.clear();
            totalSize = 0;
        }

        /**
         * 获取当前缓存大小
         */
        long getTotalSize() {
            return totalSize;
        }

        /**
         * 获取缓存条目数量
         */
        int getEntryCount() {
            return cache.size();
        }
    }

    /**
     * 主方法，用于测试
     */
    public static void main(String[] args) throws InterruptedException {
        NettyHttpServer server = new NettyHttpServer(8080);
        server.start();
    }
}