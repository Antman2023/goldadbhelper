package io.github.devicebridge.core;

/** Explicit smart-socket server address, distinct from a device's port 5555. */
public final class AdbServerEndpoint {
    public final String host;
    public final int port;
    private AdbServerEndpoint(String host,int port){this.host=host;this.port=port;}
    public String toString(){return (host.indexOf(':')>=0?"["+host+"]":host)+":"+port;}
    public static AdbServerEndpoint parse(String value){
        if(value==null)throw new IllegalArgumentException("请输入ADB服务地址");
        String text=value.trim().replace('：',':');
        if(text.isEmpty()||text.length()>512)throw new IllegalArgumentException("请输入有效ADB服务地址");
        for(int i=0;i<text.length();i++)if(Character.isWhitespace(text.charAt(i))||Character.isISOControl(text.charAt(i)))throw new IllegalArgumentException("地址不能包含空白或控制字符");
        if(text.indexOf('/')>=0||text.indexOf('\\')>=0||text.indexOf('@')>=0||text.indexOf('?')>=0||text.indexOf('#')>=0)throw new IllegalArgumentException("请输入主机:端口，不是网址或账号");
        String host=text, portText="5037";
        if(text.startsWith("[")){
            int end=text.indexOf(']');
            if(end<=1||text.indexOf(']',end+1)>=0)throw new IllegalArgumentException("IPv6地址括号不完整");
            host=text.substring(1,end);
            if(host.indexOf(':')<0)throw new IllegalArgumentException("方括号仅用于IPv6地址");
            if(end+1<text.length()){
                if(text.charAt(end+1)!=':')throw new IllegalArgumentException("地址格式应为[IPv6]:端口");
                portText=text.substring(end+2);
            }
        }else if(text.indexOf('[')>=0||text.indexOf(']')>=0)throw new IllegalArgumentException("地址括号不完整");
        else if(text.indexOf(':')==text.lastIndexOf(':')&&text.indexOf(':')>=0){
            int colon=text.indexOf(':');host=text.substring(0,colon);portText=text.substring(colon+1);
        }
        if(host.isEmpty()||!portText.matches("[0-9]{1,5}"))throw new IllegalArgumentException("服务端口必须为1–65535");
        int port=Integer.parseInt(portText);
        if(port<1||port>65535)throw new IllegalArgumentException("服务端口必须为1–65535");
        return new AdbServerEndpoint(host,port);
    }
}
