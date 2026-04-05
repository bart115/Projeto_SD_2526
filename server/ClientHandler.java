package server;

import common.*;
import common.TaggedConnection.Frame;

import java.io.*;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Handler para um cliente conectado.
 * Processa pedidos em protocolo binário.
 */
public class ClientHandler implements Runnable {
    private final Socket socket;
    private final Autenticador autenticador;
    private final EventManager eventManager;
    private final SNotificacoes notificationManager;
    private final ThreadPool threadPool;
    private final ReentrantLock sendLock = new ReentrantLock();  // Lock para sincronizar envio de respostas

    private TaggedConnection conn;
    private String authenticatedUser = null;

    public ClientHandler(Socket socket, Autenticador autenticador,
                         EventManager eventManager, SNotificacoes notificationManager,
                         ThreadPool threadPool) {
        this.socket = socket;
        this.autenticador = autenticador;
        this.eventManager = eventManager;
        this.notificationManager = notificationManager;
        this.threadPool = threadPool;  // ThreadPool GLOBAL partilhado por todos os clientes
    }

    @Override
    public void run() {
        try {
            conn = new TaggedConnection(socket);
            System.out.println("[ClientHandler] Nova conexão estabelecida: " + socket.getRemoteSocketAddress());

            while (true) {
                Frame frame = conn.receive();
                // Processar pedido em thread do pool (assíncrono)
                final int tag = frame.tag;
                final byte[] data = frame.data;
                threadPool.submit(() -> {
                    try {
                        handleRequest(tag, data);
                    } catch (IOException e) {
                        System.err.println("[ClientHandler] Erro ao processar pedido (tag=" + tag + "): " + e.getMessage());
                    }
                });
            }

        } catch (IOException e) {
            // Cliente desconectou
            System.out.println("[ClientHandler] Cliente desconectado: " +
                (authenticatedUser != null ? authenticatedUser : "não autenticado") +
                " (" + socket.getRemoteSocketAddress() + ")");
        } finally {
            // NÃO fazer shutdown do pool - é global e partilhado!
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    /**
     * Processa um pedido e envia resposta.
     * @param routingTag tag usada para routing (resposta usa a mesma tag)
     * @param data dados do pedido: [opType (4 bytes)] + [payload]
     */
    private void handleRequest(int routingTag, byte[] data) throws IOException {
        try {
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data));

            // Ler tipo de operação (primeiros 4 bytes)
            int opType = dis.readInt();

            // Operações que não requerem autenticação
            if (opType == Protocolo.TAG_REGISTER) {
                handleRegister(routingTag, dis);
                return;
            }
            if (opType == Protocolo.TAG_LOGIN) {
                handleLogin(routingTag, dis);
                return;
            }

            // Todas as outras operações requerem autenticação
            if (authenticatedUser == null) {
                System.err.println("[ERRO] Operação opType=" + opType + " requer autenticação (tag=" + routingTag + ")");
                sendError(routingTag, Protocolo.STATUS_NOT_AUTHENTICATED);
                return;
            }

            // Dispatch por tipo de operação
            switch (opType) {
                case Protocolo.TAG_ADD_EVENT:
                    handleAddEvent(routingTag, dis);
                    break;
                case Protocolo.TAG_NEW_DAY:
                    /*try{
                        sleep(10000);
                    }catch (InterruptedException e){
                        e.printStackTrace();
                    }*/
                    handleNewDay(routingTag, dis);
                    break;
                case Protocolo.TAG_QUERY_QUANTITY:
                    handleQueryQuantity(routingTag, dis);
                    break;
                case Protocolo.TAG_QUERY_VOLUME:
                    handleQueryVolume(routingTag, dis);
                    break;
                case Protocolo.TAG_QUERY_AVG_PRICE:
                    handleQueryAvgPrice(routingTag, dis);
                    break;
                case Protocolo.TAG_QUERY_MAX_PRICE:
                    handleQueryMaxPrice(routingTag, dis);
                    break;
                case Protocolo.TAG_FILTER_EVENTS:
                    handleFilterEvents(routingTag, dis);
                    break;
                case Protocolo.TAG_NOTIFY_SIMULTANEOUS:
                    handleNotifySimultaneous(routingTag, dis);
                    break;
                case Protocolo.TAG_NOTIFY_CONSECUTIVE:
                    handleNotifyConsecutive(routingTag, dis);
                    break;
                default:
                    System.err.println("[ERRO] opType desconhecido: " + opType + " (user='" + authenticatedUser + "', tag=" + routingTag + ")");
                    sendError(routingTag, Protocolo.STATUS_ERROR);
            }

        } catch (Exception e) {
            System.err.println("[ERRO] Exceção ao processar pedido (user='" + authenticatedUser + "', tag=" + routingTag + "): " + e.getMessage());
            e.printStackTrace();
            sendError(routingTag, Protocolo.STATUS_ERROR);
        }
    }

