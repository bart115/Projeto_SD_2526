package client;

import java.io.*;
import java.util.*;
import common.*;

public class Client_UI{
    private Scanner scanner;
    private Client client;
    private RequestBuffer requestBuffer;

    public Client_UI(Client client_rec) {
        this.scanner = new Scanner(System.in);
        this.client = client_rec;
        this.requestBuffer = new RequestBuffer();  // Buffer com 1 thread worker
    }

    /**
     * Mostra o UI de login, pede username e password.
     * Tenta invocar client.login(...) e retorna true se o login for bem-sucedido.
     */
    public boolean loginUI(){
        System.out.println("\n=== Login ===");
        System.out.print("Username: ");
        String username = scanner.nextLine().trim();
        System.out.print("Password: ");
        String password = scanner.nextLine(); // não trim para respeitar espaços na password

        try {
            boolean ok = client.login(username, password);
            if (ok) {
                System.out.println("Login bem-sucedido.");
                return true;
            } else {
                System.out.println("Login falhou.");
                return false;
            }
        } catch (IOException e) {
            System.err.println("Erro durante login: " + e.getMessage());
            return false;
        } catch (Exception e) {
            System.err.println("Erro inesperado: " + e.getMessage());
            return false;
        }
    }

    public boolean registerUI() {
        System.out.println("\n=== Registar ===");
        System.out.print("Username: ");
        String username = scanner.nextLine().trim();
        System.out.print("Password: ");
        String password = scanner.nextLine(); // não trim para respeitar espaços na password

        try {
            boolean ok = client.register(username, password);
            if (ok) {
                System.out.println("Registo bem-sucedido.");
                return true;
            } else {
                System.out.println("Registo falhou.");
                return false;
            }
        } catch (IOException e) {
            System.err.println("Erro durante registo: " + e.getMessage());
            return false;
        } catch (Exception e) {
            System.err.println("Erro inesperado: " + e.getMessage());
            return false;
        }
    }

    public void registoLoginMenu() {
        while (true) {
            System.out.println("\n=== Registo / Login ===");
            System.out.println("0 - Login");
            System.out.println("1 - Registar");
            System.out.print("> ");
            String line = scanner.nextLine().trim();
            if (line.equals("0") || line.equals("1")) {

                if (line.equals("0")){
                    if (loginUI()){
                        try {
                            showMainMenu();
                        } catch(InterruptedException | IOException e){
                            e.printStackTrace();
                        }
                    }
                } else if (line.equals("1")){
                    registerUI();
                }

            } else {
                System.out.println("Opção inválida. Por favor escolha 0 ou 1.");
            }
        }
    }

    private void addEventUI() throws IOException {
        System.out.print("Nome do produto: ");
        String productName = scanner.nextLine().trim();
        System.out.print("Quantidade: ");
        int quantidade = Integer.parseInt(scanner.nextLine().trim());
        System.out.print("Preço: ");
        float price = Float.parseFloat(scanner.nextLine().trim());
        client.addEvent(productName, quantidade, price);
        System.out.println("\nEvento adicionado com sucesso!");
    }

    private void newDayUI() {
        if (!client.isAdmin()) {
            System.out.println("Opção não disponível: apenas administradores podem iniciar novo dia.");
            return;
        }
        requestBuffer.submit(() -> {
            try {
                System.out.println("A processar novo dia...");
                client.newDay();
                System.out.println("Novo dia iniciado com sucesso!");
            } catch (Exception e) {
                System.err.println("Erro ao processar novo dia: " + e.getMessage());
            }
        });
        System.out.println("Pedido de novo dia enviado (a processar em background)...");
    }

    private void queryQuantityUI() throws IOException, InterruptedException {
        System.out.println("\n=== Consultar quantidade ===");
        System.out.print("ID do produto: ");
        String product = scanner.nextLine().trim();
        System.out.print("Número de dias (d): ");
        String line = scanner.nextLine().trim();
        int d;
        try {
            d = Integer.parseInt(line);
        } catch (NumberFormatException e) {
            System.out.println("Número de dias inválido.");
            return;
        }

        try {
            int qty = client.doQueryQuantity(product, d); // mudar isto para ser o metodo no cliente
            System.out.println("Quantidade vendida nos últimos " + d + " dias (excluindo o dia corrente): " + qty);
        } catch (IOException e) {
            System.err.println("Erro ao consultar quantidade: " + e.getMessage());
        }
    }

