# Messaging Service

A small TCP messaging service. Clients register with a name, send messages to each other, and acknowledge what they receive. Messages sent to an offline client are kept and delivered when it reconnects.

Built with Java 21 and Spring Boot. The protocol design and the reasons behind it are in `APPROACH.md`.

## Build, test and run: step by step

Run these from the project folder. There are two ways to run the service: directly with Java, or in Docker.

### Option A: Java and Gradle

**You need:** Java 21 (the Gradle wrapper `./gradlew` downloads Gradle itself, so nothing else is needed).

| Step | Command | What to expect |
|---|---|---|
| 1. Build and test | `./gradlew clean build` | Compiles, runs all tests, and creates the jar. Ends with `BUILD SUCCESSFUL` and `Tests: 37   passed: 37   failed: 0`. |
| 2. Check the jar | `ls build/libs` | `messaging-service-0.0.1-SNAPSHOT.jar` (the other file ending in `-plain.jar` is not runnable) |
| 3. Run the server | `java -jar build/libs/messaging-service-0.0.1-SNAPSHOT.jar` | The log ends with `TCP server started on port 9090` |
| 4. Try it | In another terminal: `nc localhost 9090`, then paste `{"type":"REGISTER","clientId":"John"}` | The reply contains `"type":"REGISTERED"` |

To run only the tests, use `./gradlew test`. To start the server without building the jar first, use `./gradlew bootRun`.

### Option B: Docker

**You need:** Docker running (Docker Desktop on Mac or Windows). No Java or Gradle is needed on your machine.

| Step | Command | What to expect |
|---|---|---|
| 1. Build the image | `docker build -t messaging-service .` | Ends with the image being named `messaging-service`. The first build downloads Gradle and the dependencies, so it needs internet. |
| 2. Run the container | `docker run --rm -p 9090:9090 --name messaging-service messaging-service` | The log ends with `TCP server started on port 9090` |
| 3. Try it | In another terminal: `nc localhost 9090`, then paste `{"type":"REGISTER","clientId":"John"}` | The reply contains `"type":"REGISTERED"` |
| 4. Stop it | `Ctrl+C`, or `docker stop messaging-service` | The container is removed (because of `--rm`) |

More about Docker, including settings, is in "Run it with Docker" below.

### Quick check that everything works

With the server running (either option), open two terminals with `nc localhost 9090` and follow "Example chat" further down. If John sends a message and Ravi receives it, the service works.

### If something goes wrong

| Problem | Cause and fix |
|---|---|
| `Address already in use` or the server does not start | Something already uses port 9090 (maybe an earlier run). Stop it, or use another port: `java -jar build/libs/*-SNAPSHOT.jar --messaging.tcp.port=9191` |
| `nc` connects but says nothing or closes | Check that you are using the port the server really listens on. In Docker, `-p 9091:9091` does not change the server's port; use `-p 9091:9090`. |
| `Cannot connect to the Docker daemon` | Docker is not running. Start Docker Desktop and try again. |
| A message is rejected with `MESSAGE_TOO_LARGE` | The whole line is longer than `messaging.tcp.max-message-size` (4096). It should be 4096 in `application.properties`. |
| `./gradlew: Permission denied` | Run `chmod +x gradlew` |

## Run it

```bash
./gradlew bootRun
```

The server listens on port `9090`.

## Run it with Docker

You need Docker and nothing else (no Java or Gradle on your machine).

**Build the image** (from the project folder):

```bash
docker build -t messaging-service .
```

**Run it:**

```bash
docker run --rm -p 9090:9090 --name messaging-service messaging-service
```

Then connect from your machine as usual: `nc localhost 9090`.

**Stop it:** press `Ctrl+C`, or run `docker stop messaging-service` from another terminal.

**Change a setting** by passing an environment variable. The name is the setting from the table below, in capitals, with `.` and `-` replaced by `_`:

```bash
docker run --rm -p 9090:9090 \
  -e MESSAGING_TCP_MAX_CLIENTS=50 \
  -e MESSAGING_TCP_MAILBOX_CAPACITY=20 \
  messaging-service
```

**Use another port on your machine** by changing the left number: `-p 8080:9090` listens on 8080 on your machine and forwards to the server's port 9090 inside the container. The `-p` flag only forwards traffic; it does not change the port the server listens on. To change that, set `-e MESSAGING_TCP_PORT=9091` and forward to it with `-p 9091:9091`.

