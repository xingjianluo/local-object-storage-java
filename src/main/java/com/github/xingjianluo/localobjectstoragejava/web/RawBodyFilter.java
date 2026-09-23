package com.github.xingjianluo.localobjectstoragejava.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 分片上传请求的原始字节保护过滤器。
 *
 * <p>问题：客户端若不指定 Content-Type，curl/浏览器会默认使用
 * {@code application/x-www-form-urlencoded}，此时 Tomcat/Spring 为解析表单参数
 * 会提前消费请求体，业务层再读 InputStream 只能得到 0 字节。</p>
 *
 * <p>方案：对匹配分片上传路径的请求缓存原始字节并把 Content-Type 强制为
 * 不透明二进制类型，使容器不触发表单解析；getInputStream 每次返回一份全新的、
 * 可完整读取的流。过滤器本身只缓存一次，避免对请求体的重复缓冲。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RawBodyFilter extends OncePerRequestFilter {

    private static boolean isPartUpload(HttpServletRequest request) {
        String method = request.getMethod();
        String uri = request.getRequestURI();
        return "PUT".equalsIgnoreCase(method) && uri != null && uri.contains("/parts/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!isPartUpload(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        byte[] body = request.getInputStream().readAllBytes();
        filterChain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public String getContentType() {
            return "application/octet-stream";
        }

        @Override
        public String getCharacterEncoding() {
            // 二进制内容没有字符编码概念，返回 UTF-8 仅为容器解析需要，不影响字节读取。
            return StandardCharsets.UTF_8.name();
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream source = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return source.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int read() {
                    return source.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            Charset cs = StandardCharsets.UTF_8;
            return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), cs));
        }
    }
}