    // === AUTENTICAÇÃO ===

    private void handleRegister(int tag, DataInputStream dis) throws IOException {
        String username = dis.readUTF();
        String password = dis.readUTF();

        boolean success = autenticador.regista(username, password);

        if (success) {
            System.out.println("[AUTH] Registo bem-sucedido: username='" + username + "'");
            sendOk(tag);
        } else {
            System.out.println("[AUTH] Registo falhou: username='" + username + "' já existe");
            sendError(tag, Protocolo.STATUS_USER_EXISTS);
        }
    }

    private void handleLogin(int tag, DataInputStream dis) throws IOException {
        String username = dis.readUTF();
        String password = dis.readUTF();

        boolean success = autenticador.autentica(username, password);

        if (success) {
            authenticatedUser = username;
            boolean isAdmin = autenticador.isAdmin(username);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeByte(Protocolo.STATUS_OK);
            dos.writeBoolean(isAdmin);  // enviar status de admin

            sendLock.lock();
            try {
                conn.send(tag, baos.toByteArray());
            } finally {
                sendLock.unlock();
            }

            System.out.println("[AUTH] Login bem-sucedido: username='" + username + "'" +
                             (isAdmin ? " (ADMIN)" : " (utilizador normal)"));
        } else {
            System.out.println("[AUTH] Login falhou: username='" + username + "' (credenciais inválidas)");
            sendError(tag, Protocolo.STATUS_INVALID_CREDENTIALS);
        }
    }

    // === EVENTOS ===

    private void handleAddEvent(int tag, DataInputStream dis) throws IOException {
        String productName = dis.readUTF();
        int quantidade = dis.readInt();
        float price = dis.readFloat();

        Evento evento = new Evento(productName, quantidade, price);
        eventManager.addEvent(evento);

        System.out.println("[EVENT] Evento adicionado (user='" + authenticatedUser + "'): produto='" +
                          productName + "', qtd=" + quantidade + ", preço=" + price);
        sendOk(tag);
    }

    private void handleNewDay(int tag, DataInputStream dis) throws IOException {
        // Verificar se é admin
        if (!autenticador.isAdmin(authenticatedUser)) {
            System.err.println("[ERRO] Tentativa de newDay por utilizador não-admin: '" + authenticatedUser + "'");
            sendError(tag, Protocolo.STATUS_ERROR);
            return;
        }

        int oldDay = eventManager.getCurrentDay();
        eventManager.newDay();
        int newDay = eventManager.getCurrentDay();
        System.out.println("[DAY] Novo dia iniciado (admin='" + authenticatedUser + "'): dia " + oldDay + " -> dia " + newDay);
        sendOk(tag);
    }

    // Novo método a acrescentar junto dos outros handlers:
    // java
    private void handleQueryQuantity(int tag, DataInputStream dis) throws IOException {
        String productName = dis.readUTF();
        int d = dis.readInt();

        // Validações básicas do pedido
        if (productName == null || productName.trim().isEmpty()) {
            System.err.println("[QUERY] Pedido inválido: productName vazio (user='" + authenticatedUser + "')");
            sendError(tag, Protocolo.STATUS_ERROR);
            return;
        }
        if (d < 1) {
            System.err.println("[QUERY] Pedido inválido: d < 1 (user='" + authenticatedUser + "')");
            sendError(tag, Protocolo.STATUS_ERROR);
            return;
        }

        try {
            int quantity = eventManager.queryQuantity(productName, d);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeByte(Protocolo.STATUS_OK);
            dos.writeInt(quantity);
            dos.flush();

            sendLock.lock();
            try {
                conn.send(tag, baos.toByteArray());
            } finally {
                sendLock.unlock();
            }

            System.out.println("[QUERY] Quantidade consultada (user='" + authenticatedUser + "'): produto='" +
                    productName + "', d=" + d + " -> qty=" + quantity);

        } catch (IllegalArgumentException e) {
            System.err.println("[QUERY] Pedido inválido (user='" + authenticatedUser + "'): " + e.getMessage());
            sendError(tag, Protocolo.STATUS_ERROR);
        } catch (IOException e) {
            System.err.println("[QUERY] Erro IO ao processar query (user='" + authenticatedUser + "'): " + e.getMessage());
            sendError(tag, Protocolo.STATUS_ERROR);
        } catch (Exception e) {
            System.err.println("[QUERY] Exceção ao processar query (user='" + authenticatedUser + "'): " + e.getMessage());
            sendError(tag, Protocolo.STATUS_ERROR);
        }
    }

