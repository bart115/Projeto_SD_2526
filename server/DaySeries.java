package server;

import common.Evento;

import java.io.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Série temporal de eventos de um dia específico.
 * Suporta:
 * - Adição de eventos (dia corrente)
 * - Cache lazy de agregações por produto
 * - Serialização/deserialização para persistência
 * 
 * Usa ReadWriteLock para permitir múltiplas leituras concorrentes.
 */
public class DaySeries {
    private final int dayNumber;
    private final List<Evento> eventos;
    private final ReentrantReadWriteLock rwLock;
    
    // Cache lazy de agregações por produto
    private final Map<String, ProductCache> cache;
    
    // Estado
    private boolean closed;  // dia já terminou (não aceita mais eventos)

    public DaySeries(int dayNumber) {
        this.dayNumber = dayNumber;
        this.eventos = new ArrayList<>();
        this.rwLock = new ReentrantReadWriteLock();
        this.cache = new HashMap<>();
        this.closed = false;
    }
    
    // Construtor para deserialização
    private DaySeries(int dayNumber, List<Evento> eventos, boolean closed) {
        this.dayNumber = dayNumber;
        this.eventos = new ArrayList<>(eventos);
        this.rwLock = new ReentrantReadWriteLock();
        this.cache = new HashMap<>();
        this.closed = closed;
    }

    public int getDayNumber() {
        return dayNumber;
    }

