# Approach

This document explains the protocol, the connection lifecycle, the behaviour rules, and the reasons behind the main design choices. For how to run and test the service, see `README.md`.

## 1. Overview

The service is a TCP server. Clients connect, register with a name, send messages to other clients by name, and acknowledge the messages they receive.

- Each client has a **mailbox** on the server. A message stays in the recipient's mailbox until the recipient acknowledges it.
- If the recipient is online, the message is pushed to it. If it is offline, the message waits.
- A client can disconnect and register again with the same name. It gets the same mailbox back.

```
 client A ──┐                         ┌── mailbox(A)
            ├── TCP ── server ────────┤
 client B ──┘   (one thread per       └── mailbox(B)
                 connection)
```

### 1.1 High-level architecture

```
  John                                                    Ravi
   │        one line of JSON over TCP, per message         │
   └──────────────────────────┬────────────────────────────┘
                              ▼
┌───────────────────────────────────────────────────────────────┐
│ TcpMessagingServer                                            │
│   accepts connections, enforces max-connections (SERVER_BUSY) │
│   starts one ClientConnectionHandler for each connection      │
└───────────────────────────────┬───────────────────────────────┘
                                ▼
┌───────────────────────────────────────────────────────────────┐
│ ClientConnectionHandler            (one per connection)       │
│   reads a line (BoundedLineReader), parses the JSON,          │
│   runs the operation, sends the reply                         │
└──────────┬───────────────────────────────────┬────────────────┘
           │ REGISTER                          │ SEND / ACK
           ▼                                   ▼
┌─────────────────────────┐        ┌───────────────────────────────┐
│ ClientRegistry          │        │ MessagingService              │
│   every known client,   │◄───────│   validates a send, applies   │
│   online or offline     │ is the │   the rules, calls the mailbox│
│   duplicate check,      │ sender │   (only the rules live here)  │
│   max-clients           │ online?└───────────────┬───────────────┘
└────────────┬────────────┘                        ▼
             ▼                     ┌───────────────────────────────┐
┌─────────────────────────┐        │ MailboxService  →  Mailbox    │
│ ClientSession           │        │   one Mailbox per client      │
│   connection state      │        │   FIFO queue, capacity,       │
│   outbound queue        │        │   duplicate check, in-flight  │
│   writer thread         │        │   flag, acknowledge           │
└────────────┬────────────┘        └───────────────────────────────┘
             ▼
   replies and DELIVER lines written to the client's socket
```

What each part is responsible for:

| Part | Responsibility | Holds state? |
|---|---|---|
| `TcpMessagingServer` | Accept connections, connection limit, shutdown | Open sockets, connection permits |
| `ClientConnectionHandler` | Speak the protocol for one connection | Only that connection (its session) |
| `ClientRegistry` | Who exists, who is online, who may register | All client sessions, client count |
| `ClientSession` | One client's current connection and its outgoing lines | Socket, outbound queue, writer thread |
| `MessagingService` | The rules for sending and acknowledging | None |
| `MailboxService` / `Mailbox` | The messages waiting for each client | Messages, in-flight flag |

The handler is the only part that knows the protocol. The services work with plain Java values, so the same core could sit behind a different front end (for example REST).

### 1.2 How it works

**Register**
```
John ─ REGISTER ─► ClientConnectionHandler
                     ├─► MessagingService.isValidId         bad ID        → INVALID_CLIENT_ID
                     ├─► ClientRegistry.connect
                     │      new client, room left            → create ClientSession
                     │      known client, offline            → reattach (mailbox kept)
                     │      known client, online             → DUPLICATE_CLIENT
                     │      new client, cap reached          → SERVER_FULL
                     ├─► ClientSession.attach               starts John's writer thread
                     ├─► reply REGISTERED                   (through John's outbound queue)
                     └─► deliver anything already waiting for John
```

