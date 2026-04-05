package common;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Demultiplexador de mensagens etiquetadas.
 * Permite múltiplas threads esperarem respostas de tags diferentes em paralelo.
 * Thread-safe.
 */
public class Demultiplexer implements AutoCloseable {
    private final ReentrantLock lock = new ReentrantLock();

    private class Entry {
        Condition cond = lock.newCondition();
        ArrayDeque<byte[]> queue = new ArrayDeque<>();
    }

    private final TaggedConnection conn;
    private final Map<Integer, Entry> map = new HashMap<>();
    private IOException ioe = null; // Exceção propagada do listener

    private Entry get(int tag) {
        Entry e = map.get(tag);
        if (e == null) {
            e = new Entry();
            map.put(tag, e);
        }
        return e;
    }

    public Demultiplexer(TaggedConnection conn) {
        this.conn = conn;
    }

    /**
     * Inicia o thread listener que recebe frames e as demultiplexa por tag.
     */
    public void start() {
        new Thread(() -> {
            try {
                for (;;) {
                    TaggedConnection.Frame f = conn.receive();
                    lock.lock();
                    try {
                        Entry e = get(f.tag);
                        e.queue.add(f.data);
                        e.cond.signal();
                    } finally {
                        lock.unlock();
                    }
                }
            } catch (IOException ex) {
                this.ioe = ex;
                // Acordar todas as threads bloqueadas em receive
                lock.lock();
                try {
                    for (Entry e : map.values()) {
                        e.cond.signalAll();
                    }
                } finally {
                    lock.unlock();
                }
            }
        }, "Demultiplexer-Listener").start();
    }

    /**
     * Envia uma frame diretamente.
     */
    public void send(TaggedConnection.Frame frame) throws IOException {
        conn.send(frame);
    }

    /**
     * Envia uma mensagem com tag e dados.
     */
    public void send(int tag, byte[] data) throws IOException {
        conn.send(tag, data);
    }

    /**
     * Recebe a resposta para uma tag específica.
     * Bloqueia até haver uma resposta ou ocorrer erro.
     */
    public byte[] receive(int tag) throws IOException, InterruptedException {
        lock.lock();
        try {
            Entry e = get(tag);
            for (;;) {
                if (ioe != null) throw ioe;
                if (!e.queue.isEmpty()) {
                    byte[] data = e.queue.poll();
                    // Limpar entrada se não há mais esperas e queue vazia
                    if (e.queue.isEmpty()) {
                        map.remove(tag);
                    }
                    return data;
                }
                e.cond.await();
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() throws IOException {
        conn.close();
    }
}
