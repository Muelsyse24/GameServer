package com.game.demo;

import com.game.demo.protobuf.PlayerProto;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.codec.protobuf.ProtobufDecoder;
import io.netty.handler.codec.protobuf.ProtobufEncoder;

import java.util.Scanner;

public class GameClient {
    public static void main(String[] args) {
        EventLoopGroup group = new NioEventLoopGroup();
        try {
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            // 0. 空闲状态检测：5秒没写数据，触发写空闲事件
                            ch.pipeline().addLast(new io.netty.handler.timeout.IdleStateHandler(0, 5, 0, java.util.concurrent.TimeUnit.SECONDS));
                            // 客户端发数据给服务端，必须加上 LengthFieldPrepender
                            ch.pipeline().addLast(new LengthFieldBasedFrameDecoder(1024, 0, 4, 0, 4));
                            ch.pipeline().addLast(new LengthFieldPrepender(4));

                            // 客户端接收服务端响应（需要指定接收的类类型）
                            ch.pipeline().addLast(new ProtobufDecoder(PlayerProto.PlayerLoginResponse.getDefaultInstance()));
                            ch.pipeline().addLast(new ProtobufEncoder());

                            ch.pipeline().addLast(new ClientHandler());
                        }
                    });

            System.out.println("客户端正在连接 8888 端口...");
            Channel channel = bootstrap.connect("127.0.0.1", 8888).sync().channel();

            System.out.println("请输入用户名（按回车发送）：");
            Scanner scanner = new Scanner(System.in);
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine();
                // 构建并发送 Protobuf 请求对象
                PlayerProto.PlayerLoginRequest req = PlayerProto.PlayerLoginRequest.newBuilder()
                        .setUsername(line)
                        .setPassword("123456")
                        .build();
                channel.writeAndFlush(req);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            group.shutdownGracefully();
        }
    }

    static class ClientHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt instanceof io.netty.handler.timeout.IdleStateEvent) {
                io.netty.handler.timeout.IdleStateEvent e = (io.netty.handler.timeout.IdleStateEvent) evt;
                if (e.state() == io.netty.handler.timeout.IdleState.WRITER_IDLE) {
                    // 触发写空闲，发送心跳
                    System.out.println("【客户端】发送心跳包...");
                    PlayerProto.PlayerLoginRequest heartbeat = PlayerProto.PlayerLoginRequest.newBuilder()
                            .setUsername("HEARTBEAT")
                            .setPassword("PING")
                            .build();
                    ctx.writeAndFlush(heartbeat);
                }
            } else {
                super.userEventTriggered(ctx, evt);
            }
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            PlayerProto.PlayerLoginResponse resp = (PlayerProto.PlayerLoginResponse) msg;
            // 过滤心跳的 PONG 回复
            if (resp.getCode() == 999 && "PONG".equals(resp.getMessage())) {
                // 收到心跳回复，静默处理，不打印日志，防止刷屏
                return;
            }
            // 正常的登录响应
            System.out.println("收到服务端回复: 状态码=" + resp.getCode() + ", 消息=" + resp.getMessage() + ", 玩家ID=" + resp.getPlayerId());
        }
    }
}