    private void queryVolumeUI() throws IOException, InterruptedException {
        System.out.println("\n=== Consultar volume ===");
        System.out.print("ID do produto: ");
        String product = scanner.nextLine().trim();
        System.out.print("Número de dias (d): ");
        String line = scanner.nextLine().trim();
        int d;
        try {
            d = Integer.parseInt(line);
        } catch (NumberFormatException e) {
            System.out.println("Número de dias inválido.");
            return;
        }

        try {
            float volume = client.doQueryVolume(product, d);
            System.out.printf("Volume vendido de '%s' nos últimos %d dias (excluindo o dia corrente): %.2f%n", product, d, volume);
        } catch (IOException e) {
            System.err.println("Erro ao consultar volume: " + e.getMessage());
        }
    }

    private void queryAvgPriceUI() throws IOException, InterruptedException {
        System.out.println("\n=== Consultar preço médio ===");
        System.out.print("ID do produto: ");
        String product = scanner.nextLine().trim();
        System.out.print("Número de dias (d): ");
        String line = scanner.nextLine().trim();
        int d;
        try {
            d = Integer.parseInt(line);
        } catch (NumberFormatException e) {
            System.out.println("Número de dias inválido.");
            return;
        }

        try {
            float avg = client.doQueryAvgPrice(product, d);
            System.out.printf("Preço médio de venda de '%s' nos últimos %d dias (excluindo o dia corrente): %.2f%n", product, d, avg);
        } catch (IOException e) {
            System.err.println("Erro ao consultar preço médio: " + e.getMessage());
        }
    }

    private void queryMaxPriceUI() throws IOException, InterruptedException {
        System.out.println("\n=== Consultar preço máximo ===");
        System.out.print("ID do produto: ");
        String product = scanner.nextLine().trim();
        System.out.print("Número de dias (d): ");
        String line = scanner.nextLine().trim();
        int d;
        try {
            d = Integer.parseInt(line);
        } catch (NumberFormatException e) {
            System.out.println("Número de dias inválido.");
            return;
        }

        try {
            float max = client.doQueryMaxPrice(product, d);
            if (max == 0.0f) {
                System.out.printf("Nenhuma venda encontrada para '%s' nos últimos %d dias (excluindo o dia corrente).%n", product, d);
            } else {
                System.out.printf("Preço máximo de venda de '%s' nos últimos %d dias (excluindo o dia corrente): %.2f%n", product, d, max);
            }
        } catch (IOException e) {
            System.err.println("Erro ao consultar preço máximo: " + e.getMessage());
        }
    }

    private void filterEventsUI() throws IOException, InterruptedException {
        System.out.println("\n=== Filtrar eventos ===");
        System.out.print("Produtos (separados por vírgula): ");
        String productsInput = scanner.nextLine().trim();

        if (productsInput.isEmpty()) {
            System.out.println("Nenhum produto especificado.");
            return;
        }

        // Criar conjunto de produtos
        Set<String> products = new HashSet<>();
        for (String product : productsInput.split(",")) {
            String trimmed = product.trim();
            if (!trimmed.isEmpty()) {
                products.add(trimmed);
            }
        }

        if (products.isEmpty()) {
            System.out.println("Nenhum produto válido especificado.");
            return;
        }

        System.out.print("Dia anterior (d): ");
        String line = scanner.nextLine().trim();
        int d;
        try {
            d = Integer.parseInt(line);
        } catch (NumberFormatException e) {
            System.out.println("Número de dias inválido.");
            return;
        }

        try {
            List<Evento> eventos = client.doFilterEvents(products, d);
            System.out.printf("\n=== Eventos filtrados (dia -%d, produtos: %s) ===%n", d, products);
            System.out.printf("Total de eventos: %d%n%n", eventos.size());

            if (eventos.isEmpty()) {
                System.out.println("Nenhum evento encontrado.");
            } else {
                System.out.println("Detalhes dos eventos:");
                System.out.println("-----------------------------------");
                for (Evento e : eventos) {
                    System.out.printf("Produto: %s | Quantidade: %d | Preço: %.2f | Volume: %.2f%n",
                            e.getProductName(), e.getQuantidade(), e.getPrice(), e.getVolume());
                }
                System.out.println("-----------------------------------");
            }
        } catch (IOException e) {
            System.err.println("Erro ao filtrar eventos: " + e.getMessage());
        }
    }