    private void handleQueryVolume(int tag, DataInputStream dis) throws IOException {
        String productName = dis.readUTF();
        int d = dis.readInt();

        // Validações básicas do pedido
        if (productName == null || productName.trim().isEmpty()) {
            System.err.println("[QUERY] Pedido inválido: productName vazio (user='" + authenticatedUser + "')");
            sendError(tag, Protocolo.STATUS_ERROR);
            return;
        }
        if (d < 1) {
            System.err.println("[QUERY] Pedido inválido: d < 1 (user='" + authenticatedUser + "')");
            sendError(tag, Protocolo.STATUS_ERROR);
            return;
        }

        try {
            float volume = eventManager.queryVolume(productName, d);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeByte(Protocolo.STATUS_OK);
            dos.writeFloat(volume);
            dos.flush();

            sendLock.lock();
            try {
                conn.send(tag, baos.toByteArray());
            } finally {
                sendLock.unlock();
            }

            System.out.println("[QUERY] Volume consultada (user='" + authenticatedUser + "'): produto='" +
                    productName + "', d=" + d + " -> qty=" + volume);

        } catch (IllegalArgumentException e) {
            System.err.println("[QUERY] Pedido inválido (user='" + authenticatedUser + "'): " + e.getMessage());
            sendError(tag, Protocolo.STATUS_ERROR);
        } catch (IOException e) {
            System.err.println("[QUERY] Erro IO ao processar query (user='" + authenticatedUser + "'): " + e.getMessage());
            sendError(tag, Protocolo.STATUS_ERROR);
        } catch (Exception e) {
            System.err.println("[QUERY] Exceção ao processar query (user='" + authenticatedUser + "'): " + e.getMessage());
            sendError(tag, Protocolo.STATUS_ERROR);
        }
    }



    // --- novo método a acrescentar na classe ClientHandler ---
    private void handleQueryAvgPrice(int tag, DataInputStream dis) throws IOException {
        String productName = dis.readUTF();
        int d = dis.readInt();

        // Validações básicas do pedido
        if (productName == null || productName.trim().isEmpty()) {
            System.err.println("[QUERY_AVG] Pedido inválido: productName vazio (user='" + authenticatedUser + "')");
            sendError(tag, Protocolo.STATUS_ERROR);
            return;
        }
        if (d < 1) {
            System.err.println("[QUERY_AVG] Pedido inválido: d < 1 (user='" + authenticatedUser + "')");
            sendError(tag, Protocolo.STATUS_ERROR);
            return;
        }

        try {
            // Chama o EventManager (implementar queryAvgPrice)
            float avgPrice = eventManager.queryAvgPrice(productName, d);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeByte(Protocolo.STATUS_OK);
            dos.writeFloat(avgPrice);
            dos.flush();

            sendLock.lock();
            try {
                conn.send(tag, baos.toByteArray());
            } finally {
                sendLock.unlock();
            }

            System.out.println("[QUERY_AVG] Preço médio consultado (user='" + authenticatedUser + "'): produto='" +
                    productName + "', d=" + d + " -> avg=" + avgPrice);

        } catch (IllegalArgumentException e) {
            System.err.println("[QUERY_AVG] Pedido inválido (user='" + authenticatedUser + "'): " + e.getMessage());
            sendError(tag, Protocolo.STATUS_ERROR);
        } catch (IOException e) {
            System.err.println("[QUERY_AVG] Erro IO ao processar query (user='" + authenticatedUser + "'): " + e.getMessage());
            sendError(tag, Protocolo.STATUS_ERROR);
        } catch (Exception e) {
            System.err.println("[QUERY_AVG] Exceção ao processar query (user='" + authenticatedUser + "'): " + e.getMessage());
            sendError(tag, Protocolo.STATUS_ERROR);
        }
    }

