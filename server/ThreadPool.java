package server;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread pool manual sem usar ExecutorService.
 * Minimiza o número de threads acordadas através de reutilização.
 */
public class ThreadPool {
    private final Queue<Runnable> taskQueue;
    private final WorkerThread[] workers;
    private final ReentrantLock lock;
    private final Condition notEmpty;
    private volatile boolean shutdown;

    public ThreadPool(int numThreads) {
        this.taskQueue = new LinkedList<>();
        this.workers = new WorkerThread[numThreads];
        this.lock = new ReentrantLock();
        this.notEmpty = lock.newCondition();
        this.shutdown = false;

        // Criar e iniciar worker threads
        for (int i = 0; i < numThreads; i++) {
            workers[i] = new WorkerThread("Worker-" + i);
            workers[i].start();
        }
    }

    /**
     * Submete uma tarefa para execução assíncrona.
     */
    public void submit(Runnable task) {
        lock.lock();
        try {
            if (shutdown) {
                throw new IllegalStateException("ThreadPool foi encerrado");
            }
            taskQueue.add(task);
            notEmpty.signal();  // Acordar uma thread worker
        } finally {
            lock.unlock();
        }
    }

    /**
     * Encerra o thread pool.
     */
    public void shutdown() {
        lock.lock();
        try {
            shutdown = true;
            notEmpty.signalAll();  // Acordar todas as threads para terminarem
        } finally {
            lock.unlock();
        }

        // Esperar que todas as threads terminem
        for (WorkerThread worker : workers) {
            try {
                worker.join();
            } catch (InterruptedException e) {
                // Ignorar interrupção
            }
        }
    }

    /**
     * Thread worker que executa tarefas da queue.
     */
    private class WorkerThread extends Thread {
        public WorkerThread(String name) {
            super(name);
        }

        @Override
        public void run() {
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
                        System.err.println("[ThreadPool] Erro ao executar tarefa: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            }
        }
    }
}
