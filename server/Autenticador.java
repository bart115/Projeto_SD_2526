package server;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Gestor de autenticação de utilizadores.
 * Mantém registo de utilizadores e passwords.
 * Thread-safe.
 */
public class Autenticador {
    private final Map<String, String> users;  // username -> password
    private final Map<String, Boolean> admins;  // username -> isAdmin
    private final ReentrantLock lock;

    public Autenticador() {
        this.users = new HashMap<>();
        this.admins = new HashMap<>();
        this.lock = new ReentrantLock();

        // Hardcoded admin users
        this.users.put("tacus", "tacus");
        this.admins.put("tacus", true);

        this.users.put("bart", "bart");
        this.admins.put("bart", true);
    }

    /**
     * Regista um novo utilizador (não-admin por defeito).
     * @return true se registado com sucesso, false se username já existe
     */
    public boolean regista(String username, String password) {
        lock.lock();
        try {
            if (users.containsKey(username)) {
                return false;
            }
            users.put(username, password);
            admins.put(username, false);  // novos users não são admins
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Autentica um utilizador.
     * @return true se credenciais válidas, false caso contrário
     */
    public boolean autentica(String username, String password) {
        lock.lock();
        try {
            String storedPassword = users.get(username);
            return storedPassword != null && storedPassword.equals(password);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Verifica se um utilizador existe.
     */
    public boolean userExists(String username) {
        lock.lock();
        try {
            return users.containsKey(username);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Verifica se um utilizador é administrador.
     * @return true se é admin, false caso contrário
     */
    public boolean isAdmin(String username) {
        lock.lock();
        try {
            return admins.getOrDefault(username, false);
        } finally {
            lock.unlock();
        }
    }

}