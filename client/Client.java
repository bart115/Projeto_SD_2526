package client;

import java.io.*;
import java.net.Socket;
import java.util.*;
import java.util.function.Consumer;
import common.*;

public class Client implements AutoCloseable {
    private Socket socket;
    private Demultiplexer demux;
    private int tagCounter = 1;
    private final java.util.concurrent.locks.ReentrantLock tagLock = new java.util.concurrent.locks.ReentrantLock();
    private boolean authenticated = false;
    private boolean isAdmin = false;

    /**
     * Conecta-se ao server e inicia o demultiplexador.
     */
    public void connect(String host, int port) throws IOException {
        socket = new Socket(host, port);
        TaggedConnection conn = new TaggedConnection(socket);
        demux = new Demultiplexer(conn);
        demux.start();
    }

    // Cria uma tag unica para identificar
    private int nextTag() {
        tagLock.lock();
        try {
            return tagCounter++;
        } finally {
            tagLock.unlock();
        }
    }


    /**
     * Envia um pedido ao servidor com o tipo de operação e dados.
     * @return resposta do servidor
     */
    private byte[] sendRequest(int operationType, byte[] data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt(operationType);
        dos.write(data);

        int tag = nextTag();
        demux.send(tag, baos.toByteArray());
        
        try {
            return demux.receive(tag);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Thread interrompida enquanto aguardava resposta", e);
        }
    }

    public boolean register(String username, String password)
            throws IOException {

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeUTF(username);
        dos.writeUTF(password);

        byte[] response = sendRequest(Protocolo.TAG_REGISTER, baos.toByteArray());
        byte status = response[0];

        if (status == Protocolo.STATUS_OK) {
            return true;
        } else if (status == Protocolo.STATUS_USER_EXISTS) {
            throw new IOException("STATUS_USER_EXISTS: Username '" + username + "' já registado");
        } else if (status == Protocolo.STATUS_ERROR) {
            throw new IOException("STATUS_ERROR: Falha no servidor ao processar TAG_REGISTER");
        } else {
            throw new IOException("TAG_REGISTER: Status code desconhecido (" + status + ")");
        }
    }


    public boolean login(String username, String password) throws IOException {

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeUTF(username);
        dos.writeUTF(password);

        byte[] response = sendRequest(Protocolo.TAG_LOGIN, baos.toByteArray());

        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(response));
        byte status = dis.readByte();

