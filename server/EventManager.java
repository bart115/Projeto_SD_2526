package server;

import common.Evento;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Gestor central de eventos e séries temporais.
 * Responsável por:
 * - Gerir o dia atual e dias anteriores
 * - Adicionar eventos
 * - Calcular o que for preciso
 * - Persistência e gestão da memória (limite S)
 */
public class EventManager {
    private final int D;  // número máximo de dias a manter
    private final int S;  // número máximo de séries em memória
    private final String dataDir;  // diretoria para persistência
    
    private int currentDay;
    private DaySeries currentSeries;
    
    // Séries em memória (excluindo a atual)
    private final Map<Integer, DaySeries> memorySeries;
    private final LinkedList<Integer> lista;
    
    private final ReentrantLock lock;
    private final SNotificacoes notification;

    public EventManager(int D, int S, String dataDir, SNotificacoes notification) {
        this.D = D;
        this.S = S;
        this.dataDir = dataDir;
        this.notification = notification;
        
        this.currentDay = determineCurrentDay();
        this.currentSeries = new DaySeries(currentDay);
        
        this.memorySeries = new HashMap<>();
        this.lista = new LinkedList<>();
        
        this.lock = new ReentrantLock();
        
        // Cria a diretoria de dados se não existir
        new File(dataDir).mkdirs();
    }

    //adiciona um evento ao dia corrente
    public void addEvent(Evento evento) {
        lock.lock();
        try {
            // Adicionar evento ao dia corrente
            currentSeries.addEvent(evento);

            // Notificar o sistema de notificações
            notification.notifySale(evento.getProductName());
        } finally {
            lock.unlock();
        }
    }

    // Avança para o proximo dia
    public void newDay() throws IOException {
        lock.lock();
        try {
            // Fechar o dia corrente
            currentSeries.close();
            int numEventos = currentSeries.size();

            // Notificar sistema de notificações que o dia terminou
            notification.endDay();

            // Persistir o dia corrente
            String filepath = getFilepath(currentDay);
            currentSeries.saveTo(filepath);
            System.out.println("[EventManager] Dia " + currentDay + " persistido: " + numEventos + " eventos -> " + filepath);

            // Adicionar à memória se houver espaço
            if (memorySeries.size() < S) {
                memorySeries.put(currentDay, currentSeries);
                lista.addLast(currentDay);
                System.out.println("[EventManager] Dia " + currentDay + " mantido em memória (" + memorySeries.size() + "/" + S + " séries)");
            } else {
                System.out.println("[EventManager] Dia " + currentDay + " descartado da memória (limite S=" + S + " atingido)");
            }

            // Remover dias muito antigos (mais de D dias atrás)
            int oldestDay = currentDay - D;
            if (oldestDay > 0) {
                memorySeries.remove(oldestDay);
                lista.remove((Integer) oldestDay);
                System.out.println("[EventManager] Dia " + oldestDay + " removido da cache(S=" + S + " dias atrás)");
            }

            // Criar novo dia
            currentDay++;
            currentSeries = new DaySeries(currentDay);
            notification.newDay();
            System.out.println("[EventManager] Novo dia iniciado: dia " + currentDay);

        } finally {
            lock.unlock();
        }
    }

    // Devolve o dia atual em int
    public int getCurrentDay() {
        lock.lock();
        try {
            return currentDay;
        } finally {
            lock.unlock();
        }
    }

    // java
// Inserir/ substituir na classe `server/EventManager.java`
    public int queryQuantity(String productName, int d) throws IOException {
        if (productName == null || productName.trim().isEmpty()) {
            throw new IllegalArgumentException("productName não pode ser vazio");
        }
        if (d < 1 || d > D) {
            throw new IllegalArgumentException("d deve estar em 1.." + D);
        }

        lock.lock();
        try {
            int sum = 0;
            for (int i = 1; i <= d; i++) {
                int day = currentDay - i;
                if (day <= 0) break; // sem dias anteriores além do início

                DaySeries ds = memorySeries.get(day);
                if (ds != null) {
                    // série em memória -> usar método que calcula (e cacheia) em memória
                    sum += ds.getProductQuantity(productName);
                } else {
                    // série não em memória -> tentar ler ficheiro em streaming
                    String filepath = getFilepath(day);
                    File f = new File(filepath);
                    if (f.exists()) {
                        sum += DaySeries.computeQuantityFromFile(filepath, productName);
                    } // caso contrário, não há dados para somar
                }
            }
            return sum;
        } finally {
            lock.unlock();
        }
    }

    public float queryVolume(String productName, int d) throws IOException {
        if (productName == null || productName.trim().isEmpty()) {
            throw new IllegalArgumentException("productName não pode ser vazio");
        }
        if (d < 1 || d > D) {
            throw new IllegalArgumentException("d deve estar em 1.." + D);
        }

        lock.lock();
        try {
            float sum = 0f;
            for (int i = 1; i <= d; i++) {
                int day = currentDay - i;
                if (day <= 0) break; // sem dias anteriores além do início

                DaySeries ds = memorySeries.get(day);
                if (ds != null) {
                    // série em memória -> usar método que calcula (e cacheia) em memória
                    sum += ds.getProductVolume(productName);
                } else {
                    // série não em memória -> tentar ler ficheiro em streaming
                    String filepath = getFilepath(day);
                    File f = new File(filepath);
                    if (f.exists()) {
                        sum += DaySeries.computeVolumeFromFile(filepath, productName);
                    } // caso contrário, não há dados para somar
                }
            }
            return sum;
        } finally {
            lock.unlock();
        }
    }

