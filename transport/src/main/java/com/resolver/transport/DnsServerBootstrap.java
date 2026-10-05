package com.resolver.transport;

import com.resolver.pipeline.ResolutionPipeline;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.dns.DatagramDnsQueryDecoder;
import io.netty.handler.codec.dns.DatagramDnsResponseEncoder;
import io.netty.handler.codec.dns.TcpDnsQueryDecoder;
import io.netty.handler.codec.dns.TcpDnsResponseEncoder;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Bootstraps and manages the Netty DNS servers (UDP and TCP).
 * Both channels share the same worker EventLoopGroup to maximize resource efficiency.
 */
public class DnsServerBootstrap {

    private static final Logger logger = LoggerFactory.getLogger(DnsServerBootstrap.class);

    private final String bindAddress;
    private final int port;
    private final ResolutionPipeline pipeline;

    // Shared thread pools for UDP/TCP
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;

    // Active bindings
    private Channel udpChannel;
    private Channel tcpChannel;

    /**
     * Configures the Netty bootstrap without starting it yet.
     * Starts thread pools based on available processors.
     *
     * @param bindAddress The interface to listen on (e.g. "127.0.0.1" or "0.0.0.0")
     * @param port The port to bind (default 53)
     * @param pipeline The core resolution logic that handlers will call
     */
    public DnsServerBootstrap(String bindAddress, int port, ResolutionPipeline pipeline) {
        this.bindAddress = bindAddress;
        this.port = port;
        this.pipeline = pipeline;

        // The boss group only needs 1 thread to accept TCP connections and bind UDP socket
        this.bossGroup = new NioEventLoopGroup(1);

        // The worker group handles network I/O across both TCP & UDP
        // Defaults to processors * 2
        this.workerGroup = new NioEventLoopGroup();
    }

    /**
     * Starts the UDP and TCP servers simultaneously.
     * Blocks until the sockets are bound successfully.
     *
     * @throws InterruptedException if thread interrupted while binding
     */
    public void start() throws InterruptedException {
        logger.info("Starting DNS Resolver on {}:{}", bindAddress, port);

        // 1. Start UDP Server
        Bootstrap udpBootstrap = new Bootstrap()
            .group(workerGroup)
            .channel(NioDatagramChannel.class)
            .handler(new ChannelInitializer<NioDatagramChannel>() {
                @Override
                protected void initChannel(NioDatagramChannel ch) {
                    // UDP Pipeline
                    ch.pipeline().addLast(
                        new DatagramDnsQueryDecoder(),
                        new DatagramDnsResponseEncoder(),
                        new UdpDnsHandler(pipeline)
                    );
                }
            });

        udpChannel = udpBootstrap.bind(bindAddress, port).sync().channel();
        logger.info("DNS UDP server listening on port {}", port);

        // 2. Start TCP Server
        ServerBootstrap tcpBootstrap = new ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel.class)
            // Disable Nagle's algorithm for faster TCP-DNS latency
            .childOption(io.netty.channel.ChannelOption.TCP_NODELAY, true)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    // TCP Pipeline per connection
                    ch.pipeline().addLast(
                        // Close inactive TCP connections after 10s idle (RFC 7766)
                        new IdleStateHandler(10, 0, 0, TimeUnit.SECONDS),

                        // Decodes and strips the 2-byte TCP length field automatically
                        new TcpDnsQueryDecoder(),

                        // Encodes response and prepends the 2-byte TCP length field automatically
                        new TcpDnsResponseEncoder(),

                        // Handler
                        new TcpDnsHandler(pipeline)
                    );
                }
            });

        tcpChannel = tcpBootstrap.bind(bindAddress, port).sync().channel();
        logger.info("DNS TCP server listening on port {}", port);
    }

    /**
     * Performs a graceful shutdown of all bound sockets and thread pools.
     * Blocks for up to 5 seconds.
     */
    public void shutdown() {
        logger.info("Shutting down DNS server...");

        if (udpChannel != null) {
            udpChannel.close();
        }
        if (tcpChannel != null) {
            tcpChannel.close();
        }

        // Gracefully shut down thread pools
        if (workerGroup != null) {
            workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        }

        logger.info("DNS server completely shut down");
    }
}