About the image:
- It is built in two stages: the first compiles the jar with Gradle on JDK 21, the second copies only the jar onto a small Java 21 runtime image. The server runs as a non-root user.
- The image build skips the tests to stay fast. Run them with `./gradlew test`.
- Messages are kept in memory only, so they are lost when the container stops.
- To get a smaller or newer image later, change the two `FROM` lines in the `Dockerfile`.

## Run the tests

```bash
./gradlew test
```

That one command runs everything. It takes a few seconds and prints one line per test, then a summary:

```
Sending and receiving messages > Acknowledge: the recipient confirms a message (ACK_ACCEPTED) PASSED
...
Tests: 37   passed: 37   failed: 0   skipped: 0
```

### What the tests do

Each test starts the real server on a free port and connects to it with a plain socket, the same as `nc`. It sends JSON lines and checks the reply the server sends back. So the tests run the real server code (connection handling, registry, mailboxes) and look at the real messages on the wire.

- Every test has a 10 second limit, and every socket read has a 3 second limit, so a problem fails fast and never hangs the build.
- The tests never wait a fixed time for the server. When a test must wait for the server to notice that a client left, it retries until the server answers, for at most 3 seconds.
- Each test uses its own server, so tests do not affect each other, and a server already running on port 9090 does not matter.
- Settings for the tests are in `src/test/resources/application.properties`. They are separate from the settings you run the server with.

### What is tested

| Test class | Runs | What it covers |
|---|---|---|
| `RegistrationTest` | 11 | A client registers. Several clients register. Duplicate client (`DUPLICATE_CLIENT`). Invalid IDs, 6 cases (`INVALID_CLIENT_ID`). With a limit of 4, the 5th client gets `SERVER_FULL` and the 5th connection gets `SERVER_BUSY`. |
| `MessagingTest` | 21 | Send is accepted. The recipient receives the message. A fake sender is ignored. Duplicate message ID (`DUPLICATE_MESSAGE`), and reuse of an ID after the ack. Acknowledge, and the next message waits for the ack. Long messages: a message exactly at the size limit is accepted; one character over, and 100,000 characters, get `MESSAGE_TOO_LARGE` and the connection is closed, without affecting other clients. Errors: unknown recipient, 4 kinds of invalid message, not registered, mailbox full, unknown ack, repeated ack, and an ack from the wrong client. |
| `OfflineAndReconnectTest` | 4 | A message sent while the recipient is offline is delivered when he returns. A message delivered but not acknowledged is delivered again after a reconnect. An acknowledged message is not delivered again. Offline messages keep their order. |
| `MessagingServiceApplicationTests` | 1 | The whole Spring application starts with its real wiring (on a free port). |

### What is not tested

- Heavy load and many clients at once. Such tests depend on timing, so they would not be reliable.
- The idle timeout and shutdown.
- Real network failures. Tests close a connection, but they do not simulate packet loss.

The manual scenarios further down (checked by hand with `nc`) include some of these.

## Connect

Use `nc` (netcat), one terminal per client:

```bash
nc localhost 9090
```

Every message is **one line of JSON**. Paste it and press Enter.

## Messages

**You send:**

| Type | Example |
|---|---|
| Register | `{"type":"REGISTER","clientId":"John"}` |
| Send | `{"type":"SEND","messageId":"j1","recipientId":"Ravi","payload":"Hi!"}` |
| Acknowledge | `{"type":"ACK","messageId":"j1"}` |

**The server replies:**

| Type | Meaning |
|---|---|
| `REGISTERED` | Registration worked |
| `SEND_ACCEPTED` | Your message was accepted and stored |
| `DELIVER` | A message for you (acknowledge it) |
| `ACK_ACCEPTED` | Your acknowledgement was accepted |
| `ERROR` | Something was rejected. The error code is in `payload`. |

## How it works

