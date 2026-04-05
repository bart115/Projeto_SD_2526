package common;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.*;

/**
 * Representa um evento de venda.
 * Imutável após criação para thread-safety.
 */
public class Evento {
    private final String productName;
    private final int quantidade;
    private final float price;

    public Evento(String productName, int quantidade, float price) {
        this.productName = productName;
        this.quantidade = quantidade;
        this.price = price;
    }

    public String getProductName() {
        return this.productName;
    }

    public int getQuantidade() {
        return this.quantidade;
    }

    public float getPrice() {
        return this.price;
    }
    
    /**
     * Calcula o valor total desta venda.
     */
    public float getVolume() {
        return this.quantidade * this.price;
    }

    /**
     * Serializa o evento para um stream binário.
     */
    public void serialize(DataOutputStream out) throws IOException {
        out.writeUTF(this.productName);
        out.writeInt(this.quantidade);
        out.writeFloat(this.price);
        // Nota: não fazer flush aqui - deixar para quem controla o stream
    }

    /**
     * Deserializa um evento de um stream binário.
     */
    public static Evento deserialize(DataInputStream in) throws IOException {
        String name = in.readUTF();
        int quantidade = in.readInt();
        float price = in.readFloat();
        return new Evento(name, quantidade, price);
    }

    /**
     * Serializa uma lista de eventos de forma compacta usando uma tabela de strings.
     * Evita repetir nomes de produtos, reduzindo o tamanho da transmissão.
     */
    public static void serializeEventList(List<Evento> eventos, DataOutputStream dos)
            throws IOException {
        // 1. Construir tabela de strings únicas
        Map<String, Integer> stringTable = new HashMap<>();
        List<String> strings = new ArrayList<>();

        for (Evento e : eventos) {
            if (!stringTable.containsKey(e.getProductName())) {
                stringTable.put(e.getProductName(), strings.size());
                strings.add(e.getProductName());
            }
        }

        // 2. Escrever tabela de strings
        dos.writeInt(strings.size());
        for (String s : strings) {
            dos.writeUTF(s);
        }

        // 3. Escrever eventos (produto como índice)
        dos.writeInt(eventos.size());
        for (Evento e : eventos) {
            dos.writeInt(stringTable.get(e.getProductName())); // índice em vez de string
            dos.writeInt(e.getQuantidade());
            dos.writeFloat(e.getPrice());
        }
    }

    /**
     * Deserializa uma lista de eventos usando o formato compacto com tabela de strings.
     */
    public static List<Evento> deserializeEventList(DataInputStream dis)
            throws IOException {
        // 1. Ler tabela de strings
        int numStrings = dis.readInt();
        String[] stringTable = new String[numStrings];
        for (int i = 0; i < numStrings; i++) {
            stringTable[i] = dis.readUTF();
        }

        // 2. Ler eventos
        int numEventos = dis.readInt();
        List<Evento> eventos = new ArrayList<>(numEventos);
        for (int i = 0; i < numEventos; i++) {
            int prodIndex = dis.readInt();
            int qtd = dis.readInt();
            float price = dis.readFloat();
            eventos.add(new Evento(stringTable[prodIndex], qtd, price));
        }

        return eventos;
    }

    @Override
    public String toString() {
        return "Evento{" + productName + ", qtd=" + quantidade + ", price=" + price + "}";
    }
}
