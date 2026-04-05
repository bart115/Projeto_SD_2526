package common;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Conexão com delimitação de mensagens (length-prefixed framing).
 * Permite enviar e receber mensagens de tamanho variável sobre TCP.
 * Thread-safe: múltiplas threads podem enviar e receber concorrentemente.
 */
public class FramedConnection implements AutoCloseable {
    private final Socket socket;
    private final DataInputStream dis;
    private final DataOutputStream dos;
    private final ReentrantLock receiveLock;
    private final ReentrantLock sendLock;

    public FramedConnection(Socket socket) throws IOException {
        this.socket = socket;
        this.dis = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.dos = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        this.receiveLock = new ReentrantLock();
        this.sendLock = new ReentrantLock();
    }

    /**
     * Envia uma mensagem (byte array) precedida do seu tamanho.
     */
    public void send(byte[] data) throws IOException {
        sendLock.lock();
        try {
            dos.writeInt(data.length);
            dos.write(data);
            dos.flush();
        } finally {
            sendLock.unlock();
        }
    }

    /**
     * Recebe uma mensagem, lendo primeiro o tamanho e depois os dados.
     */
    public byte[] receive() throws IOException {
        receiveLock.lock();
        try {
            int length = dis.readInt();
            byte[] data = new byte[length];
            dis.readFully(data);
            return data;
        } finally {
            receiveLock.unlock();
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
