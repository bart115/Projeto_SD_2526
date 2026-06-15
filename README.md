# Distributed Time-Series Event System

A multi-client distributed server for real-time sales event processing and time-series analytics, built from scratch in Java over raw TCP sockets — no frameworks, no shortcuts.

> **Academic project** · Distributed Systems · University of Minho · 2025/26 · Grade: **16.3 / 20**

---

## What it does

Clients connect to a central server and submit **sales events** (product, quantity, price). The server organises events into **daily time-series**, persists them to disk, and answers analytical queries — all while handling multiple concurrent clients without data races or deadlocks.

Clients can also **subscribe to blocking notifications** and be woken up the moment a specific condition occurs:
- Two target products sold on the same day (simultaneous sales)
- N consecutive sales of the same product (streak detection)

---

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                        SERVER                           │
│                                                         │
│  ┌─────────────┐   ┌──────────────┐   ┌─────────────┐  │
│  │ Autenticador│   │ EventManager │   │SNotificacoes│  │
│  │  (auth +    │   │ (time-series │   │ (blocking   │  │
│  │   users)    │   │  + persist.) │   │  notify)    │  │
│  └─────────────┘   └──────┬───────┘   └──────┬──────┘  │
│                           │                  │          │
│                    ┌──────▼───────┐          │          │
│                    │  DaySeries   │          │          │
│                    │ (RWLock +    │          │          │
│                    │  lazy cache) │          │          │
│                    └──────────────┘          │          │
│                                              │          │
│  ┌───────────────────────────────────────────▼──────┐   │
│  │             ThreadPool (20 workers)              │   │
│  │         ReentrantLock + Condition queue          │   │
│  └──────────────────────────────────────────────────┘   │
│                                                         │
│  ┌──────────────────────────────────────────────────┐   │
│  │  ClientHandler × N  (one per connected client)  │   │
│  │  TaggedConnection · binary protocol · TCP        │   │
│  └──────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
          ▲  TCP / binary protocol  ▲
          │                         │
   ┌──────┴──────┐           ┌──────┴──────┐
   │   Client A  │    ...    │   Client N  │
   │ Demultiplexer           │ Demultiplexer
   │  (async tags)│           │  (async tags)│
   └─────────────┘           └─────────────┘
```

---

## Key technical concepts

### Custom Thread Pool
Implemented without `ExecutorService` — a fixed pool of 20 worker threads sharing a task queue protected by `ReentrantLock` and a `Condition` variable. Workers sleep when idle and are woken up exactly as needed, minimising unnecessary wake-ups.

### Tagged Binary Protocol
Every message over TCP carries an integer **tag** that links a request to its response. This allows multiple threads on the client to send requests concurrently and each block independently waiting for *their* reply — without mixing up responses.

### Async Demultiplexer
The client-side `Demultiplexer` runs a single listener thread that reads incoming frames and routes them into per-tag queues. Any client thread can call `receive(tag)` and block until its specific response arrives, enabling true parallel request/response over a single socket.

### ReadWriteLock on Time-Series
`DaySeries` uses `ReentrantReadWriteLock` — multiple clients can query historical data in parallel (read lock), while event ingestion requires exclusive access (write lock). This avoids the bottleneck of a single mutex for read-heavy workloads.

### Lazy Aggregation Cache
Query results (total quantity, total volume, average price, max price) are computed on first access and cached per product per day. Subsequent queries hit the cache directly. Cache is invalidated when new events arrive.

### Blocking Notifications
`SNotificacoes` implements two blocking patterns using `Condition` variables:
- `waitSimultaneous(p1, p2)` — blocks until both products are sold in the current day
- `waitConsecutive(n)` — blocks until any product has N consecutive sales

Threads are woken immediately when the condition becomes true, not polled.

### Day Persistence
When a day ends, the full `DaySeries` is serialised to disk. The server manages a bounded in-memory window of `S` series (S < D days), loading from disk on demand.

### Compact Event Serialisation
`Evento.serializeEventList` uses a **string table** — product names are stored once in a lookup table and referenced by index in the event list. This reduces wire size when many events share the same product names.

---

## Protocol operations

| Tag | Operation | Auth required |
|-----|-----------|---------------|
| 1 | Register user | No |
| 2 | Login | No |
| 3 | Add sale event | Yes |
| 4 | Advance to next day | Yes (admin) |
| 5 | Query total quantity sold | Yes |
| 6 | Query total volume (revenue) | Yes |
| 7 | Query average price | Yes |
| 8 | Query max price | Yes |
| 9 | Filter events by criteria | Yes |
| 10 | Subscribe: simultaneous sales | Yes |
| 11 | Subscribe: consecutive sales streak | Yes |

---

## Getting started

**Requirements:** Java 11+, Bash

```bash
# 1. Clone the repository
git clone https://github.com/bart115/Projeto_SD_2526.git
cd Projeto_SD_2526

