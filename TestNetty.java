import io.netty.handler.codec.dns.TcpDnsQueryDecoder;
public class TestNetty {
    public static void main(String[] args) {
        System.out.println(TcpDnsQueryDecoder.class.getSuperclass().getName());
    }
}
