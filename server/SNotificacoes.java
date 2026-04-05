package server;

import java.util.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Gestor de notificações bloqueantes para o dia corrente.
 * Suporta:
 * - Vendas simultâneas: notifica quando dois produtos específicos foram vendidos
 * - Vendas consecutivas: notifica quando N vendas consecutivas do mesmo produto
 */
public class SNotificacoes {
    private final ReentrantLock lock = new ReentrantLock();
    
    // === Vendas Simultâneas ===
    private final Set<String> vendidosDiaAtual = new HashSet<>();
    private final Condition simultaneasCond;
    
    // === Vendas Consecutivas ===
    private String ultimoProduto = null;
    private int counterConsecutivas = 0;
    private int maxConsecutivas = 0;  // maior sequência até agora
    private String maxProdutosConsecutivos = null;
    private final Condition consecutivasCond;
    
    // Estado do dia
    private boolean dayEnded = false;

    public SNotificacoes() {
        this.simultaneasCond = lock.newCondition();
        this.consecutivasCond = lock.newCondition();
    }

    /**
     * Notifica uma venda (chamado pelo EventManager ao adicionar evento).
     */
    public void notifySale(String productName) {
        lock.lock();
        try {
            if (dayEnded) return;
            
            // Atualizar vendas simultâneas
            vendidosDiaAtual.add(productName);
            simultaneasCond.signalAll();
            
            // Atualizar vendas consecutivas
            if (productName.equals(ultimoProduto)) {
                counterConsecutivas++;
            } else {
                ultimoProduto = productName;
                counterConsecutivas = 1;
            }
            
            // Atualizar máximo
            if (counterConsecutivas > maxConsecutivas) {
                maxConsecutivas = counterConsecutivas;
                maxProdutosConsecutivos = productName;
            }
            
            consecutivasCond.signalAll();
            
        } finally {
            lock.unlock();
        }
    }

    /**
     * Espera até ambos os produtos terem sido vendidos no dia corrente.
     * @return true se ambos vendidos, false se dia terminou antes
     */
    public boolean waitSimultaneous(String p1, String p2) throws InterruptedException {
        lock.lock();
        try {
            while (!dayEnded && !(vendidosDiaAtual.contains(p1) && vendidosDiaAtual.contains(p2))) {
                simultaneasCond.await();
            }
            return vendidosDiaAtual.contains(p1) && vendidosDiaAtual.contains(p2);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Espera até haver N vendas consecutivas do mesmo produto.
     * @return nome do produto, ou null se dia terminou antes
     */
    public String waitConsecutive(int n) throws InterruptedException {
        lock.lock();
        try {
            while (!dayEnded && maxConsecutivas < n) {
                consecutivasCond.await();
            }
            
            if (maxConsecutivas >= n) {
                return maxProdutosConsecutivos;
            }
            return null;  // dia terminou
            
        } finally {
            lock.unlock();
        }
    }

    /**
     * Termina o dia corrente, acordando todas as threads bloqueadas.
     */
    public void endDay() {
        lock.lock();
        try {
            dayEnded = true;
            simultaneasCond.signalAll();
            consecutivasCond.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inicia um novo dia, limpando o estado.
     */
    public void newDay() {
        lock.lock();
        try {
            vendidosDiaAtual.clear();
            ultimoProduto = null;
            counterConsecutivas = 0;
            maxConsecutivas = 0;
            maxProdutosConsecutivos = null;
            dayEnded = false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Verifica se um produto já foi vendido hoje.
     */
    public boolean wasvendidosDiaAtual(String productName) {
        lock.lock();
        try {
            return vendidosDiaAtual.contains(productName);
        } finally {
            lock.unlock();
        }
    }
}
