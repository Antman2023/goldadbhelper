package io.github.devicebridge.android;

import io.github.devicebridge.core.Ipv4SubnetSuggestion;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.Locale;

/** Passive interface inspection only. Never connects, resolves a name, or probes a host. */
final class LocalNetworkPrefix {
    private LocalNetworkPrefix() {}
    static String suggest() {
        String prefix = "";
        int bestRank = Integer.MAX_VALUE;
        boolean ambiguous = false;
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback() || network.isPointToPoint() || network.isVirtual()) continue;
                String name = network.getName().toLowerCase(Locale.ROOT);
                if (name.startsWith("tun") || name.startsWith("tap") || name.startsWith("ppp")
                        || name.startsWith("veth") || name.startsWith("docker")) continue;
                int rank = name.startsWith("wlan") || name.startsWith("wifi") ? 0 : name.startsWith("eth") ? 1 : 2;
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!(address instanceof Inet4Address)) continue;
                    String candidate = Ipv4SubnetSuggestion.fromAddress(address.getHostAddress());
                    if (candidate.isEmpty()) continue;
                    if (rank < bestRank) { prefix = candidate; bestRank = rank; ambiguous = false; }
                    else if (rank == bestRank && !prefix.equals(candidate)) ambiguous = true;
                }
            }
        } catch (SocketException | SecurityException ignored) { return ""; }
        return ambiguous ? "" : prefix;
    }
}
