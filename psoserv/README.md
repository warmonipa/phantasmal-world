# Phantasmal PSO Server

This server is far from complete, the only functionality that works at the moment is the proxy
server.

## Configuration

Put a psoserv.conf file in the directory where psoserv will run or pass
the `--config=/path/to/file.conf` parameter to specify a configuration file.
The [HOCON](https://github.com/lightbend/config#using-hocon-the-json-superset) format is used to
describe configurations.

## Proxy

Phantasmal PSO server supports proxying PC and BB protocol connections. Below is a sample
configuration for proxying a locally running Tethealla server using the standard Tethealla client.
Be sure to modify tethealla.ini and set server port to 22000.

Each client has an independent session. Upstream connection failures close that session without
stopping the listener, and stopping a server closes all sockets belonging to its active sessions.
The proxy assembles complete PC or BB protocol messages before forwarding them, so TCP fragmentation
and coalescing do not affect encryption or redirect rewriting. Frames whose wire size, including
encryption padding, exceeds 32 KiB are forwarded unchanged without parsing their message bodies.
Invalid sizes, truncated messages, and processing errors close the affected session. A complete
client message header received before the upstream handshake establishes the ciphers also closes
that session; an early disconnect closes its peer socket.

```hocon
proxy: {
  # Default local address used by all proxies, can be overwritten per proxy.
  bindAddress: localhost
  # Default address of the remote server used by all proxies, can be overwritten per proxy.
  remoteAddress: localhost
  # One server configuration per address/port pair that needs to be proxied.
  servers: [
    {
      # Name used for e.g. the logs. Should contain only alpha-numeric characters, minus (-) or
      # underscore (_).
      name: patch_proxy
      # PC or BB, determines the message format and encryption cipher used.
      version: PC
      # Local port the proxy will listen on.
      bindPort: 11000
      # Remote port the proxy will connect to.
      remotePort: 21000
    }
    {
      name: patch_data_proxy
      version: PC
      bindPort: 11001
      remotePort: 21001
    }
    {
      name: login_proxy
      version: BB
      bindPort: 12000
      remotePort: 22000
    }
    {
      name: character_proxy
      version: BB
      bindPort: 12001
      remotePort: 22001
    }
    {
      name: ship_proxy
      version: BB
      bindPort: 13000
      remotePort: 5278
    }
    {
      name: block_1_proxy
      version: BB
      bindPort: 13001
      remotePort: 5279
    }
    {
      name: block_2_proxy
      version: BB
      bindPort: 13002
      remotePort: 5280
    }
  ]
}
```

## Developers

Run `./gradlew :psoserv:test` for framing, cipher-state and session-lifecycle regressions. These
tests use loopback sockets and cover PC/BB handshakes, bidirectional traffic, fragmentation,
coalescing, redirect rewriting, concurrent clients, upstream failure recovery, early disconnects,
and shutdown. They do not replace compatibility testing against real PSO clients and servers.

## Building and Running

Build with `./gradlew :psoserv:build` or run with `./gradlew :psoserv:run`.

## Native Builds with GraalVM

You can create a native build using [GraalVM](https://www.graalvm.org/) by
running `./gradlew :psoserv:nativeBuild`.

Prerequisites:

- Make sure the JAVA_HOME environment variable points to a GraalVM JDK
- Install native-image with `gu` (the GraalVM updater tool)
- Install necessary libraries on Linux
- Install MSVC and use a x64 Native Tools Command Prompt for running gradle on Windows
- See the [manual](https://www.graalvm.org/reference-manual/native-image/) for details