**Send and deliver**
```
John ─ SEND to Ravi ─► ClientConnectionHandler (John's)
                         ├─► MessagingService.sendMessage
                         │      checks the sender is online      (ClientRegistry)
                         │      checks recipient, IDs, size      → INVALID_RECIPIENT, INVALID_MESSAGE, ...
                         ├─► Mailbox(Ravi).storeMessage
                         │      duplicate ID                     → DUPLICATE_MESSAGE
                         │      mailbox at capacity              → MAILBOX_FULL
                         │      otherwise append to the queue
                         ├─► reply SEND_ACCEPTED to John
                         └─► deliver to Ravi:
                                ClientRegistry: is Ravi online?   no → stop, message stays queued
                                Mailbox(Ravi).claimNextForDelivery
                                   head message already in flight → nothing to send
                                   otherwise mark it in flight
                                Ravi's ClientSession.send(DELIVER)  → outbound queue
                                Ravi's writer thread                → Ravi's socket
```

**Acknowledge**
```
Ravi ─ ACK j1 ─► ClientConnectionHandler (Ravi's)
                   ├─► Mailbox(Ravi).acknowledgeMessage
                   │      j1 is the head message               → remove it, clear in-flight
                   │      anything else                        → INVALID_ACK
                   ├─► reply ACK_ACCEPTED
                   └─► deliver the next message (same as the last step of "Send")
```

**A connection closes** (client left, network error, idle for 5 minutes, or server stopping)
```
ClientConnectionHandler.cleanUp
   ├─► ClientRegistry.unregister → ClientSession.disconnect
   │      drops the socket and queue, stops the writer thread
   │      (the ClientSession and its Mailbox stay, only "offline")
   ├─► clear the in-flight flag    so the unacknowledged message is sent again later
   └─► close the socket, free the connection slot

later: Ravi ─ REGISTER ─► ClientRegistry reattaches the same ClientSession
                          → Ravi gets his waiting messages in order
```

**Threads**

| Thread | How many | What it does |
|---|---|---|
| Accept thread | 1 | Accepts connections |
| Handler thread | 1 per connection, at most `max-connections` | Reads and handles that client's requests |
| Writer thread | 1 per online client | Writes that client's queued lines to its socket |

A handler never writes to another client's socket. It only puts a line on that client's outbound queue, and the writer thread sends it. This is why one slow client cannot block anyone else.

## 2. Protocol

### 2.1 Transport and framing

- Transport: TCP, port 9090 by default.
- Framing: **one JSON object per line**, UTF-8, ending in `\n`. Every request and every reply is one line.
- A line longer than `max-message-size` (4096 characters) is rejected with `MESSAGE_TOO_LARGE` and the connection is closed.

Why line-delimited JSON: it is easy to read and to test with `nc`, and it can be read with a size limit, so a client cannot make the server buffer unlimited data. A bad line does not corrupt the lines after it.

A consequence: a message split over several lines is not valid. Each line is rejected with `INVALID_JSON`. To put a newline inside a payload, write `\n` inside the JSON string.

### 2.2 Message shape

Every message, in both directions, has the same fields. Fields that do not apply are `null`.

```json
{
  "type": "...",
  "clientId": "...",
  "messageId": "...",
  "senderId": "...",
  "recipientId": "...",
  "payload": "..."
}
```

For an `ERROR` reply, the error code is in `payload`.

### 2.3 Operations

Client to server:

| Type | Fields used | Purpose |
|---|---|---|
| `REGISTER` | `clientId` | Identify this connection as a client |
| `SEND` | `messageId`, `recipientId`, `payload` | Send a message to another client |
| `ACK` | `messageId` | Confirm a received message |

Server to client:

| Type | Meaning |
|---|---|
| `REGISTERED` | Registration succeeded |
| `SEND_ACCEPTED` | The message was accepted and stored in the recipient's mailbox |
| `DELIVER` | A message for this client. It must be acknowledged. |
| `ACK_ACCEPTED` | The acknowledgement was accepted and the message was removed |
| `ERROR` | The request was rejected (`payload` is the error code) |

### 2.4 Identity and validation

- A client identifies itself with `REGISTER`. After that, the server uses that identity for everything on the connection.
- The **sender is always the registered client**. A `clientId` or `senderId` in a `SEND` is ignored, so a client cannot pretend to be someone else.
- The **recipient of an ack is always the registered client**, so a client can only acknowledge messages from its own mailbox.
- IDs (client and message) must match `[A-Za-z0-9._-]{1,64}`. They are case sensitive.
- A payload must not be empty and must not exceed `max-message-size`.
- A recipient must have registered at least once, otherwise `INVALID_RECIPIENT`.