- IDs and message IDs are 1 to 64 characters: letters, digits, `.`, `_` or `-`. IDs are case sensitive (`ravi` and `Ravi` are different clients).
- The sender is always the registered client. A `senderId` in your message is ignored.
- Each client has a mailbox. Messages stay there until the recipient acknowledges them.
- Only **one message is in flight per recipient**. The next message is delivered after the previous one is acknowledged. So messages arrive in the order they were sent (FIFO).
- `SEND_ACCEPTED` means the server stored the message. It does not mean the recipient has received it yet.
- A message that was delivered but not acknowledged is delivered again after the client reconnects. Clients should ignore a `messageId` they have already seen.
- A client that disconnects stays known. Registering again with the same ID gives back its mailbox.
- A connection that sends nothing for 5 minutes is closed.

## Settings

In `src/main/resources/application.properties`:

| Setting | Default | Meaning |
|---|---|---|
| `messaging.tcp.port` | 9090 | Port |
| `messaging.tcp.max-connections` | 100 | Active connections |
| `messaging.tcp.max-clients` | 1000 | Different client IDs the server will remember |
| `messaging.tcp.mailbox-capacity` | 100 | Messages kept per client |
| `messaging.tcp.max-message-size` | 4096 | Longest line in characters |
| `messaging.tcp.outbound-queue-capacity` | 100 | Messages queued for one slow client before it is dropped |
| `messaging.tcp.idle-timeout-millis` | 300000 | Close a silent connection after this long (5 minutes) |

To try a setting without editing the file:

```bash
./gradlew bootRun --args='--messaging.tcp.mailbox-capacity=3'
```

## Error codes

| Code | Meaning |
|---|---|
| `INVALID_JSON` | The line is not a JSON object. Send one JSON object per line. |
| `UNSUPPORTED_OPERATION` | Unknown `type` |
| `NOT_REGISTERED` | You sent a message or ack before registering |
| `INVALID_CLIENT_ID` | The ID is empty, too long, or has bad characters |
| `ALREADY_REGISTERED` | You registered twice on one connection |
| `DUPLICATE_CLIENT` | That ID is already connected |
| `SERVER_FULL` | Too many different client IDs |
| `SERVER_BUSY` | Too many active connections |
| `INVALID_RECIPIENT` | The recipient is empty or has never registered |
| `INVALID_MESSAGE` | Bad `messageId` or empty payload |
| `MESSAGE_TOO_LARGE` | The line or payload is too long. The connection is closed. |
| `DUPLICATE_MESSAGE` | You already have a message with this `messageId` waiting |
| `MAILBOX_FULL` | The recipient's mailbox is full |
| `INVALID_ACK` | Not the message waiting for an ack, or already acknowledged |

## Example chat

Open two terminals and register:

- Terminal 1: `{"type":"REGISTER","clientId":"John"}`
- Terminal 2: `{"type":"REGISTER","clientId":"Ravi"}`

Use a **different `messageId` for every message**.

| Step | Terminal | Paste | What happens |
|---|---|---|---|
| 1 | John | `{"type":"SEND","messageId":"j1","recipientId":"Ravi","payload":"Hi!! How are you?"}` | Ravi receives it |
| 2 | John | `{"type":"SEND","messageId":"j2","recipientId":"Ravi","payload":"Any plans for today?"}` | Accepted, but waits for Ravi to acknowledge `j1` |
| 3 | Ravi | `{"type":"ACK","messageId":"j1"}` | `j1` is acknowledged, then `j2` arrives |
| 4 | Ravi | `{"type":"SEND","messageId":"r1","recipientId":"John","payload":"Not too bad, thanks"}` | John receives it |
| 5 | John | `{"type":"ACK","messageId":"r1"}` | `r1` is acknowledged |
| 6 | Ravi | `{"type":"ACK","messageId":"j2"}` | `j2` is acknowledged |

## Manual test scenarios: acknowledgements

Try these by hand with `nc`. Some of them are also in the automated tests above (`MessagingTest` and `OfflineAndReconnectTest`).

Registered clients: John and Ravi. John sends to Ravi unless stated.

### Normal acknowledgements

| # | Scenario | Steps | Expected |
|---|---|---|---|
| A1 | Ack a delivered message | John sends `j1`. Ravi sends `{"type":"ACK","messageId":"j1"}` | `ACK_ACCEPTED` |
| A2 | Ack delivers the next message | John sends `j1`, `j2`, `j3`. Ravi acks `j1` | `ACK_ACCEPTED`, then `DELIVER` `j2` |
| A3 | Acks in both directions | John sends `j1` and Ravi acks it. Ravi sends `r1` and John acks it. | Both `ACK_ACCEPTED` |

