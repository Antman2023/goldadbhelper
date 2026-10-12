package io.github.devicebridge.core;

/** Makes a /24 suggestion from a numeric private IPv4 address, without DNS or network I/O. */
public final class Ipv4SubnetSuggestion {
    private Ipv4SubnetSuggestion() {}
    public static String fromAddress(String address) {
        if (address == null) return "";
        String[] parts = address.split("\\.", -1);
        if (parts.length != 4) return "";
        int[] bytes = new int[4];
        for (int i = 0; i < 4; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 3 || part.length() > 1 && part.charAt(0) == '0') return "";
            for (int j = 0; j < part.length(); j++) {
                char c = part.charAt(j);
                if (c < '0' || c > '9') return "";
                bytes[i] = bytes[i] * 10 + c - '0';
            }
            if (bytes[i] > 255) return "";
        }
        boolean privateAddress = bytes[0] == 10 || bytes[0] == 172 && bytes[1] >= 16 && bytes[1] <= 31
                || bytes[0] == 192 && bytes[1] == 168;
        return privateAddress ? bytes[0] + "." + bytes[1] + "." + bytes[2] : "";
    }
}
