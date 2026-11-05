package com.ecnu.agentpapermate.config.paper;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.springframework.ai.autoconfigure.vectorstore.elasticsearch.ElasticsearchVectorStoreProperties;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * 配置论文索引使用的 Elasticsearch 连接和向量存储属性。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ElasticsearchProperties.class, ElasticsearchVectorStoreProperties.class})
public class PaperElasticsearchConfiguration {

    /**
     * 创建论文索引专用的 REST 客户端，应用关闭时由 Spring 释放连接。
     */
    @Bean(destroyMethod = "close")
    public RestClient paperElasticsearchClient(PaperIngestionProperties ingestionProperties,
                                               ElasticsearchProperties elasticsearch) {
        // 1、读取 Elasticsearch 节点地址
        HttpHost[] hosts = elasticsearch.getUris().stream().map(HttpHost::create).toArray(HttpHost[]::new);

        // 2、配置了用户名时，为请求设置认证凭据
        var credentials = new BasicCredentialsProvider();
        if (StringUtils.hasText(elasticsearch.getUsername())) {
            credentials.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(
                    elasticsearch.getUsername(), elasticsearch.getPassword()));
        }

        // 3、设置建立连接、等待连接池和读取响应的超时，创建客户端
        int connectTimeoutMillis = ingestionProperties.getConnectTimeoutSeconds() * 1000;
        int readTimeoutMillis = ingestionProperties.getRequestTimeoutSeconds() * 1000;
        return RestClient.builder(hosts)
                .setRequestConfigCallback(c -> c.setConnectTimeout(connectTimeoutMillis)
                        .setConnectionRequestTimeout(connectTimeoutMillis).setSocketTimeout(readTimeoutMillis))
                .setHttpClientConfigCallback(c -> c.setDefaultCredentialsProvider(credentials)).build();
    }
}
