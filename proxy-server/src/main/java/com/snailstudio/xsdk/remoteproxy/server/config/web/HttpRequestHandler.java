package com.snailstudio.xsdk.remoteproxy.server.config.web;

import com.snailstudio.xsdk.remoteproxy.server.config.ProxyConfig;
import com.snailstudio.xsdk.remoteproxy.server.config.web.routes.RouteConfig;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.HttpHeaders.Names;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedNioFile;
import com.snailstudio.xsdk.remoteproxy.common.JsonUtil;

import java.io.*;
import java.net.URI;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.TimeZone;

public class HttpRequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final String PAGE_FOLDER = System.getProperty("app.home", System.getProperty("user.dir"))
            + "/webpages";

    private static final String SERVER_VS = "LPS-0.1";

//    private static final ExecutorService mExecutorService = Executors
//            .newFixedThreadPool(3);
    private static final long MAX_REPORT_SIZE = 100 * 1024;

    private static String mReport1 = "";
    private static String mReport2 = "";

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {

        // GET返回页面；POST请求接口
        if (request.getMethod() != HttpMethod.POST) {
            if (!handleQuery(ctx, request) && !getReport(ctx, request))
                outputPages(ctx, request);
            return;
        }

        ResponseInfo responseInfo = ApiRoute.run(request);

        // 错误码规则：除100取整为http状态码
        outputContent(ctx, request, responseInfo.getCode() / 100, JsonUtil.object2json(responseInfo),
                "Application/json;charset=utf-8");
    }

    private boolean getReport(ChannelHandlerContext ctx, FullHttpRequest request) {
        String uri = request.getUri();
        if (uri != null && uri.startsWith("/report")) {
            if(!RouteConfig.isAuthenticated(request)){
                outputContent(ctx, request, ResponseInfo.CODE_UNAUTHORIZED / 100, "401 Unauthorized", "Application/json;charset=utf-8");
                return true;
            }
            String content = readReport();
//            File file = new File(ProxyConfig.REPORT_FILE + "-1");
//            if(file.exists())
//                content += readReport(file);
//            file = new File(ProxyConfig.REPORT_FILE + "-2");
//            if(file.exists())
//                content += readReport(file);
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                    Unpooled.wrappedBuffer(content.getBytes(Charset.forName("UTF-8"))));
            response.headers().set(Names.CONTENT_TYPE, "text/plain");
            response.headers().set(Names.CONTENT_LENGTH, response.content().readableBytes());
            response.headers().set(Names.SERVER, SERVER_VS);
            ChannelFuture future = ctx.writeAndFlush(response);
            if (!HttpHeaders.isKeepAlive(request)) {
                future.addListener(ChannelFutureListener.CLOSE);
            }
            return true;
        }
        return false;
    }

    private static String readReport() {
        return mReport1 + mReport2;
    }

    private static String readReport(File file) {
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            byte[] buf = new byte[1024];
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int readIndex;
            while ((readIndex = in.read(buf)) != -1) {
                out.write(buf, 0, readIndex);
            }


            return new String(out.toByteArray(), Charset.forName("UTF-8"));
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return "";
    }

    private boolean handleQuery(ChannelHandlerContext ctx, FullHttpRequest request) {
        String uri = request.getUri();
        if (uri != null && uri.startsWith("/querykey")) {
            int index = uri.indexOf("key=");
            if (index != -1) {
                String key = uri.substring(index + "key=".length(), uri.length());

                boolean exist = false;
                List<ProxyConfig.Client> clients = ProxyConfig.getInstance().getClients();
                for (ProxyConfig.Client client : clients) {
                    if(client.getClientKey().equalsIgnoreCase(key)){
                        exist = true;
                        break;
                    }
                }

                if(!exist) {

                    final String message = getFormatTime() + " " + key + "\r\n";
                    writeReport(message);
//                    mExecutorService.execute(new Runnable() {
//                        @Override
//                        public void run() {
//                            writeReport(message);
//                        }
//                    });
                }
                String result = exist? "1" : "0";//"hello:" + exist;
                FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                        Unpooled.wrappedBuffer(result.getBytes(Charset.forName("UTF-8"))));
                response.headers().set(Names.CONTENT_TYPE, "text/plain");
                response.headers().set(Names.CONTENT_LENGTH, response.content().readableBytes());
                response.headers().set(Names.SERVER, SERVER_VS);
                ChannelFuture future = ctx.writeAndFlush(response);
                if (!HttpHeaders.isKeepAlive(request)) {
                    future.addListener(ChannelFutureListener.CLOSE);
                }
                return true;
            }
        }
        return false;
    }

    public static String getFormatTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        sdf.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return sdf.format(System.currentTimeMillis());
    }

    private static boolean writeReport(String info) {
        if(mReport1.length() < MAX_REPORT_SIZE){
            mReport1 += info;
        }
        else{
            if(mReport2.length() < MAX_REPORT_SIZE){
                mReport2 += info;
            }
            else{
                mReport1 = mReport2;
                mReport2 = info;
            }
        }
        return true;
    }

    private static boolean writeReport0(String info) {
        File file = new File(ProxyConfig.REPORT_FILE + "-1");
        if(file.exists() && file.length() > MAX_REPORT_SIZE){
            File file_2 = new File(ProxyConfig.REPORT_FILE + "-2");
            if(file_2.exists() && file_2.length() > MAX_REPORT_SIZE){
                file.delete();
                file_2.renameTo(file);
                file = new File(ProxyConfig.REPORT_FILE + "-2");
            }
            else{
                file = file_2;
            }
        }
        FileOutputStream outputStream;
        try {
            outputStream = new FileOutputStream(file, true);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            return false;
        }
        byte[] b = info.getBytes();
        try {
            outputStream.write(b, 0, b.length);
            outputStream.flush();
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        } finally {
            try {
                outputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return true;
    }

    private void outputContent(ChannelHandlerContext ctx, FullHttpRequest request, int code, String content,
                               String mimeType) {

        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(code),
                Unpooled.wrappedBuffer(content.getBytes(Charset.forName("UTF-8"))));
        response.headers().set(Names.CONTENT_TYPE, mimeType);
        response.headers().set(Names.CONTENT_LENGTH, response.content().readableBytes());
        response.headers().set(Names.SERVER, SERVER_VS);
        ChannelFuture future = ctx.writeAndFlush(response);
        if (!HttpHeaders.isKeepAlive(request)) {
            future.addListener(ChannelFutureListener.CLOSE);
        }

    }

    /**
     * 输出静态资源数据
     *
     * @param ctx
     * @param request
     * @throws Exception
     */
    private void outputPages(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        HttpResponseStatus status = HttpResponseStatus.OK;
        URI uri = new URI(request.getUri());
        String uriPath = uri.getPath();
        uriPath = uriPath.equals("/") ? "/index.html" : uriPath;
        String path = PAGE_FOLDER + uriPath;
        File rfile = new File(path);
        if (rfile.isDirectory()) {
            path = path + "/index.html";
            rfile = new File(path);
        }

        if (!rfile.exists()) {
            status = HttpResponseStatus.NOT_FOUND;
            outputContent(ctx, request, status.code(), status.toString(), "text/html");
            return;
        }

        if (HttpHeaders.is100ContinueExpected(request)) {
            send100Continue(ctx);
        }

        String mimeType = MimeType.getMimeType(MimeType.parseSuffix(path));
        long length = 0;
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(rfile, "r");
            length = raf.length();
        } finally {
            if (length < 0 && raf != null) {
                raf.close();
            }
        }

        HttpResponse response = new DefaultHttpResponse(request.getProtocolVersion(), status);
        response.headers().set(Names.CONTENT_TYPE, mimeType);
        boolean keepAlive = HttpHeaders.isKeepAlive(request);
        if (keepAlive) {
            response.headers().set(Names.CONTENT_LENGTH, length);
            response.headers().set(Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
        }

        response.headers().set(Names.SERVER, SERVER_VS);
        ctx.write(response);

        if (ctx.pipeline().get(SslHandler.class) == null) {
            ctx.write(new DefaultFileRegion(raf.getChannel(), 0, length));
        } else {
            ctx.write(new ChunkedNioFile(raf.getChannel()));
        }

        ChannelFuture future = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
        if (!keepAlive) {
            future.addListener(ChannelFutureListener.CLOSE);
        }
    }

    private static void send100Continue(ChannelHandlerContext ctx) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE);
        ctx.writeAndFlush(response);
    }

}