### 2.5 Error codes

| Code | When |
|---|---|
| `INVALID_JSON` | The line is not a JSON object |
| `UNSUPPORTED_OPERATION` | Unknown `type` |
| `NOT_REGISTERED` | `SEND` or `ACK` before `REGISTER` |
| `INVALID_CLIENT_ID` | Client ID is empty, too long, or has bad characters |
| `ALREADY_REGISTERED` | A second `REGISTER` on the same connection |
| `DUPLICATE_CLIENT` | The ID already has a live connection |
| `SERVER_FULL` | The number of different client IDs has reached `max-clients` |
| `SERVER_BUSY` | The number of active connections has reached `max-connections` |
| `INVALID_RECIPIENT` | Recipient is empty or has never registered |
| `INVALID_MESSAGE` | Bad `messageId` or empty payload |
| `MESSAGE_TOO_LARGE` | Line or payload is longer than `max-message-size` |
| `DUPLICATE_MESSAGE` | A message with this ID from this sender is still waiting |
| `MAILBOX_FULL` | The recipient's mailbox is at capacity |
| `INVALID_ACK` | The ack does not match the message waiting for one |

A bad request gets an `ERROR` reply and the connection stays open. Only an oversized line, an idle timeout, or a slow client closes it.

## 3. Connection lifecycle

```
connect ──► (unregistered) ──REGISTER──► registered ──close / idle / error──► offline
                │                            │                                  │
                │ SEND/ACK → NOT_REGISTERED  │ SEND, ACK, DELIVER               │ same ID can
                                                                                  │ REGISTER again
```

### Connect
The server accepts the connection if fewer than `max-connections` are active. Otherwise it replies `SERVER_BUSY` and closes the connection.

### Register
1. The client sends `{"type":"REGISTER","clientId":"John"}`.
2. If the ID is new and there is room (`max-clients`), a new client is created with an empty mailbox.
3. If the ID is known and **offline**, the connection is attached to the existing client. Its mailbox is untouched.
4. If the ID is known and **online**, the request is refused with `DUPLICATE_CLIENT`. The existing connection is not replaced.
5. On success the server replies `REGISTERED` and then delivers the first waiting message, if any.

This is how a client reconnects: it opens a new connection and sends `REGISTER` with the same ID. No token or session ID is used. (See "Limitations".)

### Send
1. The server checks the sender is registered, the recipient exists, the IDs and payload are valid, and the message is not a duplicate.
2. It adds the message to the end of the recipient's mailbox. If the mailbox is full, the reply is `MAILBOX_FULL`.
3. It replies `SEND_ACCEPTED` to the sender. This means "stored", not "received".
4. If the recipient is online and nothing is waiting for an ack, the message is delivered now.

### Deliver and acknowledge
1. The server sends `DELIVER` to the recipient. The message is now **in flight**: sent, waiting for an ack. It stays in the mailbox.
2. The recipient sends `{"type":"ACK","messageId":"..."}`.
3. If the ack matches the message at the front of the mailbox, the message is removed, the server replies `ACK_ACCEPTED`, and the next message is delivered.
4. Otherwise the reply is `INVALID_ACK` and nothing changes.

### Connection closes
When a connection ends (the client closes it, the network fails, the server times it out, or the server shuts down):
- The client is marked **offline**. It is **not** deleted.
- Its mailbox is kept, including messages that were delivered but not acknowledged.
- The in-flight mark is cleared, so the first unacknowledged message will be delivered again on the next connection.
- The connection slot is freed.
- Messages sent to it later are accepted and stored (up to the mailbox limit).

When the client registers again, it receives its waiting messages in order, starting with any that were delivered before but never acknowledged.

## 4. Delivery rules

### Delivery guarantee: at least once
A message is removed only when its recipient acknowledges it. If the recipient disconnects before acknowledging, the message is sent again after it reconnects. So a message may be delivered **more than once**. Clients should ignore a `messageId` they have already processed.

