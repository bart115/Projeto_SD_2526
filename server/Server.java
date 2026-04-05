package server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Servidor principal do sistema de séries temporais.
 * 
 * Parâmetros de configuração:
 * - D: número máximo de dias a manter
 * - S: número máximo de séries em memória (S < D)
 * - PORT: porta TCP
 */
public class Server {
    // Configuração por defeito
    private static final int DEFAULT_PORT = 12345;
    private static final int DEFAULT_D = 3;    // 30 dias de histórico
    private static final int DEFAULT_S = 2;     // 5 séries em memória
    private static final String DEFAULT_DATA_DIR = "./data";

    public static void main(String[] args) {
        // Parsing de argumentos (opcional)
        int port = DEFAULT_PORT;
        int D = DEFAULT_D;
        int S = DEFAULT_S;
        String dataDir = DEFAULT_DATA_DIR;
        

        //Override aos valores default
        for (int i = 0; i < args.length - 1; i += 2) {
            switch (args[i]) {
                case "-p":
                case "--port":
                    port = Integer.parseInt(args[i + 1]);
                    break;
                case "-d":
                case "--days":
                    D = Integer.parseInt(args[i + 1]);
                    break;
                case "-s":
                case "--series":
                    S = Integer.parseInt(args[i + 1]);
                    break;
                case "--data":
                    dataDir = args[i + 1];
                    break;
            }
        }
        
        // Validar S < D
        if (S >= D) {
            System.err.println("Erro: S tem q ser menor que D");
            System.exit(1);
        }
        
        System.out.println("Server Online!");
        
        // Inicializar componentes
        Autenticador autenticador = new Autenticador();
        SNotificacoes managerNotificacoes = new SNotificacoes();
        EventManager eventManager = new EventManager(D, S, dataDir, managerNotificacoes);
        ThreadPool threadPool = new ThreadPool(20);  // Pool GLOBAL com 20 threads

        System.out.println("ThreadPool global criado com 20 threads para processar pedidos.");

        // Iniciar servidor
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Servidor a escutar na porta " + port + "...");

            while (true) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    System.out.println("Conexão establecida");

                    // Criar handler para o novo cliente (passa pool global)
                    ClientHandler handler = new ClientHandler(
                        clientSocket, autenticador, eventManager, managerNotificacoes, threadPool);

                    // Criar uma thread para receber pedidos do cliente
                    new Thread(handler).start();
                    
                } catch (IOException e) {
                    System.err.println("Erro. Não foi possivel establecer conexão: ");
                }
            }
            
        } catch (IOException e) {
            System.err.println("Erro do servidor: ");
            e.printStackTrace();
        }
    }
}
