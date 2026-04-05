package testes;

import client.Client;
import common.Evento;

import java.io.IOException;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Suite de testes de concorrência para o sistema de séries temporais.
 *
 * IMPORTANTE: Servidor deve estar a correr antes de executar os testes.
 *
 * Para executar:
 * 1. Iniciar servidor: java server.Server
 * 2. Executar testes: java testes.Testes
 */
public class Testes {
    private static final String HOST = "localhost";
    private static final int PORT = 12345;

    public static void main(String[] args) {
        System.out.println("=== Iniciando Testes de Concorrência ===\n");

        try {
            // Limpar estado inicial
            setupInicial();

            // Executar testes
            testCorrecaoConcorrencia();
            testCacheLazy();
            testSemDeadlock();
            testConsistenciaQueries();
            testThreadPoolCarga();
            testNotificacaoSimultanea();
            testEdgeCaseNewDayMultiplo();

            System.out.println("\n===Todos os testes passaram! ===");

        } catch (Exception e) {
            System.err.println("\n===Teste falhou: " + e.getMessage() + " ===");
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Setup inicial: criar utilizador admin e limpar estado
     */
    private static void setupInicial() throws Exception {
        System.out.println("[SETUP] Preparando ambiente de testes...");

        Client admin = new Client();
        try {
            admin.connect(HOST, PORT);

            boolean logged = false;

            // 1) Tentar login com tacus/tacus
            try {
                admin.login("tacus", "tacus");
                System.out.println("[SETUP] Login como admin bem‑sucedido");
                logged = true;
            } catch (Exception e) {
                System.out.println("[SETUP] Não foi possível login como admin: " + e.getMessage());
            }

            // 2) Se falhar, tentar com credenciais conhecidas bart/bart
            if (!logged) {
                try {
                    admin.login("bart", "bart"); //admin dava erro se usasse admin/admin aqui
                    System.out.println("[SETUP] Login como bart bem‑sucedido");
                    logged = true;
                } catch (Exception e) {
                    System.out.println("[SETUP] Não foi possível login como bart: " + e.getMessage());
                }
            }

            // 3) Se ainda não autenticado, tentar registar admin e fazer login
            if (!logged) {
                try {
                    admin.register("admin", "admin");
                    admin.login("admin", "admin");
                    System.out.println("[SETUP] Admin registado e login bem‑sucedido");
                    logged = true;
                } catch (Exception e) {
                    System.out.println("[SETUP] Falha ao registar/login admin: " + e.getMessage());
                    // Tentar registar/login bart como último recurso
                    try {
                        admin.register("bart", "bart");
                        admin.login("bart", "bart");
                        System.out.println("[SETUP] Bart registado e login bem‑sucedido");
                        logged = true;
                    } catch (Exception ex) {
                        throw new IOException("Não foi possível obter credenciais admin (tentadas: admin, bart)", ex);
                    }
                }
            }

            // 4) Só avançar dia se autenticado
            if (logged) {
                admin.newDay();
                System.out.println("[SETUP] Novo dia iniciado - estado limpo\n");
            } else {
                throw new IOException("Falha no setup inicial: usuário administrador não autenticado");
            }

        } finally {
            admin.close();
        }
    }

    /**
     * Teste 1: Correção sob Concorrência
     * Verifica que N clientes adicionando M eventos cada resulta em N×M eventos totais.
     */
    public static void testCorrecaoConcorrencia() throws Exception {
        System.out.println("[TESTE 1] Correção sob Concorrência");

        final int CLIENTES = 50;
        final int EVENTOS = 100;

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(CLIENTES);
        AtomicInteger erros = new AtomicInteger(0);

        // Criar clientes em paralelo
        for (int i = 0; i < CLIENTES; i++) {
            new Thread(() -> {
                Client c = null;
                try {
                    String userId = "user" + Thread.currentThread().getId();
                    c = new Client();
                    c.connect(HOST, PORT);
                    c.register(userId, userId);
                    c.login(userId, userId);

                    start.await(); // Esperar sinal

                    for (int j = 0; j < EVENTOS; j++) {
                        c.addEvent("ProdutoA", 1, 10.0f);
                    }

                } catch (Exception e) {
                    erros.incrementAndGet();
                    System.err.println("[ERRO] Thread " + Thread.currentThread().getId() + ": " + e.getMessage());
                } finally {
                    if (c != null) {
                        try { c.close(); } catch (IOException e) { }
                    }
                    done.countDown();
                }
            }).start();
        }

        start.countDown(); // Disparar todas as threads
        done.await(); // Esperar todas terminarem

        // Verificar resultado
        Client admin = new Client();
        try {
            admin.connect(HOST, PORT);
            admin.login("bart", "bart"); //dava erro se usasse admin/admin aqui
            admin.newDay(); // Fechar dia para consultar

            int total = admin.doQueryQuantity("ProdutoA", 1);
            int esperado = CLIENTES * EVENTOS;

            if (total != esperado) {
                throw new AssertionError("Total incorreto: esperado=" + esperado + ", obtido=" + total);
            }

            if (erros.get() > 0) {
                throw new AssertionError("Houve " + erros.get() + " erros durante execução");
            }

            System.out.println(" " + CLIENTES + " clientes × " + EVENTOS + " eventos = " + total + " total");

        } finally {
            admin.close();
        }
    }

    /**
     * Teste 2: Cache Lazy Thread-Safe
     * Verifica que múltiplas threads consultando simultaneamente recebem resultados consistentes.
     */
    public static void testCacheLazy() throws Exception {
        System.out.println("[TESTE 2] Cache Lazy Thread-Safe");

        final int THREADS = 100;

        // Adicionar eventos primeiro
        Client setup = new Client();
        try {
            setup.connect(HOST, PORT);
            setup.login("bart", "bart");

            for (int i = 0; i < 50; i++) {
                setup.addEvent("ProdutoB", 2, 5.0f);
            }
            setup.newDay(); // Fechar dia

        } finally {
            setup.close();
        }

        // Agora testar consultas concorrentes
        CyclicBarrier barrier = new CyclicBarrier(THREADS);
        CountDownLatch done = new CountDownLatch(THREADS);
        float[] results = new float[THREADS];
        AtomicInteger erros = new AtomicInteger(0);
        AtomicInteger idx = new AtomicInteger(0);

        for (int i = 0; i < THREADS; i++) {
            new Thread(() -> {
                Client c = null;
                try {
                    String userId = "reader" + Thread.currentThread().getId();
                    c = new Client();
                    c.connect(HOST, PORT);
                    c.register(userId, userId);
                    c.login(userId, userId);

                    barrier.await(); // Sincronizar todas as threads

                    int myIdx = idx.getAndIncrement();
                    results[myIdx] = c.doQueryVolume("ProdutoB", 1);

                } catch (Exception e) {
                    erros.incrementAndGet();
                    System.err.println("[ERRO] Thread " + Thread.currentThread().getId() + ": " + e.getMessage());
                } finally {
                    if (c != null) {
                        try { c.close(); } catch (IOException e) { }
                    }
                    done.countDown();
                }
            }).start();
        }

        done.await();

        // Verificar consistência
        float first = results[0];
        for (int i = 1; i < results.length; i++) {
            if (Math.abs(results[i] - first) > 0.001f) {
                throw new AssertionError("Resultados inconsistentes: results[0]=" + first + ", results[" + i + "]=" + results[i]);
            }
        }

        if (erros.get() > 0) {
            throw new AssertionError("Houve " + erros.get() + " erros durante execução");
        }

        System.out.println(" " + THREADS + " threads consultaram concorrentemente, todos receberam " + first);
    }

    /**
     * Teste 3: Ausência de Deadlock
     * Verifica que o sistema não trava sob carga mista de operações.
     */
    public static void testSemDeadlock() throws Exception {
        System.out.println("[TESTE 3] Ausência de Deadlock");

        long startTime = System.currentTimeMillis();
        final long TIMEOUT = 10_000; // 10 segundos
        final int NUM_THREADS = 100;

        Thread[] threads = new Thread[NUM_THREADS];
        AtomicInteger erros = new AtomicInteger(0);

        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                Client c = null;
                try {
                    String userId = "deadlock" + Thread.currentThread().getId();
                    c = new Client();
                    c.connect(HOST, PORT);
                    c.register(userId, userId);
                    c.login(userId, userId);

                    // Mix de operações
                    c.addEvent("ProdutoC", 1, 1.0f);
                    c.doQueryQuantity("ProdutoC", 1);
                    c.doQueryVolume("ProdutoC", 1);

                } catch (Exception e) {
                    erros.incrementAndGet();
                    System.err.println("[ERRO] Thread " + Thread.currentThread().getId() + ": " + e.getMessage());

                } finally {
                    if (c != null) {
                        try { c.close(); } catch (IOException e) { }
                    }
                }
            });
            threads[i].start();
        }

