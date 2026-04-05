package client;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Buffer de pedidos com 1 thread worker dedicada.
 * Minimiza threads acordadas (apenas 1 worker thread).
 */
public class RequestBuffer {
    private final Queue<Runnable> taskQueue;
    private final ReentrantLock lock;
    private final Condition notEmpty;
    private final Thread workerThread;
    private volatile boolean shutdown;

    public RequestBuffer() {
        this.taskQueue = new LinkedList<>();
        this.lock = new ReentrantLock();
        this.notEmpty = lock.newCondition();
        this.shutdown = false;
        this.workerThread = new Thread(this::runWorker, "Client-Worker");
        this.workerThread.start();
    }

    /**
     * Submete uma tarefa para execução assíncrona.
     */
    public void submit(Runnable task) {
        lock.lock();
        try {
            if (shutdown) {
                throw new IllegalStateException("RequestBuffer foi encerrado");
            }
            taskQueue.add(task);
            notEmpty.signal();  // Acordar worker thread
        } finally {
            lock.unlock();
        }
    }

    /**
     * Encerra o buffer e aguarda worker thread terminar.
     */
    public void shutdown() {
        lock.lock();
        try {
            shutdown = true;
            notEmpty.signal();  // Acordar worker para terminar
        } finally {
            lock.unlock();
        }

        // Esperar worker thread terminar
        try {
            workerThread.join();
        } catch (InterruptedException e) {
            // Ignorar interrupção
        }
    }

    /**
     * Loop da worker thread - processa tarefas do buffer.
     */
    private void runWorker() {
        while (true) {
            Runnable task = null;

            lock.lock();
            try {
                // Esperar até haver tarefa ou shutdown
                while (taskQueue.isEmpty() && !shutdown) {
                    notEmpty.await();
                }

                // Se shutdown e queue vazia, terminar
                if (shutdown && taskQueue.isEmpty()) {
                    break;
                }

                // Obter tarefa da queue
                task = taskQueue.poll();
            } catch (InterruptedException e) {
                // Interrompida - terminar thread
                break;
            } finally {
                lock.unlock();
            }

            // Executar tarefa fora do lock
            if (task != null) {
                try {
                    task.run();
                } catch (Exception e) {
                    System.err.println("[RequestBuffer] Erro ao executar tarefa: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }
    }
}
