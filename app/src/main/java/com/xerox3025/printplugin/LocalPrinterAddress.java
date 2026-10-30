package com.xerox3025.printplugin;

import java.io.IOException;
import java.net.InetAddress;

/** Numeric private/link-local IPv4 only: no DNS, public endpoints or URL injection. */
final class LocalPrinterAddress {
    private LocalPrinterAddress() {}

    static InetAddress parse(String value) throws IOException {
        if (value == null) throw new IOException("Enter a private IPv4 printer address");
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) throw new IOException("Enter a numeric private IPv4 address");
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            if (!parts[i].matches("0|[1-9][0-9]{0,2}")) throw new IOException("Invalid IPv4 address");
            int n = Integer.parseInt(parts[i]);
            if (n > 255) throw new IOException("Invalid IPv4 address");
            bytes[i] = (byte) n;
        }
        int a = bytes[0] & 255, b = bytes[1] & 255;
        if (!(a == 10 || a == 172 && b >= 16 && b <= 31 || a == 192 && b == 168
                || a == 169 && b == 254)) {
            throw new IOException("Only private or link-local IPv4 printer addresses are allowed");
        }
        return InetAddress.getByAddress(bytes);
    }
}
