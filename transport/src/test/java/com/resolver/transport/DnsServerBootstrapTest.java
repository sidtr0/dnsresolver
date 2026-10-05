package com.resolver.transport;

import com.resolver.pipeline.ResolutionPipeline;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.dns.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class DnsServerBootstrapTest {

    private static DnsServerBootstrap server;
    private static final int TEST_PORT = 15354; // Use non-standard port for testing
    private static final String BIND_HOST = "127.0.0.1";

    // We mock the pipeline with a static implementation that always returns 8.8.8.8 for A queries
    private static final ResolutionPipeline mockPipeline = query -> {
        DefaultDnsResponse response = new DefaultDnsResponse(query.id(), query.opCode(), DnsResponseCode.NOERROR);
        response.addRecord(DnsSection.ANSWER,
            new DefaultDnsRawRecord(
                query.recordAt(DnsSection.QUESTION, 0).name(),
                DnsRecordType.A,
                60,
                Unpooled.wrappedBuffer(new byte[]{8, 8, 8, 8})
            )
        );
        return CompletableFuture.completedFuture(response);
    };

    @BeforeAll
    static void startServer() throws InterruptedException {
        server = new DnsServerBootstrap(BIND_HOST, TEST_PORT, mockPipeline);
        server.start();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.shutdown();
        }
    }

    @Test
    void testUdpResolution() throws Exception {
        InetAddress address = InetAddress.getByName(BIND_HOST);

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(3000);

            // Raw bytes for a DNS query: type A, IN, "google.com"
            // Transaction ID: 0x12 0x34
            byte[] queryBytes = new byte[] {
                0x12, 0x34, // ID
                0x01, 0x00, // Flags (RD=1)
                0x00, 0x01, // 1 question
                0x00, 0x00, // 0 answers
                0x00, 0x00, // 0 authority
                0x00, 0x00, // 0 additional
                // "google.com"
                0x06, 'g', 'o', 'o', 'g', 'l', 'e',
                0x03, 'c', 'o', 'm',
                0x00,
                // Type A (1), Class IN (1)
                0x00, 0x01, 0x00, 0x01
            };

            DatagramPacket packet = new DatagramPacket(queryBytes, queryBytes.length, address, TEST_PORT);
            socket.send(packet);

            byte[] buf = new byte[512];
            DatagramPacket responsePacket = new DatagramPacket(buf, buf.length);
            socket.receive(responsePacket);

            assertEquals(0x12, buf[0]);
            assertEquals(0x34, buf[1]);

            // Expected response should contain our mocked answer (8.8.8.8)
            // Skip header and question section, checking the answer section isn't strictly necessary
            // to prove the server is listening and responding, checking the transaction ID and QR bit is enough.

            // QR bit should be 1 (response) -> buf[2] top bit is 1
            assertTrue((buf[2] & 0x80) != 0, "QR bit should be set");
            // RCODE should be 0 (NOERROR) -> buf[3] bottom 4 bits are 0
            assertEquals(0, buf[3] & 0x0F, "RCODE should be NOERROR");
        }
    }

    @Test
    void testTcpResolution() throws Exception {
        try (Socket socket = new Socket(BIND_HOST, TEST_PORT)) {
            socket.setSoTimeout(3000);

            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            byte[] queryBytes = new byte[] {
                0x56, 0x78, // ID
                0x01, 0x00, // Flags (RD=1)
                0x00, 0x01, // 1 question
                0x00, 0x00, // 0 answers
                0x00, 0x00, // 0 authority
                0x00, 0x00, // 0 additional
                // "example.com"
                0x07, 'e', 'x', 'a', 'm', 'p', 'l', 'e',
                0x03, 'c', 'o', 'm',
                0x00,
                // Type A (1), Class IN (1)
                0x00, 0x01, 0x00, 0x01
            };

            // TCP DNS prepends exactly 2 bytes for the length
            out.writeShort(queryBytes.length);
            out.write(queryBytes);
            out.flush();

            // Read response
            int length = in.readUnsignedShort();
            assertTrue(length > 0, "Response should have a positive length");

            byte[] responseBytes = new byte[length];
            in.readFully(responseBytes);

            assertEquals(0x56, responseBytes[0]);
            assertEquals(0x78, responseBytes[1]);

            // QR bit should be 1 (response)
            assertTrue((responseBytes[2] & 0x80) != 0, "QR bit should be set");
            // RCODE should be 0 (NOERROR)
            assertEquals(0, responseBytes[3] & 0x0F, "RCODE should be NOERROR");
        }
    }
}