A duplicate can also happen if a client disconnects just after sending an ack that the server has not yet processed.

### Ordering: first in, first out
- Messages for one recipient are delivered in the order the server accepted them.
- Only **one message is in flight** per recipient. The next one is sent only after the previous one is acknowledged.
- Because of this, `SEND_ACCEPTED` does not mean the recipient has the message. A message behind an unacknowledged one waits.
- Ordering between different recipients, or between different senders to one recipient, is the order in which the server accepted them.

### Duplicate sends
- A message is identified by (sender, `messageId`).
- If the same sender sends the same `messageId` to the same recipient while the first is still waiting in the mailbox, the second is rejected with `DUPLICATE_MESSAGE`.
- Once the first message has been acknowledged, the `messageId` can be used again. It is treated as a new message. The server does not remember acknowledged messages.
- Two different senders can use the same `messageId`. Both are accepted.

### Repeated and stale acknowledgements
An ack is accepted only if all of these are true:
- the client is registered,
- the mailbox is not empty,
- the `messageId` matches the message at the **front** of that client's own mailbox.

Everything else gets `INVALID_ACK` and changes nothing. That covers:
- acking the same message twice (the second ack is stale),
- acking a message that is behind the front one (out of order),
- acking an unknown message or an empty `messageId`,
- acking a message that belongs to someone else.

Rejecting a repeated ack is safe. The first ack already did the work.

## 5. Limits and how they are reported

Everything is bounded. When a limit is reached, the server replies with an error (or closes the connection) and keeps running.

| What | Limit (setting) | When exceeded |
|---|---|---|
| Messages per mailbox | `mailbox-capacity` (100) | `MAILBOX_FULL` on `SEND` |
| Line and payload size | `max-message-size` (4096 chars) | `MESSAGE_TOO_LARGE`, connection closed |
| Active connections | `max-connections` (100) | `SERVER_BUSY`, connection closed |
| Distinct client IDs | `max-clients` (1000) | `SERVER_FULL` on `REGISTER` of a new ID |
| ID length | 64 characters | `INVALID_CLIENT_ID` / `INVALID_MESSAGE` |
| Output queued per client | `outbound-queue-capacity` (100) | The slow client is disconnected |
| Silence on a connection | `idle-timeout-millis` (5 min) | Connection closed |

Known clients can always reconnect, even when `max-clients` is reached. The cap only applies to new IDs.

Memory is bounded by roughly `max-clients × mailbox-capacity × max-message-size`.

## 6. Concurrency

Each connection has its own thread, so clients are handled in parallel. The things they share are protected like this:

| Shared state | How it is protected | Why |
|---|---|---|
| Client registry | `ConcurrentHashMap`, changed with `compute()` | `compute()` is atomic per ID, so two simultaneous `REGISTER`s for one ID cannot both win. |
| Client count (`max-clients`) | `AtomicInteger` with compare-and-set | Many new IDs registering at once cannot exceed the cap. |
| A mailbox | `synchronized` on that mailbox only | One lock per recipient, so different mailboxes never wait on each other. Store, claim and ack are each one atomic step. |
| In-flight flag | Inside the mailbox lock | If many threads try to deliver at once, exactly one claims the message. |
| A client's connection state | `synchronized` attach/detach, `volatile` fields | A client can only have one live connection, and an old connection cannot detach a newer one. |
| Active connection count | `Semaphore` | One permit per live connection. Extra connections are refused. |

### Slow clients
Writing to a socket can block if the client is not reading. To keep that away from other clients:
- Sending to a client only puts a line into a **bounded queue** and returns at once.
- One **writer thread per connection** takes lines from the queue and writes them to the socket.
- If the queue fills up, the client is too slow. Its connection is closed. Its messages stay in the mailbox and are delivered when it reconnects.

So a slow recipient never blocks the sender or any other client, and only one thread ever writes to a given socket.

No lock is held while writing to the network, and no code takes two locks at once, so deadlock is not possible.

## 7. Shutdown

