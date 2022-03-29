package com.ok2c.http.client.benchmark;


import javax.net.ssl.SSLContext;

import org.apache.hc.core5.ssl.SSLContexts;

public class SSLTestContexts {

    public static SSLContext createServerSSLContext() throws Exception {
        return SSLContexts.custom()
            .loadTrustMaterial(SSLTestContexts.class.getResource("/test.keystore"),
                "nopassword".toCharArray())
            .loadKeyMaterial(SSLTestContexts.class.getResource("/test.keystore"),
                "nopassword".toCharArray(), "nopassword".toCharArray())
            .build();
    }

    public static SSLContext createClientSSLContext() throws Exception {
        return SSLContexts.custom()
            .loadTrustMaterial(SSLTestContexts.class.getResource("/test.keystore"),
                "nopassword".toCharArray())
            .build();
    }

}
