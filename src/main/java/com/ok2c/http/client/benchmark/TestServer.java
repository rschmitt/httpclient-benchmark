package com.ok2c.http.client.benchmark;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.spi.HttpServerProvider;
import org.apache.hc.client5.http.HttpRequestRetryStrategy;
import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManager;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.client5.http.nio.AsyncClientConnectionManager;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.TrustSelfSignedStrategy;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.Method;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.http2.HttpVersionPolicy;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.pool.PoolConcurrencyPolicy;
import org.apache.hc.core5.pool.PoolReusePolicy;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.ssl.SSLContexts;
import org.apache.hc.core5.util.TimeValue;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static java.lang.Math.max;
import static java.lang.Math.min;
import static org.apache.hc.client5.http.async.methods.SimpleHttpRequest.create;
import static org.apache.hc.core5.reactor.IOReactorStatus.ACTIVE;
import static org.apache.hc.core5.reactor.IOReactorStatus.INACTIVE;
import static org.apache.hc.core5.util.TimeValue.ofSeconds;

public class TestServer {
    private static CloseableHttpAsyncClient client;

    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private HttpsServer tls12Server;
    private HttpsServer tls10Server;

    public TestServer() {
    }

    public void start() {
        HttpServerProvider provider = HttpServerProvider.provider();
        int backlog = Integer.MAX_VALUE;
        try {
            this.tls12Server = provider.createHttpsServer(new InetSocketAddress(8012), backlog);
            this.tls12Server.setHttpsConfigurator(new HttpsConfigurator(SSLTestContexts.createServerSSLContext()));

            this.tls10Server = provider.createHttpsServer(new InetSocketAddress(8010), backlog);
            this.tls10Server.setHttpsConfigurator(new HttpsConfigurator(SSLTestContexts.createServerSSLContext()) {
                @Override
                public void configure(HttpsParameters params) {
                    params.setProtocols(new String[]{ "TLSv1" });
                    params.setCipherSuites(new String[]{ "TLS_RSA_WITH_AES_256_CBC_SHA",
                                                         "TLS_RSA_WITH_AES_128_CBC_SHA",
                                                         "TLS_RSA_WITH_3DES_EDE_CBC_SHA" });
                }
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        startServer(tls10Server, tls12Server);
    }

    private void startServer(HttpServer... servers) {
        for (HttpServer server : servers) {
            server.createContext("/ping", new PingHandler());
            server.setExecutor(executorService);
            server.start();
        }
    }

    public void stop() {
        this.tls10Server.stop(0);
        this.tls12Server.stop(0);
        executorService.shutdownNow();
    }

    public static void main(String[] args) throws Exception {
        TestServer testServer = new TestServer();
        testServer.start();
        System.out.println("Server started");

        try (CloseableHttpAsyncClient client = getAsyncClient()) {
            TestServer.client = client;

            test("TLSv1.2", "https://localhost:8012/ping");
            test("TLSv1.0", "https://localhost:8010/ping");
        } catch (Throwable t) {
            System.out.println("Uncaught throwable; this should not occur");
            System.out.println(t.getMessage());
            t.printStackTrace();
        } finally {
            testServer.stop();
        }
    }

    private static void test(String name, String uri) throws InterruptedException {
        LatchCallback latch = new LatchCallback();
        client.execute(create(Method.GET, URI.create(uri)), latch);
        System.out.print("Waiting for response from " + name + "...");
        System.out.println(latch.await(5, TimeUnit.SECONDS) ? "returned." : "stalled!");
    }

    static CloseableHttpAsyncClient getAsyncClient() throws Exception {
        CloseableHttpAsyncClient client = HttpAsyncClients.custom()
            .setConnectionManager(PoolingAsyncClientConnectionManagerBuilder
                .create()
                .setTlsStrategy(ClientTlsStrategyBuilder.create()
                    .setSslContext(SSLTestContexts.createClientSSLContext())
                    .setTlsVersions(new String[]{ "TLSv1.2", "TLSv1.3" })
                    .build()).build())
            // Changing this to `HttpVersionPolicy.NEGOTIATE` seems to fix the stall
            .setVersionPolicy(HttpVersionPolicy.FORCE_HTTP_1)
            .build();

        client.start();
        return client;
    }

    private static class PingHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange httpExchange) throws IOException {
            httpExchange.sendResponseHeaders(200, 0);
            httpExchange.getResponseBody().close();
            httpExchange.close();
        }
    }

    private static class LatchCallback implements FutureCallback<SimpleHttpResponse> {
        private final CountDownLatch latch = new CountDownLatch(1);

        public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return latch.await(timeout, unit);
        }

        @Override
        public void completed(SimpleHttpResponse result) {
            System.out.print("completed... ");
            latch.countDown();
        }

        @Override
        public void failed(Exception ex) {
            System.out.print("failed... ");
            latch.countDown();
        }

        @Override
        public void cancelled() {
            System.out.print("cancelled... ");
            latch.countDown();
        }
    }
}
