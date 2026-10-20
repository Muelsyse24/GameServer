package com.game.demo;

import com.game.demo.protobuf.PlayerProto;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.codec.protobuf.ProtobufDecoder;
import io.netty.handler.codec.protobuf.ProtobufEncoder;

public class GameServer {
    public static void main(String[] args) throws InterruptedException {
        // 1. 创建两个线程组
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        try {
            // 2. 启动引导类
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            // 0. 空闲状态检测：10秒没读到客户端消息，触发读空闲事件
                            ch.pipeline().addLast(new io.netty.handler.timeout.IdleStateHandler(10, 0, 0, java.util.concurrent.TimeUnit.SECONDS));

                            // 1. 解码器（剥离4字节长度头）
                            ch.pipeline().addLast(new LengthFieldBasedFrameDecoder(1024, 0, 4, 0, 4));
                            // 2. 编码器（在消息前自动加上4字节长度头）【新增】
                            ch.pipeline().addLast(new LengthFieldPrepender(4));

                            // 3. Protobuf 解码器
                            ch.pipeline().addLast(new ProtobufDecoder(PlayerProto.PlayerLoginRequest.getDefaultInstance()));
                            // 4. Protobuf 编码器
                            ch.pipeline().addLast(new ProtobufEncoder());

                            // 5. 业务处理器
                            ch.pipeline().addLast(new SimpleServerHandler());
                        }
                    });

            System.out.println("游戏服务器正在启动，监听端口 8888...");
            // 3. 绑定端口，同步等待成功
            bootstrap.bind(8888).sync().channel().closeFuture().sync();
        } finally {
            // 4. 优雅关闭
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

    // 自定义业务处理器
    static class SimpleServerHandler extends ChannelInboundHandlerAdapter {


        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            // 如果是空闲状态事件
            if (evt instanceof io.netty.handler.timeout.IdleStateEvent) {
                io.netty.handler.timeout.IdleStateEvent e = (io.netty.handler.timeout.IdleStateEvent) evt;
                // 如果是读空闲（10秒没收到客户端消息）
                if (e.state() == io.netty.handler.timeout.IdleState.READER_IDLE) {
                    System.out.println("【警告】客户端长时间未发送心跳，主动断开连接: " + ctx.channel().remoteAddress());
                    ctx.close(); // 断开连接，触发 channelInactive 清理玩家数据
                }
            } else {
                super.userEventTriggered(ctx, evt);
            }
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            PlayerProto.PlayerLoginRequest req = (PlayerProto.PlayerLoginRequest) msg;

            // 拦截心跳包（我们用用户名 "HEARTBEAT" 来标识心跳）
            if ("HEARTBEAT".equals(req.getUsername())) {
                // 回复 Pong 心跳包（复用 LoginResponse，保持代码简单）
                PlayerProto.PlayerLoginResponse pong = PlayerProto.PlayerLoginResponse.newBuilder()
                        .setCode(999)
                        .setMessage("PONG")
                        .setPlayerId(0L)
                        .build();
                ctx.writeAndFlush(pong);
                return; // 心跳逻辑走完，直接返回，不走后面的登录逻辑
            }

            // 2. 正常登录逻辑（Redis 缓存旁路 + MySQL 持久层 + BCrypt 密码哈希）
            String username = req.getUsername();
            String password = req.getPassword();

            PlayerProto.PlayerLoginResponse resp;

            try (redis.clients.jedis.Jedis jedis = RedisUtil.getJedis()) {
                String redisKey = "user:" + username;
                String cachedHash = jedis.get(redisKey);

                if (cachedHash != null) {
                    // 第一层：Redis 缓存命中，直接用 BCrypt 校验
                    if (PasswordUtil.check(password, cachedHash)) {
                        resp = PlayerProto.PlayerLoginResponse.newBuilder()
                                .setCode(0).setMessage("登录成功（Redis缓存命中）").setPlayerId(10001L).build();
                        System.out.println("【Redis】命中缓存，BCrypt 校验通过，登录成功");
                    } else {
                        resp = PlayerProto.PlayerLoginResponse.newBuilder()
                                .setCode(1).setMessage("密码错误").setPlayerId(0L).build();
                    }
                } else {
                    // 第二层：Redis 未命中，回源 MySQL
                    System.out.println("【MySQL】Redis未命中，查询 MySQL 持久层...");
                    try (java.sql.Connection conn = MySQLUtil.getConnection();
                         java.sql.PreparedStatement pstmt = conn.prepareStatement(
                                 "SELECT id, password FROM t_player WHERE username = ?")) {

                        pstmt.setString(1, username);
                        java.sql.ResultSet rs = pstmt.executeQuery();

                        if (rs.next()) {
                            long playerId = rs.getLong("id");
                            String stored = rs.getString("password");

                            boolean ok = PasswordUtil.check(password, stored);
                            boolean legacyPlain = !ok && PasswordUtil.isLegacyPlainText(stored)
                                    && stored.equals(password);

                            if (ok || legacyPlain) {
                                // 老的明文账号：登录成功后顺便把它升级成 BCrypt 哈希
                                if (legacyPlain) {
                                    String newHash = PasswordUtil.hash(password);
                                    try (java.sql.PreparedStatement up = conn.prepareStatement(
                                            "UPDATE t_player SET password = ? WHERE id = ?")) {
                                        up.setString(1, newHash);
                                        up.setLong(2, playerId);
                                        up.executeUpdate();
                                    }
                                    stored = newHash;
                                    System.out.println("【MySQL】检测到老明文账号，已平滑升级为 BCrypt 哈希");
                                }
                                // 回填 Redis（存的是哈希，不是明文）
                                jedis.set(redisKey, stored);
                                resp = PlayerProto.PlayerLoginResponse.newBuilder()
                                        .setCode(0).setMessage("登录成功（MySQL回源，已回填Redis）").setPlayerId(playerId).build();
                                System.out.println("【MySQL】校验成功，玩家ID: " + playerId);
                            } else {
                                resp = PlayerProto.PlayerLoginResponse.newBuilder()
                                        .setCode(1).setMessage("密码错误").setPlayerId(0L).build();
                            }
                        } else {
                            // 全新玩家：BCrypt 哈希后落库，再双写 Redis
                            String hashed = PasswordUtil.hash(password);
                            try (java.sql.PreparedStatement insertStmt = conn.prepareStatement(
                                    "INSERT INTO t_player (username, password) VALUES (?, ?)",
                                    java.sql.Statement.RETURN_GENERATED_KEYS)) {
                                insertStmt.setString(1, username);
                                insertStmt.setString(2, hashed);
                                insertStmt.executeUpdate();

                                java.sql.ResultSet generatedKeys = insertStmt.getGeneratedKeys();
                                long newPlayerId = 0;
                                if (generatedKeys.next()) {
                                    newPlayerId = generatedKeys.getLong(1);
                                }

                                jedis.set(redisKey, hashed);
                                resp = PlayerProto.PlayerLoginResponse.newBuilder()
                                        .setCode(0).setMessage("注册并登录成功（MySQL+Redis双写）").setPlayerId(newPlayerId).build();
                                System.out.println("【MySQL】新玩家注册成功（BCrypt 落库），玩家ID: " + newPlayerId);
                            }
                        }
                    }
                }
                ctx.writeAndFlush(resp);
            } catch (Exception e) {
                System.out.println("【错误】数据库或 Redis 连接异常！");
                e.printStackTrace();
            }
        }
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            // 判断是否是连接重置异常（客户端强制断线）
            if (cause instanceof java.io.IOException || cause instanceof java.net.SocketException) {
                System.out.println("【提示】客户端异常断开连接: " + ctx.channel().remoteAddress());
            } else {
                // 其他真正的代码异常，需要打印堆栈排查
                cause.printStackTrace();
            }
            // 无论哪种情况，都要关闭连接，释放资源
            ctx.close();
        }
    }
}