    /**
     * Adiciona um evento à série (apenas se dia não fechado).
     * @return true se adicionado, false se dia já fechado
     */
    public boolean addEvent(Evento evento) {
        rwLock.writeLock().lock();
        try {
            if (closed) return false;
            eventos.add(evento);
            return true;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * Fecha o dia (não aceita mais eventos).
     */
    public void close() {
        rwLock.writeLock().lock();
        try {
            closed = true;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public boolean isClosed() {
        rwLock.readLock().lock();
        try {
            return closed;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Obtém o cache de agregações para um produto.
     * Calcula e guarda em cache se não existir (lazy).
     */
    public ProductCache getProductCache(String productName) {
        // Primeiro tenta ler do cache (read lock)
        rwLock.readLock().lock();
        try {
            ProductCache cached = cache.get(productName);
            if (cached != null) {
                return cached;
            }
        } finally {
            rwLock.readLock().unlock();
        }
        
        // Não está em cache - calcular (write lock)
        rwLock.writeLock().lock();
        try {
            // Double-check (outra thread pode ter calculado entretanto)
            ProductCache cached = cache.get(productName);
            if (cached != null) {
                return cached;
            }
            
            // Calcular agregações
            int totalQtd = 0;
            float totalVol = 0;
            float maxPrice = 0;
            
            for (Evento e : eventos) {
                if (e.getProductName().equals(productName)) {
                    totalQtd += e.getQuantidade();
                    totalVol += e.getVolume();
                    maxPrice = Math.max(maxPrice, e.getPrice());
                }
            }
            
            ProductCache newCache = new ProductCache(totalQtd, totalVol, maxPrice);
            cache.put(productName, newCache);
            return newCache;
            
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public int getProductQuantity(String productName) {
        if (productName == null) throw new IllegalArgumentException("productName não pode ser null");

        // Primeiro tenta verificar se já existe cache (leitura rápida)
        rwLock.readLock().lock();
        try {
            if (cache.containsKey(productName)) {
                // Não dependemos de getters do ProductCache; para simplicidade
                // recalculamos abaixo e apenas guardamos se ainda não existir.
            }
        } finally {
            rwLock.readLock().unlock();
        }

        // Calcular apenas a quantidade a partir dos eventos (pode ser feito sob read-lock)
        int totalQtd = 0;
        rwLock.readLock().lock();
        try {
            for (Evento e : eventos) {
                if (e.getProductName().equals(productName)) {
                    totalQtd += e.getQuantidade();
                }
            }
        } finally {
            rwLock.readLock().unlock();
        }

        // Guardar em cache (se ainda não existir) - write lock
        rwLock.writeLock().lock();
        try {
            if (!cache.containsKey(productName)) {
                // Para manter consistência com o resto da classe guardamos um ProductCache completo.
                // Recriar os outros agregados mínimos (volume e maxPrice) percorre-se novamente.
                float totalVol = 0;
                float maxPrice = 0;
                for (Evento e : eventos) {
                    if (e.getProductName().equals(productName)) {
                        totalVol += e.getVolume();
                        maxPrice = Math.max(maxPrice, e.getPrice());
                    }
                }
                cache.put(productName, new ProductCache(totalQtd, totalVol, maxPrice));
            }
        } finally {
            rwLock.writeLock().unlock();
        }

        return totalQtd;
    }
    public float getProductVolume(String productName) {
        if (productName == null) throw new IllegalArgumentException("productName não pode ser null");

        float totalVol = 0f;
        rwLock.readLock().lock();
        try {
            for (Evento e : eventos) {
                if (e.getProductName().equals(productName)) {
                    totalVol += e.getVolume();
                }
            }
        } finally {
            rwLock.readLock().unlock();
        }
        return totalVol;
    }

    /**
     * Calcula o volume total do produto lendo um ficheiro de dia em streaming.
     */
    public static float computeVolumeFromFile(String filepath, String productName) throws IOException {
        try (DataInputStream dis = new DataInputStream(
                new BufferedInputStream(new FileInputStream(filepath)))) {

            dis.readInt();      // dayNumber
            dis.readBoolean();  // closed
            int numEventos = dis.readInt();

            float totalVol = 0f;
            for (int i = 0; i < numEventos; i++) {
                Evento e = Evento.deserialize(dis);
                if (e.getProductName().equals(productName)) {
                    totalVol += e.getVolume();
                }
            }
            return totalVol;
        }
    }
    /**
     * Calcula a quantidade total vendida do produto lendo um ficheiro de dia em streaming.
     * Usado quando a série não está em memória.
     */
    public static int computeQuantityFromFile(String filepath, String productName) throws IOException {
        try (DataInputStream dis = new DataInputStream(
                new BufferedInputStream(new FileInputStream(filepath)))) {

            // Ler metadados do ficheiro (compatível com saveTo)
            dis.readInt();      // dayNumber
            dis.readBoolean();  // closed
            int numEventos = dis.readInt();

            int totalQtd = 0;
            for (int i = 0; i < numEventos; i++) {
                Evento e = Evento.deserialize(dis);
                if (e.getProductName().equals(productName)) {
                    totalQtd += e.getQuantidade();
                }
            }
            return totalQtd;
        }
    }
    /**
     * Filtra eventos por conjunto de produtos.
     */
    public List<Evento> filterEvents(Set<String> products) {
        rwLock.readLock().lock();
        try {
            List<Evento> result = new ArrayList<>();
            for (Evento e : eventos) {
                if (products.contains(e.getProductName())) {
                    result.add(e);
                }
            }
            return result;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Obtém todos os eventos (cópia defensiva).
     */
    public List<Evento> getEvents() {
        rwLock.readLock().lock();
        try {
            return new ArrayList<>(eventos);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Número de eventos na série.
     */
    public int size() {
        rwLock.readLock().lock();
        try {
            return eventos.size();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Limpa o cache de agregações.
     */
    public void clearCache() {
        rwLock.writeLock().lock();
        try {
            cache.clear();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    // === PERSISTÊNCIA ===

    /**
     * Serializa a série para um ficheiro.
     */
    public void saveTo(String filepath) throws IOException {
        rwLock.readLock().lock();
        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(filepath)))) {
            
            dos.writeInt(dayNumber);
            dos.writeBoolean(closed);
            dos.writeInt(eventos.size());
            
            for (Evento e : eventos) {
                e.serialize(dos);
            }
            
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Carrega uma série de um ficheiro.
     */
    public static DaySeries loadFrom(String filepath) throws IOException {
        try (DataInputStream dis = new DataInputStream(
                new BufferedInputStream(new FileInputStream(filepath)))) {
            
            int dayNumber = dis.readInt();
            boolean closed = dis.readBoolean();
            int numEventos = dis.readInt();
            
            List<Evento> eventos = new ArrayList<>(numEventos);
            for (int i = 0; i < numEventos; i++) {
                eventos.add(Evento.deserialize(dis));
            }
            
            return new DaySeries(dayNumber, eventos, closed);
        }
    }

    /**
     * Processa eventos de um ficheiro em streaming (sem carregar tudo em memória).
     * Útil quando S séries já estão em memória.
     */
    public static ProductCache computeCacheFromFile(String filepath, String productName) throws IOException {
        try (DataInputStream dis = new DataInputStream(
                new BufferedInputStream(new FileInputStream(filepath)))) {
            
            int dayNumber = dis.readInt();
            boolean closed = dis.readBoolean();
            int numEventos = dis.readInt();
            
            int totalQtd = 0;
            float totalVol = 0;
            float maxPrice = 0;
            
            for (int i = 0; i < numEventos; i++) {
                Evento e = Evento.deserialize(dis);
                if (e.getProductName().equals(productName)) {
                    totalQtd += e.getQuantidade();
                    totalVol += e.getVolume();
                    maxPrice = Math.max(maxPrice, e.getPrice());
                }
            }
            
            return new ProductCache(totalQtd, totalVol, maxPrice);
        }
    }
}