        // Esperar com timeout
        for (Thread t : threads) {
            long remaining = TIMEOUT - (System.currentTimeMillis() - startTime);
            if (remaining <= 0) {
                throw new AssertionError("Possível deadlock detectado - timeout excedido");
            }
            t.join(remaining);

            if (t.isAlive()) {
                throw new AssertionError("Thread ainda viva após timeout - possível deadlock");
            }
        }

        long duracao = System.currentTimeMillis() - startTime;
        System.out.println(" " + NUM_THREADS + " threads completaram em " + duracao + "ms (sem deadlock)");
    }

    /**
     * Teste 4: Consistência de Queries
     * Verifica que queries durante adição contínua retornam valores não-decrescentes.
     */
    public static void testConsistenciaQueries() throws Exception {
        System.out.println("[TESTE 4] Consistência de Queries");

        Client writer = new Client();
        Client reader = new Client();

        try {
            writer.connect(HOST, PORT);
            reader.connect(HOST, PORT);

            String writerId = "writer" + System.nanoTime();
            String readerId = "reader" + System.nanoTime();

            writer.register(writerId, writerId);
            reader.register(readerId, readerId);

            writer.login(writerId, writerId);
            reader.login(readerId, readerId);

            final boolean[] running = { true };
            final int[] values = new int[100];
            final AtomicInteger eventosAdicionados = new AtomicInteger(0);

            // Thread que adiciona eventos continuamente
            Thread tWriter = new Thread(() -> {
                try {
                    while (running[0]) {
                        writer.addEvent("ProdutoD", 1, 1.0f);
                        eventosAdicionados.incrementAndGet();
                        Thread.sleep(5); // Pequeno delay
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            // Thread que lê valores periodicamente
            Thread tReader = new Thread(() -> {
                try {
                    for (int i = 0; i < values.length; i++) {
                        values[i] = reader.doQueryQuantity("ProdutoD", 1);
                        Thread.sleep(10);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            tWriter.start();
            Thread.sleep(100); // Dar tempo ao writer começar
            tReader.start();

            tReader.join();
            running[0] = false;
            tWriter.join();

            // Verificar que valores são não-decrescentes
            for (int i = 1; i < values.length; i++) {
                if (values[i] < values[i - 1]) {
                    throw new AssertionError("Valor decresceu: values[" + (i-1) + "]=" + values[i-1] +
                            ", values[" + i + "]=" + values[i]);
                }
            }

            System.out.println(" " + values.length + " leituras consistentes (min=" + values[0] +
                    ", max=" + values[values.length-1] + ", adicionados=" + eventosAdicionados.get() + ")");

        } finally {
            writer.close();
            reader.close();
        }
    }

    /**
     * Teste 5: Robustez do ThreadPool (via carga no servidor)
     * Verifica que o servidor consegue processar muitos pedidos concorrentes.
     */
    public static void testThreadPoolCarga() throws Exception {
        System.out.println("[TESTE 5] Robustez sob Carga");

        final int NUM_CLIENTES = 200;
        CountDownLatch done = new CountDownLatch(NUM_CLIENTES);
        AtomicInteger sucesso = new AtomicInteger(0);
        AtomicInteger erros = new AtomicInteger(0);

        long inicio = System.currentTimeMillis();

        for (int i = 0; i < NUM_CLIENTES; i++) {
            new Thread(() -> {
                Client c = null;
                try {
                    String userId = "carga" + Thread.currentThread().getId();
                    c = new Client();
                    c.connect(HOST, PORT);
                    c.register(userId, userId);
                    c.login(userId, userId);

                    // Mix de operações
                    c.addEvent("ProdutoE", 1, 1.0f);
                    c.doQueryQuantity("ProdutoE", 1);
                    c.doQueryVolume("ProdutoE", 1);

                    sucesso.incrementAndGet();

                } catch (Exception e) {
                    erros.incrementAndGet();
                    System.err.println("[ERRO] Cliente : " + e.getMessage());
                } finally {
                    if (c != null) {
                        try { c.close(); } catch (IOException e) { }
                    }
                    done.countDown();
                }
            }).start();
        }

        done.await();
        long duracao = System.currentTimeMillis() - inicio;

        if (erros.get() > NUM_CLIENTES * 0.05) { // Tolerar até 5% de erros
            throw new AssertionError("Muitos erros: " + erros.get() + "/" + NUM_CLIENTES);
        }

        System.out.println(" " + NUM_CLIENTES + " clientes processados em " + duracao + "ms " +
                "(sucesso=" + sucesso.get() + ", erros=" + erros.get() + ")");
    }

    /**
     * Teste 6: Notificações Bloqueantes
     * Verifica que notificações acordam corretamente quando condição é satisfeita.
     */
    public static void testNotificacaoSimultanea() throws Exception {
        System.out.println("[TESTE 6] Notificações Bloqueantes");

        Client waiter = new Client();
        Client seller = new Client();

        try {
            waiter.connect(HOST, PORT);
            seller.connect(HOST, PORT);

            String waiterId = "waiter" + System.nanoTime();
            String sellerId = "seller" + System.nanoTime();

            waiter.register(waiterId, waiterId);
            seller.register(sellerId, sellerId);

            waiter.login(waiterId, waiterId);
            seller.login(sellerId, sellerId);

            final boolean[] notified = { false };
            final long[] tempoEspera = { 0 };

            // Thread que espera notificação
            Thread tWaiter = new Thread(() -> {
                try {
                    long inicio = System.currentTimeMillis();
                    boolean result = waiter.esperaSimultaneos("ProdutoX", "ProdutoY");
                    tempoEspera[0] = System.currentTimeMillis() - inicio;
                    notified[0] = result;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            tWaiter.start();
            Thread.sleep(500); // Garantir que waiter começou a esperar

            // Adicionar primeiro produto
            seller.addEvent("ProdutoX", 1, 1.0f);
            Thread.sleep(200);

            // Adicionar segundo produto (deve acordar o waiter)
            seller.addEvent("ProdutoY", 1, 1.0f);

            tWaiter.join(5000); // Timeout de 5s

            if (tWaiter.isAlive()) {
                throw new AssertionError("Notificação não acordou a thread");
            }

            if (!notified[0]) {
                throw new AssertionError("Notificação retornou false");
            }

            System.out.println(" Notificação funcionou corretamente (tempo de espera: " + tempoEspera[0] + "ms)");

        } finally {
            waiter.close();
            seller.close();
        }
    }

    /**
     * Teste 7: Edge Case - Múltiplos newDay() Concorrentes
     * Verifica comportamento quando múltiplos admins tentam avançar dia simultaneamente.
     */
    public static void testEdgeCaseNewDayMultiplo() throws Exception {
        System.out.println("[TESTE 7] Edge Case - newDay() Concorrente");

        final int NUM_ADMINS = 5;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(NUM_ADMINS);
        AtomicInteger sucessos = new AtomicInteger(0);

        for (int i = 0; i < NUM_ADMINS; i++) {
            new Thread(() -> {
                Client admin = null;
                try {
                    admin = new Client();
                    admin.connect(HOST, PORT);
                    admin.login("tacus", "tacus");

                    start.await(); // Sincronizar

                    admin.newDay();
                    sucessos.incrementAndGet();

                } catch (Exception e) {
                    // Pode falhar se outro admin já avançou o dia
                    // Isso é aceitável
                } finally {
                    if (admin != null) {
                        try { admin.close(); } catch (IOException e) { }
                    }
                    done.countDown();
                }
            }).start();
        }

        start.countDown();
        done.await();

        // Pelo menos um deve ter sucesso
        if (sucessos.get() == 0) {
            throw new AssertionError("Nenhum admin conseguiu avançar o dia");
        }

        System.out.println(" " + sucessos.get() + "/" + NUM_ADMINS + " admins avançaram dia com sucesso");
    }
}