    // Inserir este método na classe `server/ClientHandler` (junto dos outros handlers)
    private void handleQueryMaxPrice(int tag, DataInputStream dis) throws IOException {
        String productName = dis.readUTF();
        int d = dis.readInt();

        // Validações básicas do pedido
        if (productName == null || productName.trim().isEmpty()) {
            System.err.println("[QUERY_MAX] Pedido inválido: productName vazio (user='" + authenticatedUser + "')");
            sendError(tag, Protocolo.STATUS_ERROR);
            return;
        }
        if (d < 1) {
            System.err.println("[QUERY_MAX] Pedido inválido: d < 1 (user='" + authenticatedUser + "')");
            sendError(tag, Protocolo.STATUS_ERROR);
            return;
        }

        try {
            // Chama o EventManager (implementar queryMaxPrice)
            float maxPrice = eventManager.queryMaxPrice(productName, d);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeByte(Protocolo.STATUS_OK);
            dos.writeFloat(maxPrice);
            dos.flush();

            sendLock.lock();
            try {
                conn.send(tag, baos.toByteArray());
            } finally {
                sendLock.unlock();
            }

            System.out.println("[QUERY_MAX] Preço máximo consultado (user='" + authenticatedUser + "'): produto='" +
                    productName + "', d=" + d + " -> max=" + maxPrice);

        } catch (IllegalArgumentException e) {
            System.err.println("[QUERY_MAX] Pedido inválido (user='" + authenticatedUser + "'): " + e.getMessage());
            sendError(tag, Protocolo.STATUS_ERROR);
        } catch (IOException e) {
            System.err.println("[QUERY_MAX] Erro IO ao processar query (user='" + authenticatedUser + "'): " + e.getMessage());
            sendError(tag, Protocolo.STATUS_ERROR);
        } catch (Exception e) {
            System.err.println("[QUERY_MAX] Exceção ao processar query (user='" + authenticatedUser + "'): " + e.getMessage());
            sendError(tag, Protocolo.STATUS_ERROR);
        }
    }
    // === FILTRAR EVENTOS ===

    private void handleFilterEvents(int tag, DataInputStream dis) throws IOException {
        // Ler número de produtos
        int numProducts = dis.readInt();
        Set<String> products = new HashSet<>();
        for (int i = 0; i < numProducts; i++) {
            products.add(dis.readUTF());
        }
        int dayOffset = dis.readInt();

        // Validações básicas do pedido
        if (products.isEmpty()) {
            System.err.println("[FILTER] Pedido inválido: conjunto de produtos vazio (user='" + authenticatedUser + "')");
            sendError(tag, Protocolo.STATUS_ERROR);
            return;
        }
        if (dayOffset < 1) {
            System.err.println("[FILTER] Pedido inválido: dayOffset < 1 (user='" + authenticatedUser + "')");
            sendError(tag, Protocolo.STATUS_ERROR);
            return;
        }

        try {
            List<Evento> eventos = eventManager.filterEvents(products, dayOffset);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeByte(Protocolo.STATUS_OK);

            // Usar serialização compacta
            Evento.serializeEventList(eventos, dos);
            dos.flush();

            sendLock.lock();
            try {
                conn.send(tag, baos.toByteArray());
            } finally {
                sendLock.unlock();
            }

            System.out.println("[FILTER] Eventos filtrados (user='" + authenticatedUser + "'): produtos=" +
                    products + ", dayOffset=" + dayOffset + " -> " + eventos.size() + " eventos");

        } catch (IllegalArgumentException e) {
            System.err.println("[FILTER] Pedido inválido (user='" + authenticatedUser + "'): " + e.getMessage());
            sendError(tag, Protocolo.STATUS_ERROR);
        } catch (IOException e) {
            System.err.println("[FILTER] Erro IO ao processar filtro (user='" + authenticatedUser + "'): " + e.getMessage());
            sendError(tag, Protocolo.STATUS_ERROR);
        } catch (Exception e) {
            System.err.println("[FILTER] Exceção ao processar filtro (user='" + authenticatedUser + "'): " + e.getMessage());
            sendError(tag, Protocolo.STATUS_ERROR);
        }
    }

