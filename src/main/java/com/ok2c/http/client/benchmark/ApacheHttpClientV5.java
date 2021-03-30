/*
 * Copyright 2019 OK2 Consulting Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ok2c.http.client.benchmark;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.DefaultHttpResponseParserFactory;
import org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.config.CharCodingConfig;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.pool.PoolConcurrencyPolicy;
import org.apache.hc.core5.pool.PoolReusePolicy;
import org.apache.hc.core5.util.Timeout;
import org.apache.hc.core5.util.VersionInfo;

import java.net.URI;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class ApacheHttpClientV5 implements HttpAgent {

    private final PoolingHttpClientConnectionManager mgr;
    private final CloseableHttpClient httpclient;
    private final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(10);

    public ApacheHttpClientV5() {
        super();
        this.mgr = PoolingHttpClientConnectionManagerBuilder.create()
                .setConnectionFactory(new ManagedHttpClientConnectionFactory(
                        Http1Config.custom()
                                .setBufferSize(8 * 1024)
                                .setChunkSizeHint(8 * 1024)
                                .build(),
                        CharCodingConfig.DEFAULT,
                        DefaultHttpResponseParserFactory.INSTANCE))
                .setMaxConnPerRoute(8)
                .setMaxConnTotal(8)
                .setPoolConcurrencyPolicy(PoolConcurrencyPolicy.STRICT)
                .setConnPoolPolicy(PoolReusePolicy.FIFO)
                .build();
        this.httpclient = HttpClientBuilder.create()
                .setConnectionManager(this.mgr)
                .build();
    }

    @Override
    public void init() {
    }

    @Override
    public void shutdown() {
        executorService.shutdownNow();
        this.mgr.close(CloseMode.GRACEFUL);
    }

    @Override
    public Stats execute(final BenchmarkConfig config) throws Exception {
        int concurrency = config.getConcurrency();
        int requests = config.getRequests();
        final Stats stats = new Stats(config.getRequests(), concurrency);
        CyclicBarrier barrier = new CyclicBarrier(concurrency);
        ExecutorService executorService = Executors.newFixedThreadPool(concurrency);
        AtomicBoolean leader = new AtomicBoolean(false);
        AtomicBoolean leakDetected = new AtomicBoolean(false);
        for (int i = 0; i < requests; i++) {
            executorService.submit(() -> {
                if (leakDetected.get()) {
                    return;
                }
                try {
                    barrier.await();
                    if (leader.getAndSet(false)) {
                        if (mgr.getTotalStats().toString().contains("leased: 0;")) {
                            System.out.println("No connection leak detected...");
                        } else {
                            System.out.println("Connection leak detected!");
                            leakDetected.set(true);
                        }
                    }
                    barrier.await();
                    leader.set(true);
                    if (leakDetected.get()) {
                        return;
                    }

                    final URI target = URI.create("http://240.0.0.1:80/");

                    final HttpHost targetHost = new HttpHost(target.getScheme(), target.getHost(), target.getPort());
                    final RequestConfig requestConfig = RequestConfig.custom()
                            .setConnectTimeout(Timeout.ofMilliseconds(50))
                            .setConnectionRequestTimeout(Timeout.ofMilliseconds(50))
                            .build();

                    final HttpUriRequestBase request = new HttpGet(target);
                    final HttpClientContext clientContext = HttpClientContext.create();
                    clientContext.setRequestConfig(requestConfig);

                    try (final CloseableHttpResponse response = httpclient.execute(targetHost, request, clientContext)) {
                        stats.success(0);
                    } catch (Throwable t) {
                        stats.failure(0);
                    }
                } catch (Throwable ignore) {
                }
            });
        }

        executorService.shutdown();
        executorService.awaitTermination(1, TimeUnit.DAYS);

        String poolStats = mgr.getTotalStats().toString();
        if (poolStats.contains("leased: 0;")) {
            System.out.println("No connection leak detected.");
        } else {
            System.out.println("Connection leak detected");
            System.out.println(poolStats);
        }
        return stats;
    }

    @Override
    public String getClientName() {
        final VersionInfo vinfo = VersionInfo.loadVersionInfo(
                "org.apache.hc.client5",
                Thread.currentThread().getContextClassLoader());
        return "Apache HttpClient (ver: " + (vinfo != null ? vinfo.getRelease() : VersionInfo.UNAVAILABLE) + ")";
    }

    public static void main(final String... args) throws Exception {
        BenchmarkRunner.run(new ApacheHttpClientV5(), args);
    }

}