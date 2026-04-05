package common;

import java.io.*;
import java.net.Socket;

/**
 * Conexão com mensagens etiquetadas.
 * Cada frame contém uma tag (int) seguida de dados (byte[]).
 * A tag permite associar pedidos a respostas e distinguir as operações.
 */
public class TaggedConnection implements AutoCloseable {
    
    /**
     * Representa uma frame com tag e dados.
     */
    public static class Frame {
        public final int tag;
        public final byte[] data;
        
        public Frame(int tag, byte[] data) {
            this.tag = tag;
            this.data = data;
        }
    }
    
    private final FramedConnection conn;

    public TaggedConnection(Socket socket) throws IOException {
        this.conn = new FramedConnection(socket);
    }

    /**
     * Envia uma frame (tag + dados).
     */
    public void send(Frame frame) throws IOException {
        send(frame.tag, frame.data);
    }
    
    /**
     * Envia uma mensagem com tag e dados.
     */
    public void send(int tag, byte[] data) throws IOException {
        // Serializar tag + data num único byte array
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt(tag);
        dos.write(data);
        
        conn.send(baos.toByteArray());
    }

    /**
     * Recebe uma frame (tag + dados).
     */
    public Frame receive() throws IOException {
        byte[] frameData = conn.receive();
        
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(frameData));
        int tag = dis.readInt();
        
        // Restantes bytes são os dados
        byte[] data = new byte[frameData.length - 4];
        dis.readFully(data);
        
        return new Frame(tag, data);
    }

    @Override
    public void close() throws IOException {
        conn.close();
    }
}