    private void waitSimultaneousUI() throws IOException {
        System.out.print("Produto 1: ");
        String p1 = scanner.nextLine().trim();
        System.out.print("Produto 2: ");
        String p2 = scanner.nextLine().trim();
        System.out.println("A acompanhar vendas simultâneas em background...");
        client.esperaSimultaneosAsync(p1, p2, result -> {
            if (result) {
                System.out.println("[Notificação] Ambos os produtos foram vendidos!");
            } else {
                System.out.println("[Notificação] Dia terminou antes de ambos serem vendidos.");
            }
        });
    }

    private void waitConsecutiveUI() throws IOException {
        System.out.print("Número de vendas consecutivas: ");
        int n = Integer.parseInt(scanner.nextLine().trim());
        System.out.println("A acompanhar vendas consecutivas em background...");
        client.esperaConsecutivosAsync(n, produto -> {
            if (produto != null) {
                System.out.println("[Notificação] Produto com " + n + " vendas consecutivas: " + produto);
            } else {
                System.out.println("[Notificação] Dia terminou antes de haver vendas consecutivas.");
            }
        });
    }

    private void logoutUI() {
        try {
            client.logout();
        } catch (Exception e) {
            System.err.println("Erro no logout: " + e.getMessage());
        }
        requestBuffer.shutdown();
        requestBuffer = new RequestBuffer();  // Criar novo buffer para próximo login
        System.out.println("Logout efetuado.");
    }


    public void showMainMenu() throws IOException, InterruptedException {
        while (true) {
            System.out.println("\n=== Menu Principal ===");
            System.out.println("1. Adicionar evento");
            if (client.isAdmin()) {
                System.out.println("2. Novo dia");
            }
            System.out.println("3. Consultar quantidade");
            System.out.println("4. Consultar volume");
            System.out.println("5. Consultar preço médio");
            System.out.println("6. Consultar preço máximo");
            System.out.println("7. Filtrar eventos");
            System.out.println("8. Esperar vendas simultâneas");
            System.out.println("9. Esperar vendas consecutivas");
            System.out.println("0. Logout");
            System.out.print("> ");

            String choice = scanner.nextLine().trim();

            try {
                switch (choice) {
                    case "1":
                        addEventUI();
                        break;
                    case "2":
                        newDayUI();
                        break;
                    case "3":
                        queryQuantityUI();
                        break;
                    case "4":
                        queryVolumeUI();
                        break;
                    case "5":
                        queryAvgPriceUI();
                        break;
                    case "6":
                        queryMaxPriceUI();
                        break;
                    case "7":
                        filterEventsUI();
                        break;
                    case "8":
                        waitSimultaneousUI();
                        break;
                    case "9":
                        waitConsecutiveUI();
                        break;
                    case "0":
                        logoutUI();
                        return;  // Volta para o menu de login
                    default:
                        System.out.println("Opção inválida");
                }
            } catch (IOException e) {
                System.err.println("Erro do serviço: " + e.getMessage());
            }
        }
    }

    public static void main(String[] args) {
        try (Client client = new Client()) {
            String host = "localhost";
            int port = 12345;

            System.out.println("A conectar...");
            client.connect(host, port);
            System.out.println("Conectado!\n");

            Client_UI cUI = new Client_UI(client);
            cUI.registoLoginMenu();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}