### Rejected acknowledgements

| # | Scenario | Steps | Expected |
|---|---|---|---|
| A4 | Out of order | John sends `j1`, `j2`. Ravi acks `j2` | `INVALID_ACK` |
| A5 | Same ack twice | Ravi acks `j1`, then acks `j1` again | Second one: `INVALID_ACK` |
| A6 | Unknown message | Ravi acks `nope` | `INVALID_ACK` |
| A7 | No message ID | Ravi sends `{"type":"ACK"}` | `INVALID_ACK` |
| A8 | Nothing waiting | Ravi acks `j1` with an empty mailbox | `INVALID_ACK` |
| A9 | Wrong client | John sends `j1` to Ravi. John acks `j1` | `INVALID_ACK`, `j1` stays pending |
| A10 | Not registered | A new terminal sends an ACK without registering | `NOT_REGISTERED` |

### Acknowledgements and disconnects

| # | Scenario | Steps | Expected |
|---|---|---|---|
| A11 | Delivered, not acked, reconnect | Ravi gets `j1` and does not ack. Ravi disconnects and registers again | `DELIVER` `j1` again |
| A12 | Ack after reconnect | Continue A11: Ravi acks `j1` | `ACK_ACCEPTED`. `j1` is not delivered again. |
| A13 | Offline messages | Ravi is offline. John sends `j1`, `j2`. Ravi registers | `DELIVER` `j1`. Ack it to get `j2`. |

### Acknowledgements and message IDs

| # | Scenario | Steps | Expected |
|---|---|---|---|
| A14 | Reuse an ID while waiting | John sends `j1`. Ravi has not acked. John sends `j1` again | `DUPLICATE_MESSAGE` |
| A15 | Reuse an ID after the ack | Ravi acks `j1`. John sends `j1` again | `SEND_ACCEPTED` (treated as a new message) |
| A16 | Same ID from both people | John sends `s1` to Ravi. Ravi sends `s1` to John | Both accepted |

### Order and limits

| # | Scenario | Steps | Expected |
|---|---|---|---|
| A17 | Strict order | John sends `j1`, `j2`, `j3`. Ravi acks one at a time | Delivered `j1`, `j2`, `j3` |
| A18 | One at a time | John sends `j1` then `j2`. Ravi acks nothing | Ravi sees only `j1`, and it is not sent twice |
| A19 | Space after an ack | Mailbox is full (`MAILBOX_FULL`). Ravi acks the first message | John's next send is accepted |

## Other things to try

| Scenario | Paste | Expected |
|---|---|---|
| Duplicate client | Register `John` in a second terminal | `DUPLICATE_CLIENT` |
| Invalid ID | `{"type":"REGISTER","clientId":"bad id!"}` | `INVALID_CLIENT_ID` |
| Unknown recipient | `{"type":"SEND","messageId":"x1","recipientId":"nobody","payload":"hi"}` | `INVALID_RECIPIENT` |
| Bad JSON | `hello` | `INVALID_JSON` (the connection stays open) |
| Multi-line JSON | Paste a message split over several lines | `INVALID_JSON` for each line. Keep each message on one line. |
| Too long | `python3 -c "print('x'*5000)" \| nc localhost 9090` | `MESSAGE_TOO_LARGE`, then closed |
| Unknown operation | `{"type":"WHAT"}` | `UNSUPPORTED_OPERATION` |

## Project layout

```
src/main/java/com/messaging/
  server/     TCP server, one handler per connection, client registry
  service/    Messaging rules, mailboxes
  model/      Message, client session
  protocol/   The JSON message format
  constant/   Message types and error codes
  config/     Settings

Dockerfile      Builds the runnable image (see "Run it with Docker")
.dockerignore   Files kept out of the Docker build

src/test/
  java/com/messaging/MessagingServiceApplicationTests   The application starts (Spring wiring)
  java/com/messaging/server/
    ServerTestSupport        Starts the server and gives tests a socket client
    RegistrationTest         Registration and the client and connection limits
    MessagingTest            Sending, receiving, duplicates, acknowledgements, errors
    OfflineAndReconnectTest  Offline messages, redelivery, order
  resources/application.properties   Settings for the tests
```
