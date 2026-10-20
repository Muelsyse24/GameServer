# Game Login Server

基于 **Netty + Protobuf + Redis + MySQL** 实现的单机版游戏登录服务器 Demo，用于学习高并发网络编程、缓存旁路（Cache-Aside）和常见服务端工程化实践。

> 仅用于个人学习与简历展示。

## 技术栈

| 分类 | 选型 |
|---|---|
| 语言 | Java 21 |
| 网络框架 | Netty 4.1（NIO，主从 Reactor） |
| 序列化 | Protobuf 3.25 |
| 缓存 | Redis（Jedis 5 + JedisPool） |
| 持久化 | MySQL 8（HikariCP 连接池） |
| 密码哈希 | BCrypt（cost = 12） |
| 构建 | Maven |
| 日志 | Logback |

## 功能特性

- **TCP 长连接服务器**：Boss/Worker 双 EventLoopGroup，监听 8888 端口。
- **自定义协议栈**：`LengthFieldBasedFrameDecoder/Prepender` 解决 TCP 粘包拆包 → `ProtobufDecoder/Encoder` 做二进制序列化。
- **登录缓存旁路（Cache-Aside）**：
  1. 请求先查 Redis，命中则 BCrypt 校验后直接返回；
  2. 未命中回源 MySQL，校验成功后回填 Redis；
  3. 新玩家落库取自增主键，再双写 Redis。
- **心跳与空闲连接回收**：客户端写空闲 5s 主动发心跳包，服务端读空闲 10s 未收到消息则主动断开，清理僵尸连接。
- **密码安全**：BCrypt 加盐哈希存储；兼容早期明文老账号，首次登录成功后自动平滑升级为哈希值。
- **连接池**：HikariCP（JDBC）+ JedisPool（Redis），开启泄漏检测。
- **配置外置**：敏感信息放在 `db.properties`（已 gitignore），仓库内只保留 `.example` 模板。

## 项目结构

```
src/main
├── java/com/game/demo
│   ├── GameServer.java        # Netty 服务端入口 + 登录业务 Handler
│   ├── GameClient.java        # Netty 客户端 Demo（命令行输入用户名登录）
│   ├── MySQLUtil.java         # HikariCP 单例连接池
│   ├── RedisUtil.java         # JedisPool 单例连接池
│   ├── PasswordUtil.java      # BCrypt 哈希 + 老明文兼容升级
│   └── protobuf/PlayerProto.java  # protoc 生成
├── proto/player.proto         # Protobuf 协议定义
└── resources
    ├── db.properties.example  # 配置模板（复制为 db.properties 后填真实值）
    └── logback.xml
```

## 本地启动

### 前置依赖

- JDK 21+
- MySQL 8（本地 3306）
- Redis（本地 6379）

### 1. 建库建表

```sql
CREATE DATABASE game_db DEFAULT CHARSET utf8mb4;
USE game_db;

CREATE TABLE t_player (
  id       BIGINT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(64)  NOT NULL UNIQUE,
  password VARCHAR(100) NOT NULL   -- BCrypt 哈希固定 60 字符，留足余量
);
```

### 2. 配置

```bash
cp src/main/resources/db.properties.example src/main/resources/db.properties
# 编辑 db.properties，填入真实的 MySQL 账号密码
```

### 3. 编译运行

在 IDEA 里 Reload Maven Project 下载依赖，然后分别运行：

- `com.game.demo.GameServer#main` —— 启动服务端，监听 8888 端口
- `com.game.demo.GameClient#main` —— 启动客户端，命令行输入用户名即可登录

### 4. 观察日志

- 新用户首次登录：`【MySQL】新玩家注册成功（BCrypt 落库）`
- 第二次登录（Redis 命中）：`【Redis】命中缓存，BCrypt 校验通过`
- 客户端空闲 5s：自动发心跳；服务端 10s 收不到心跳主动断连。

## 协议定义

见 `src/main/proto/player.proto`：

```proto
message PlayerLoginRequest  { string username = 1; string password = 2; }
message PlayerLoginResponse { int32 code = 1; string message = 2; int64 playerId = 3; }
```

心跳包复用 `PlayerLoginRequest`，`username = "HEARTBEAT"`；服务端回复 `code = 999, message = "PONG"`。

## 已知局限 & 后续 TODO

- 单节点单机部署，未做会话路由与多服集群。
- 未实现角色系统、战斗逻辑等游戏业务，仅打通登录链路。
- 待扩展：在线玩家 Session 管理器、网关与业务服拆分、Protobuf 多消息类型分发（Opcode 路由）。

## License

MIT
