package com.pixelmosaic.ws;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RateLimitKeyTest {

    @Test
    void ipv4IsUsedAsIs() throws Exception {
        assertEquals("103.203.36.133",
                MosaicWebSocketHandler.rateLimitKey(InetAddress.getByName("103.203.36.133")));
    }

    @Test
    void ipv6AddressesInTheSame64ShareOneKey() throws Exception {
        String a = MosaicWebSocketHandler.rateLimitKey(InetAddress.getByName("2401:4900:1c2a:5d10:1111:2222:3333:4444"));
        String b = MosaicWebSocketHandler.rateLimitKey(InetAddress.getByName("2401:4900:1c2a:5d10:aaaa:bbbb:cccc:dddd"));
        assertEquals("2401:4900:1c2a:5d10::/64", a);
        assertEquals(a, b);
    }
}