        if (status == Protocolo.STATUS_OK) {
            authenticated = true;
            isAdmin = dis.readBoolean();  // Read admin status from server
            return true;
        } else if (status == Protocolo.STATUS_INVALID_CREDENTIALS) {
            throw new IOException("STATUS_INVALID_CREDENTIALS: Autenticação falhou para username '" + username + "'");
        } else if (status == Protocolo.STATUS_ERROR) {
            throw new IOException("STATUS_ERROR: Falha no servidor ao processar TAG_LOGIN");
        } else {
            throw new IOException("TAG_LOGIN: Status code desconhecido (" + status + ")");
        }
    }

    /**
     * Verifica se o utilizador autenticado é administrador.
     * @return true se é admin, false caso contrário
     */
    public boolean isAdmin() {
        return isAdmin;
    }

    /**
     * Faz logout do utilizador atual.
     */
    public void logout() {
        authenticated = false;
        isAdmin = false;
    }

    public void esperaSimultaneosAsync(String p1, String p2, Consumer<Boolean> callback) {
        new Thread(() -> {
            try {
                boolean result = esperaSimultaneos(p1, p2);
                callback.accept(result);
            } catch (Exception e) {
                System.err.println("Erro na notificação simultânea: " + e.getMessage());
                callback.accept(false);
            }
        }, "Simultaneous-Notifier").start();
    }

    public void esperaConsecutivosAsync(int n, Consumer<String> callback) {
        new Thread(() -> {
            try {
                String result = esperaConsecutivos(n);
                callback.accept(result);
            } catch (Exception e) {
                System.err.println("Erro na notificação consecutiva: " + e.getMessage());
                callback.accept(null);
            }
        }, "Consecutive-Notifier").start();
    }

    /**
     * Espera até dois produtos terem sido vendidos no dia corrente.
     * @return true se ambos vendidos, false se dia terminou antes
     */
    public boolean esperaSimultaneos(String p1, String p2)
            throws IOException {

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeUTF(p1);
        dos.writeUTF(p2);

        byte[] response = sendRequest(Protocolo.TAG_NOTIFY_SIMULTANEOUS, baos.toByteArray());

        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(response));
        byte status = dis.readByte();

        if (status == Protocolo.STATUS_OK) {
            return dis.readBoolean();
        } else if (status == Protocolo.STATUS_DAY_ENDED) {
            return false;
        } else if (status == Protocolo.STATUS_NOT_AUTHENTICATED) {
            throw new IOException("STATUS_NOT_AUTHENTICATED: Cliente não autenticado para TAG_NOTIFY_SIMULTANEOUS");
        } else if (status == Protocolo.STATUS_ERROR) {
            throw new IOException("STATUS_ERROR: Falha no servidor ao processar TAG_NOTIFY_SIMULTANEOUS (p1='" + p1 + "', p2='" + p2 + "')");
        } else {
            throw new IOException("TAG_NOTIFY_SIMULTANEOUS: Status code desconhecido (" + status + ")");
        }
    }

    /**
     * Espera até haver N vendas consecutivas do mesmo produto.
     * @return nome do produto, ou null se dia terminou antes
     */
    public String esperaConsecutivos(int n)
            throws IOException {

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt(n);

        byte[] response = sendRequest(Protocolo.TAG_NOTIFY_CONSECUTIVE, baos.toByteArray());

        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(response));
        byte status = dis.readByte();

        if (status == Protocolo.STATUS_OK) {
            return dis.readUTF();
        } else if (status == Protocolo.STATUS_DAY_ENDED) {
            return null;
        } else if (status == Protocolo.STATUS_NOT_AUTHENTICATED) {
            throw new IOException("STATUS_NOT_AUTHENTICATED: Cliente não autenticado para TAG_NOTIFY_CONSECUTIVE");
        } else if (status == Protocolo.STATUS_ERROR) {
            throw new IOException("STATUS_ERROR: Falha no servidor ao processar TAG_NOTIFY_CONSECUTIVE (n=" + n + ")");
        } else {
            throw new IOException("TAG_NOTIFY_CONSECUTIVE: Status code desconhecido (" + status + ")");
        }
    }

    /**
     * Adiciona um evento de venda ao dia corrente.
     */
    public void addEvent(String productName, int quantidade, float price)
            throws IOException {

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeUTF(productName);
        dos.writeInt(quantidade);
        dos.writeFloat(price);

        byte[] response = sendRequest(Protocolo.TAG_ADD_EVENT, baos.toByteArray());
        byte status = response[0];

        if (status == Protocolo.STATUS_OK) {
            return;
        } else if (status == Protocolo.STATUS_NOT_AUTHENTICATED) {
            throw new IOException("STATUS_NOT_AUTHENTICATED: Cliente não autenticado para TAG_ADD_EVENT");
        } else if (status == Protocolo.STATUS_ERROR) {
            throw new IOException("STATUS_ERROR: Falha no servidor ao processar TAG_ADD_EVENT (produto='" + productName + "', qtd=" + quantidade + ", preço=" + price + ")");
        } else {
            throw new IOException("TAG_ADD_EVENT: Status code desconhecido (" + status + ")");
        }
    }

    /**
     * Avança para o próximo dia.
     */
    public void newDay() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        byte[] response = sendRequest(Protocolo.TAG_NEW_DAY, baos.toByteArray());
        byte status = response[0];

        if (status == Protocolo.STATUS_OK) {
            return;
        } else if (status == Protocolo.STATUS_NOT_AUTHENTICATED) {
            throw new IOException("STATUS_NOT_AUTHENTICATED: Cliente não autenticado para TAG_NEW_DAY");
        } else if (status == Protocolo.STATUS_ERROR) {
            throw new IOException("STATUS_ERROR: Falha no servidor ao processar TAG_NEW_DAY");
        } else {
            throw new IOException("TAG_NEW_DAY: Status code desconhecido (" + status + ")");
        }
    }
    public int doQueryQuantity(String productName, int d) throws IOException {
        if (d < 1) throw new IllegalArgumentException("d deve ser >= 1");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeUTF(productName);
        dos.writeInt(d);

        byte[] response = sendRequest(Protocolo.TAG_QUERY_QUANTITY, baos.toByteArray());
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(response));
        byte status = dis.readByte();

        if (status == Protocolo.STATUS_OK) {
            return dis.readInt();
        } else if (status == Protocolo.STATUS_NOT_AUTHENTICATED) {
            throw new IOException("STATUS_NOT_AUTHENTICATED: Cliente não autenticado para TAG_QUERY_QUANTITY");
        } else if (status == Protocolo.STATUS_ERROR) {
            throw new IOException("STATUS_ERROR: Falha no servidor ao processar TAG_QUERY_QUANTITY (produto='" + productName + "', d=" + d + ")");
        } else {
            throw new IOException("TAG_QUERY_QUANTITY: Status code desconhecido (" + status + ")");
        }
    }
    public float doQueryVolume(String productName, int d) throws IOException {
        if (d < 1) throw new IllegalArgumentException("d deve ser >= 1");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeUTF(productName);
        dos.writeInt(d);

        byte[] response = sendRequest(Protocolo.TAG_QUERY_VOLUME, baos.toByteArray());
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(response));
        byte status = dis.readByte();

        if (status == Protocolo.STATUS_OK) {
            return dis.readFloat();
        } else if (status == Protocolo.STATUS_NOT_AUTHENTICATED) {
            throw new IOException("STATUS_NOT_AUTHENTICATED: Cliente não autenticado para TAG_QUERY_VOLUME");
        } else if (status == Protocolo.STATUS_ERROR) {
            throw new IOException("STATUS_ERROR: Falha no servidor ao processar TAG_QUERY_VOLUME (produto='" + productName + "', d=" + d + ")");
        } else {
            throw new IOException("TAG_QUERY_VOLUME: Status code desconhecido (" + status + ")");
        }
    }
    public float doQueryAvgPrice(String productName, int d) throws IOException {
        if (d < 1) throw new IllegalArgumentException("d deve ser >= 1");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeUTF(productName);
        dos.writeInt(d);

        byte[] response = sendRequest(Protocolo.TAG_QUERY_AVG_PRICE, baos.toByteArray());
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(response));
        byte status = dis.readByte();

        if (status == Protocolo.STATUS_OK) {
            return dis.readFloat();
        } else if (status == Protocolo.STATUS_NOT_AUTHENTICATED) {
            throw new IOException("STATUS_NOT_AUTHENTICATED: Cliente não autenticado para TAG_QUERY_AVG_PRICE");
        } else if (status == Protocolo.STATUS_ERROR) {
            throw new IOException("STATUS_ERROR: Falha no servidor ao processar TAG_QUERY_AVG_PRICE (produto='" + productName + "', d=" + d + ")");
        } else {
            throw new IOException("TAG_QUERY_AVG_PRICE: Status code desconhecido (" + status + ")");
        }
    }
    public float doQueryMaxPrice(String productName, int d) throws IOException {
        if (d < 1) throw new IllegalArgumentException("d deve ser >= 1");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeUTF(productName);
        dos.writeInt(d);

        byte[] response = sendRequest(Protocolo.TAG_QUERY_MAX_PRICE, baos.toByteArray());
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(response));
        byte status = dis.readByte();

        if (status == Protocolo.STATUS_OK) {
            return dis.readFloat();
        } else if (status == Protocolo.STATUS_NOT_AUTHENTICATED) {
            throw new IOException("STATUS_NOT_AUTHENTICATED: Cliente não autenticado para TAG_QUERY_MAX_PRICE");
        } else if (status == Protocolo.STATUS_ERROR) {
            throw new IOException("STATUS_ERROR: Falha no servidor ao processar TAG_QUERY_MAX_PRICE (produto='" + productName + "', d=" + d + ")");
        } else {
            throw new IOException("TAG_QUERY_MAX_PRICE: Status code desconhecido (" + status + ")");
        }
    }
    public List<Evento> doFilterEvents(Set<String> products, int dayOffset) throws IOException {
        if (products == null || products.isEmpty()) {
            throw new IllegalArgumentException("products não pode ser vazio");
        }
        if (dayOffset < 1) {
            throw new IllegalArgumentException("dayOffset deve ser >= 1");
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        // Serializar conjunto de produtos
        dos.writeInt(products.size());
        for (String product : products) {
            dos.writeUTF(product);
        }
        dos.writeInt(dayOffset);

        byte[] response = sendRequest(Protocolo.TAG_FILTER_EVENTS, baos.toByteArray());
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(response));
        byte status = dis.readByte();

        if (status == Protocolo.STATUS_OK) {
            // Deserializar lista de eventos usando formato compacto
            return Evento.deserializeEventList(dis);
        } else if (status == Protocolo.STATUS_NOT_AUTHENTICATED) {
            throw new IOException("STATUS_NOT_AUTHENTICATED: Cliente não autenticado para TAG_FILTER_EVENTS");
        } else if (status == Protocolo.STATUS_ERROR) {
            throw new IOException("STATUS_ERROR: Falha no servidor ao processar TAG_FILTER_EVENTS");
        } else {
            throw new IOException("TAG_FILTER_EVENTS: Status code desconhecido (" + status + ")");
        }
    }
    @Override
    public void close() throws IOException {
        if (demux != null) {
            demux.close();
        }
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }





}