On stop, the server:
1. stops accepting connections,
2. closes every open client socket (this wakes the threads that are waiting to read),
3. stops the thread pool and waits up to 5 seconds for the handlers to finish.

Each handler removes its client's live connection on the way out. Mailboxes are in memory only, so they are lost when the process exits.

## 8. Design choices

| Choice | Reason | Trade-off |
|---|---|---|
| One message in flight per recipient | Simple and gives strict order, and makes redelivery easy to reason about | Slower: each message waits for the previous ack. A window of N unacked messages would be faster but more complex. |
| Line-delimited JSON | Human readable, easy to test, easy to bound | Not suited to binary data. No multi-line messages. |
| Thread per connection | Simple, blocking code that is easy to follow | Does not scale to very many connections. The connection cap keeps it safe. |
| Clients are never removed | A disconnected client must keep its mailbox | The registry can only grow, so it has a cap. |
| At-least-once delivery | Never loses a message | Clients may see a message twice |
| Mailboxes in memory | Simple, fast | Lost on restart |
| Register by name only | Simple, matches the exercise | Anyone can use any name (see below) |

## 9. Limitations

- **No authentication.** Anyone who knows a name can register as that client. While the real client is offline, someone else could register as it and read its messages.
- **Messages are not stored on disk.** A server restart loses all mailboxes.
- **Possible duplicate on reconnect.** In rare timing cases a message can be delivered twice around a reconnect. This is allowed by at-least-once delivery.
- **Delivery and registration can interleave.** A `DELIVER` can arrive in a connection just before or after `REGISTERED`. Clients should handle `DELIVER` at any time.
- **No heartbeat.** A client that dies without closing its connection keeps its ID until the idle timeout (5 minutes) closes it. A client that is only listening is also disconnected after 5 minutes of silence and must reconnect.
- **Throughput.** One message in flight per recipient limits the delivery rate.
- **A single server.** Nothing is shared between servers.

## 10. What I would do for production

- Authenticate clients (for example with tokens) instead of trusting a name.
- Store mailboxes in a database so they survive restarts and can be shared between servers.
- Run several servers behind a load balancer, with a shared lookup of which server holds which client, and route messages between servers.
- Add a heartbeat so dead connections are found sooner.
- Allow a window of several unacknowledged messages for more throughput, using sequence numbers to keep order.
- Add a REST or WebSocket front end that uses the same messaging core.
- Add metrics (connections, mailbox sizes, delivery time, error counts), logging, and rate limiting per client.

## 11. AI tool usage

**Tool:** Claude Code (Anthropic's command-line assistant), used inside VS Code, with the Claude Sonnet 5.5 model.

**What I did before using the tool:** I wrote the first version myself: client registration and reconnection, the duplicate-client check, input validation, and the FIFO mailbox with acknowledgements (see the early commits in the git history).

**What I used the tool for:**
- Reviewing my code against the exercise brief and listing the gaps (for example: the same unacknowledged message being sent again, no size limit on a line, no cap on connections or clients, an unclear shutdown).
- Implementing fixes for those gaps: the in-flight flag, the bounded line reader and ID validation, the per-client outbound queue with a writer thread, the connection limit (`SERVER_BUSY`), the client limit (`SERVER_FULL`), the idle timeout, and closing sockets on shutdown.
- Restructuring `ClientConnectionHandler` so it is easier to read.
- Writing the automated tests and the first drafts of `README.md` and this file.
- Explaining concurrency and system design ideas so I could prepare to discuss them.

**What I decided and checked myself:**
- I chose to keep the design simple: no heartbeat, only a configurable idle timeout (5 minutes by default), one message in flight per recipient, and no authentication.
- I kept the test suite small and focused on the documented requirements, and I turned down a larger stress-test suite because it would not be deterministic.
- I rejected a version of the tests that started the whole Spring application and went back to building the server directly in the test, because it is faster and simpler.
- I ran the full test suite many times, and I ran the real server and walked through the scenarios by hand with `nc`.
- I read the generated code, and I can explain each class and why it is there.

**Responsibility:** the AI output was a starting point. I reviewed and edited it, and I am responsible for the final code, tests and documents.