    // === NOTIFICAÇÕES ===

    private void handleNotifySimultaneous(int tag, DataInputStream dis) throws IOException {
        String p1 = dis.readUTF();
        String p2 = dis.readUTF();

        new Thread(() -> {
            try {
                System.out.println("[NOTIFY] Esperando vendas simultâneas (user='" + authenticatedUser + "'): p1='" + p1 + "', p2='" + p2 + "'");
                boolean result = notificationManager.waitSimultaneous(p1, p2);

                System.out.println("[NOTIFY] Vendas simultâneas " + (result ? "SUCESSO" : "DIA_TERMINOU") +
                                 " (user='" + authenticatedUser + "'): p1='" + p1 + "', p2='" + p2 + "'");

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                DataOutputStream dos = new DataOutputStream(baos);
                dos.writeByte(result ? Protocolo.STATUS_OK : Protocolo.STATUS_DAY_ENDED);
                dos.writeBoolean(result);

                sendLock.lock();
                try {
                    conn.send(tag, baos.toByteArray());
                } finally {
                    sendLock.unlock();
                }

            } catch (InterruptedException e) {
                System.err.println("[ERRO] Interrompido ao esperar vendas simultâneas (user='" + authenticatedUser + "')");
                Thread.currentThread().interrupt();
                try {
                    sendError(tag, Protocolo.STATUS_ERROR);
                } catch (IOException ioException) {
                    System.err.println("[ERRO] Falha ao enviar erro de notificação (user='" + authenticatedUser + "')");
                }
            } catch (IOException e) {
                System.err.println("[ERRO] Falha ao enviar notificação (user='" + authenticatedUser + "'): " + e.getMessage());
            }
        }, "NotifySimultaneousThread").start();
    }

    private void handleNotifyConsecutive(int tag, DataInputStream dis) throws IOException {
        int n = dis.readInt();

        new Thread(() -> {
            try {
                System.out.println("[NOTIFY] Esperando " + n + " vendas consecutivas (user='" + authenticatedUser + "')");
                String product = notificationManager.waitConsecutive(n);

                if (product != null) {
                    System.out.println("[NOTIFY] Vendas consecutivas SUCESSO (user='" + authenticatedUser + "'): " +
                                     n + " vendas de '" + product + "'");
                } else {
                    System.out.println("[NOTIFY] Vendas consecutivas DIA_TERMINOU (user='" + authenticatedUser + "', n=" + n + ")");
                }

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                DataOutputStream dos = new DataOutputStream(baos);

                if (product != null) {
                    dos.writeByte(Protocolo.STATUS_OK);
                    dos.writeUTF(product);
                } else {
                    dos.writeByte(Protocolo.STATUS_DAY_ENDED);
                }

                sendLock.lock();
                try {
                    conn.send(tag, baos.toByteArray());
                } finally {
                    sendLock.unlock();
                }

            } catch (InterruptedException e) {
                System.err.println("[ERRO] Interrompido ao esperar vendas consecutivas (user='" + authenticatedUser + "', n=" + n + ")");
                Thread.currentThread().interrupt();
                try {
                    sendError(tag, Protocolo.STATUS_ERROR);
                } catch (IOException ioException) {
                    System.err.println("[ERRO] Falha ao enviar erro de notificação (user='" + authenticatedUser + "')");
                }
            } catch (IOException e) {
                System.err.println("[ERRO] Falha ao enviar notificação (user='" + authenticatedUser + "'): " + e.getMessage());
            }
        }, "NotifyConsecutiveThread").start();
    }

    // === HELPERS ===

    private void sendOk(int tag) throws IOException {
        sendLock.lock();
        try {
            conn.send(tag, new byte[]{Protocolo.STATUS_OK});
        } finally {
            sendLock.unlock();
        }
    }

    private void sendError(int tag, byte status) throws IOException {
        sendLock.lock();
        try {
            conn.send(tag, new byte[]{status});
        } finally {
            sendLock.unlock();
        }
    }
}
