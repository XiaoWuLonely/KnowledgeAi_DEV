package com.knowflow.backend.config;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 统一构建带超时的 AI 模型 RestClient。
 *
 * <p>关键点：socket timeout 是“每次 socket read 的空闲超时”，而不是整次请求的总时长，
 * 因此流式（SSE）回答只要持续有 delta 到达就不会被误杀；connect/response timeout 保证
 * 建连和等待首个响应字节也不会无限阻塞。所有调用方（Chat、Embedding、Settings 的测试/拉取模型）
 * 必须通过这里构建客户端，避免 timeoutSeconds 只保存不生效的问题再次出现。</p>
 */
@Component
public class ModelRestClientFactory {

    /**
     * @param baseUrl        OpenAI-compatible 根地址（调用方会继续追加 /chat/completions 等路径）
     * @param apiKey         已解密的 API Key，仅进入 Authorization header，不落日志、不返回前端
     * @param timeoutSeconds 用户级或环境级超时秒数；小于等于 0 时回退到 60 秒
     * @return 带 connect / response / socket 超时的 RestClient
     */
    public RestClient create(String baseUrl, String apiKey, int timeoutSeconds) {
        int effectiveSeconds = timeoutSeconds > 0 ? timeoutSeconds : 60;
        Timeout timeout = Timeout.ofSeconds(effectiveSeconds);

        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setSocketTimeout(timeout)
                .build();

        PoolingHttpClientConnectionManager connectionManager =
                PoolingHttpClientConnectionManagerBuilder.create()
                        .setDefaultConnectionConfig(connectionConfig)
                        .build();

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(timeout)
                .setConnectionRequestTimeout(timeout)
                .setResponseTimeout(timeout)
                .build();

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .build();

        return RestClient.builder()
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();
    }
}
