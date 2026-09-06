package com.litevision.tv;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 极简 DNS-over-UDP 客户端：直接向指定 DNS 服务器发送 A 记录查询，解析应答中的 IPv4。
 *
 * 用途：DDNS 域名的 IP 更新不及时问题。系统解析器（{@code InetAddress.getAllByName}）
 * 走系统缓存 + 运营商 LocalDNS，IP 变化后可能长时间拿到旧记录；这里绕过全部缓存，
 * 直接向公共递归 DNS（阿里 223.5.5.5 / 腾讯 119.29.29.29 / 114DNS）发原始 UDP 查询，
 * 拿到的是递归服务器实时回源的最新权威记录。
 *
 * 只依赖 java.net / java.util，无 Android API——可在 PC 上用 javac/java 单独编译验证。
 */
public final class DnsOverUdp {

    private static final Random RANDOM = new Random();

    /** DNS 标准端口。 */
    public static final int DNS_PORT = 53;

    /** A 记录（IPv4 地址）。 */
    private static final int TYPE_A = 1;
    /** IN class。 */
    private static final int CLASS_IN = 1;

    private DnsOverUdp() {
    }

    /**
     * 向指定 DNS 服务器查询域名的 A 记录。
     *
     * @param domain    要查询的域名（如 "nvr.example.com"），不要带 scheme / 端口 / 路径
     * @param dnsServer DNS 服务器地址（如 "223.5.5.5"）
     * @param timeoutMs 单次查询超时（毫秒），超时/失败返回空列表
     * @return 解析到的 IPv4 地址列表（可能为空；顺序与应答顺序一致）
     */
    public static List<String> queryA(String domain, String dnsServer, int timeoutMs) {
        List<String> result = new ArrayList<>();
        DatagramSocket socket = null;
        try {
            byte[] query = buildQuery(domain);
            socket = new DatagramSocket();
            socket.setSoTimeout(timeoutMs);
            InetAddress server = InetAddress.getByName(dnsServer);
            socket.send(new DatagramPacket(query, query.length, server, DNS_PORT));

            byte[] buf = new byte[1500];
            DatagramPacket response = new DatagramPacket(buf, buf.length);
            socket.receive(response);
            parseAnswers(query, buf, response.getLength(), result);
        } catch (Exception ignored) {
            // 超时/网络不可达/格式异常：调用方把空列表当作"该服务器不可用"，还有其他候选
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Exception ignored) {
                }
            }
        }
        return result;
    }

    // --- 查询包构造 ---

    /**
     * 构造标准 DNS 查询报文：
     * Header(12B): ID | FLAGS=0x0100(RD=1 递归查询) | QDCOUNT=1 | ANCOUNT/NSCOUNT/ARCOUNT=0
     * Question: QNAME(标签序列) | QTYPE=A | QCLASS=IN
     */
    private static byte[] buildQuery(String domain) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64);

        // Header
        int id = RANDOM.nextInt(0x10000);
        out.write(id >>> 8);
        out.write(id & 0xFF);
        out.write(0x01); // flags 高字节: RD=1
        out.write(0x00); // flags 低字节
        out.write(0x00); out.write(0x01); // QDCOUNT = 1
        out.write(0x00); out.write(0x00); // ANCOUNT = 0
        out.write(0x00); out.write(0x00); // NSCOUNT = 0
        out.write(0x00); out.write(0x00); // ARCOUNT = 0

        // QNAME: "a.b.com" -> [1]'a'[1]'b'[3]'c''o''m'[0]
        for (String label : domain.split("\\.")) {
            byte[] labelBytes = label.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            out.write(labelBytes.length);
            out.write(labelBytes, 0, labelBytes.length);
        }
        out.write(0); // QNAME 结束符

        // QTYPE = A, QCLASS = IN
        out.write(TYPE_A >>> 8); out.write(TYPE_A & 0xFF);
        out.write(CLASS_IN >>> 8); out.write(CLASS_IN & 0xFF);

        return out.toByteArray();
    }

    // --- 应答解析 ---

    /**
     * 解析 DNS 应答，提取所有 A 记录的 IPv4 地址。
     *
     * 校验：事务 ID 与查询一致（防止串包）、QR=1（是应答）、RCODE=0（无错误）。
     * 支持 QNAME/NAME 的压缩指针（0xC0 开头的两字节指针）。
     */
    private static void parseAnswers(byte[] query, byte[] data, int length, List<String> out) {
        if (length < 12) {
            return;
        }
        // 事务 ID 必须与查询一致
        if ((data[0] & 0xFF) != (query[0] & 0xFF) || (data[1] & 0xFF) != (query[1] & 0xFF)) {
            return;
        }
        // QR 位（最高位）必须为 1（应答）；RCODE（低 4 位）必须为 0（成功）
        boolean isResponse = (data[2] & 0x80) != 0;
        int rcode = data[3] & 0x0F;
        if (!isResponse || rcode != 0) {
            return;
        }
        int qdCount = readU16(data, 4);
        int anCount = readU16(data, 6);
        if (anCount <= 0) {
            return;
        }

        int pos = 12;
        // 跳过 Question 区（qdCount 个 name + type + class）
        for (int i = 0; i < qdCount && pos < length; i++) {
            pos = skipName(data, length, pos);
            pos += 4; // QTYPE + QCLASS
        }
        // 逐条解析 Answer 区
        for (int i = 0; i < anCount && pos < length; i++) {
            pos = skipName(data, length, pos);
            if (pos + 10 > length) {
                break; // TYPE/CLASS/TTL/RDLENGTH 不完整
            }
            int type = readU16(data, pos);
            pos += 2; // TYPE
            pos += 2; // CLASS
            pos += 4; // TTL
            int rdLength = readU16(data, pos);
            pos += 2;
            if (pos + rdLength > length) {
                break;
            }
            if (type == TYPE_A && rdLength == 4) {
                String ip = (data[pos] & 0xFF) + "." + (data[pos + 1] & 0xFF) + "."
                        + (data[pos + 2] & 0xFF) + "." + (data[pos + 3] & 0xFF);
                if (!out.contains(ip)) {
                    out.add(ip);
                }
            }
            pos += rdLength;
        }
    }

    /**
     * 跳过一个 NAME 字段（可能带压缩指针），返回跳过后的读取位置。
     * 指针不再跟随跳转（本方法只需知道 NAME 占用的线性长度）。
     */
    private static int skipName(byte[] data, int length, int pos) {
        while (pos < length) {
            int len = data[pos] & 0xFF;
            if (len == 0) {
                return pos + 1; // NAME 结束
            }
            if ((len & 0xC0) == 0xC0) {
                // 压缩指针：两字节，NAME 结束
                return pos + 2;
            }
            pos += 1 + len; // 标签长度字节 + 标签内容
        }
        return pos;
    }

    private static int readU16(byte[] data, int pos) {
        return ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
    }
}
