package io.github.devicebridge.core;
public final class AdbServerEndpointTests {
    private static int checks;
    private static void good(String input,String expected){if(!AdbServerEndpoint.parse(input).toString().equals(expected))throw new AssertionError(input);checks++;}
    private static void bad(String input){try{AdbServerEndpoint.parse(input);throw new AssertionError("Accepted: "+input);}catch(IllegalArgumentException expected){checks++;}}
    public static void main(String[] args){
        good("127.0.0.1","127.0.0.1:5037");good(" localhost:5043 ","localhost:5043");good("host：5037","host:5037");
        good("[::1]","[::1]:5037");good("[::1]:5043","[::1]:5043");good("2001:db8::1","[2001:db8::1]:5037");good("[fe80::1%wlan0]:1","[fe80::1%wlan0]:1");good("host:65535","host:65535");
        for(String s:new String[]{null,""," ",":5037","host:0","host:65536","host:-1","host:+1","host:","host:100000","host:abc","a b:5037","a\nb:5037","a\u0000b","tcp://host:5037","user@host:5037","host/path","host\\path","host?x","host#x","[host]:5037","[::1","[::1]x","[::1]:","[::1]]:5037","::1]"})bad(s);
        System.out.println("ADB SERVER ENDPOINT TESTS: "+checks+" passed");
    }
}