# 2. Compile everything
chmod +x compile.sh && ./compile.sh

# 3. Start the server (default: port 12345, 3-day window, 2 series in memory)
java -cp build server.Server

# Or with custom config:
java -cp build server.Server --port 9000 --days 30 --series 10

# 4. In a separate terminal, launch the client UI
java -cp build client.Client_UI

# 5. Run the full concurrency test suite
chmod +x run_tests.sh && ./run_tests.sh
```

**Default admin credentials:** `tacus / tacus`

---

## Server configuration

| Flag | Default | Description |
|------|---------|-------------|
| `--port` / `-p` | 12345 | TCP port |
| `--days` / `-d` | 3 | Max days to retain (D) |
| `--series` / `-s` | 2 | Max series in memory (S < D) |
| `--data` | `./data` | Persistence directory |

---

## Concurrency test suite

`testes/Testes.java` covers:

- **Correctness under concurrency** — multiple clients writing simultaneously, results remain consistent
- **Lazy cache validity** — cache returns correct aggregates after concurrent writes
- **Deadlock freedom** — stress test with interleaved operations across many threads
- **Query consistency** — queries on historical days return stable results while current day is active
- **Thread pool under load** — high-volume task submission without starvation or queue corruption
- **Simultaneous notification** — blocking subscribers are correctly woken when condition is met
- **Multiple day-advance edge cases** — state transitions between days are atomic and correct

---

## Project structure

```
.
├── common/
│   ├── Demultiplexer.java      # Async tag-based message router
│   ├── Evento.java             # Immutable sale event + binary serialisation
│   ├── FramedConnection.java   # Length-prefixed TCP framing
│   ├── Protocolo.java          # Protocol constants
│   └── TaggedConnection.java   # Tagged frame send/receive
├── server/
│   ├── Server.java             # Entry point, accepts connections
│   ├── ClientHandler.java      # Per-client request dispatcher
│   ├── EventManager.java       # Time-series orchestrator + persistence
│   ├── DaySeries.java          # One day of events + RWLock + lazy cache
│   ├── ProductCache.java       # Cached aggregations per product
│   ├── SNotificacoes.java      # Blocking notification system
│   ├── Autenticador.java       # Thread-safe user auth
│   └── ThreadPool.java         # Custom worker pool (no ExecutorService)
├── client/
│   ├── Client.java             # API client with async demultiplexer
│   └── Client_UI.java          # Interactive terminal UI
├── testes/
│   └── Testes.java             # Concurrency test suite
├── compile.sh
├── run_tests.sh
└── clean.sh
```

---

## What I learned

Building this without any networking or concurrency frameworks forced me to understand the primitives directly:
- How thread pools actually work under the hood (condition variables, not busy-waiting)
- Why `ReadWriteLock` matters for read-heavy shared state
- How to design a binary protocol that supports async multiplexed request/response over a single connection
- The difference between a lock protecting a data structure and a condition variable coordinating between threads

---

## Author

**Gonçalo Freitas** · [@bart115](https://github.com/bart115) · [LinkedIn](https://linkedin.com/in/YOUR_USERNAME)
