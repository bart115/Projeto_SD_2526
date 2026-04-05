#!/bin/bash
# Compilar o projeto
# Criar diretório de classes
mkdir -p build
# Compilar common
echo "Common."
javac -d build ./common/*.java

# Compilar server
echo "server."
javac -cp build -d build ./server/*.java

# Compilar client
# echo "client."
javac -cp build -d build ./client/*.java

# Compilar testes (se existirem)
if [ -d "testes" ]; then
    echo "testes."
    javac -cp build -d build ./testes/*.java 2>/dev/null
fi

echo "Compilação concluída!"
echo ""
echo "Para executar:"
echo "  Servidor: java -cp build server.Server"
echo "  Cliente:  java -cp build client.Client_UI"
echo "  Testes:   java -cp build testes.Testes"