    public float queryAvgPrice(String productName, int d) throws IOException {
        if (productName == null || productName.trim().isEmpty()) {
            throw new IllegalArgumentException("productName não pode ser vazio");
        }
        if (d < 1 || d > D) {
            throw new IllegalArgumentException("d deve estar em 1.." + D);
        }

        lock.lock();
        try {
            int totalQtd = 0;
            float totalVol = 0f;

            for (int i = 1; i <= d; i++) {
                int day = currentDay - i;
                if (day <= 0) break; // sem dias anteriores além do início

                DaySeries ds = memorySeries.get(day);
                if (ds != null) {
                    totalQtd += ds.getProductQuantity(productName);
                    totalVol += ds.getProductVolume(productName);
                } else {
                    String filepath = getFilepath(day);
                    File f = new File(filepath);
                    if (f.exists()) {
                        totalQtd += DaySeries.computeQuantityFromFile(filepath, productName);
                        totalVol += DaySeries.computeVolumeFromFile(filepath, productName);
                    }
                }
            }

            if (totalQtd == 0) return 0.0f;
            return totalVol / totalQtd;
        } finally {
            lock.unlock();
        }
    }

    public float queryMaxPrice(String productName, int d) throws IOException {
        if (productName == null || productName.trim().isEmpty()) {
            throw new IllegalArgumentException("productName não pode ser vazio");
        }
        if (d < 1 || d > D) {
            throw new IllegalArgumentException("d deve estar em 1.." + D);
        }

        lock.lock();
        try {
            float globalMax = 0f;
            boolean found = false;

            for (int i = 1; i <= d; i++) {
                int day = currentDay - i;
                if (day <= 0) break; // sem dias anteriores além do início

                DaySeries ds = memorySeries.get(day);
                if (ds != null) {
                    // usar cache em memória
                    ProductCache pc = ds.getProductCache(productName);
                    float dayMax = pc != null ? pc.getMaxPrice() : 0f;
                    if (pc != null && pc.getTotalQtd() > 0) {
                        found = true;
                        globalMax = Math.max(globalMax, dayMax);
                    } else if (dayMax > 0f) {
                        // mesmo sem quantidade explícita, considerar preço se presente
                        found = true;
                        globalMax = Math.max(globalMax, dayMax);
                    }
                } else {
                    // série não em memória -> tentar ler ficheiro em streaming usando computeCacheFromFile
                    String filepath = getFilepath(day);
                    File f = new File(filepath);
                    if (f.exists()) {
                        ProductCache pc = DaySeries.computeCacheFromFile(filepath, productName);
                        float dayMax = pc != null ? pc.getMaxPrice() : 0f;
                        if (pc != null && pc.getTotalQtd() > 0) {
                            found = true;
                            globalMax = Math.max(globalMax, dayMax);
                        } else if (dayMax > 0f) {
                            found = true;
                            globalMax = Math.max(globalMax, dayMax);
                        }
                    }
                }
            }

            return found ? globalMax : 0.0f;
        } finally {
            lock.unlock();
        }
    }
    // FILTRAR EVENTOS
    /**
     * Filtra eventos de um dia específico por conjunto de produtos.
     */
    public List<Evento> filterEvents(Set<String> products, int dayOffset) throws IOException {
        if (products == null || products.isEmpty()) {
            throw new IllegalArgumentException("products não pode ser vazio");
        }
        if (dayOffset < 1 || dayOffset > D) {
            throw new IllegalArgumentException("dayOffset deve estar em 1.." + D);
        }

        lock.lock();
        try {
            int targetDay = currentDay - dayOffset;
            if (targetDay <= 0) {
                // Dia não existe (antes do início)
                return new ArrayList<>();
            }

            // Tentar obter série em memória
            DaySeries ds = memorySeries.get(targetDay);
            if (ds != null) {
                // Série em memória -> usar método de filtragem
                return ds.filterEvents(products);
            } else {
                // Série não em memória -> carregar do ficheiro
                String filepath = getFilepath(targetDay);
                File f = new File(filepath);
                if (f.exists()) {
                    // Carregar série do ficheiro e filtrar
                    DaySeries loadedSeries = DaySeries.loadFrom(filepath);
                    return loadedSeries.filterEvents(products);
                } else {
                    // Ficheiro não existe
                    return new ArrayList<>();
                }
            }
        } finally {
            lock.unlock();
        }
    }


     private int determineCurrentDay(){
        int currentDay = 0;
        File directory = new File(dataDir);

        System.out.println("[EventM :: determineCurrentDay]: dataDir:  " + dataDir);
        
        File[] files = directory.listFiles();

        if (files == null){
            System.out.println("determineCurrentDay: files is null" );
            
            return 1;
        }

        for (File file : files){
            System.out.println(file.getName());
            
            int day = parseDataFileNumber(file.getName());
            System.out.println("Dia: " + day);
            
            if (currentDay < day){
                currentDay = day;
            }    
       }
        int c = currentDay+1;
        System.out.println("CurrentDay: " + c);
       return currentDay + 1;
    }
    
    private int parseDataFileNumber(String filename) {
        if (filename == null || !filename.startsWith("day_") || !filename.endsWith(".dat")) {
            return -1;
        }
        try {
            // Extrai a substring entre    "day_"(4 chars)   e   ".dat" (4 chars do fim)
            String numberStr = filename.substring(4, filename.length() - 4);
            return Integer.parseInt(numberStr);
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            return -1;
        }
    }


    //dá o caminho do ficheiro de um determinado dia
    private String getFilepath(int day) {
        return dataDir + "/day_" + day + ".dat";
    }
}
