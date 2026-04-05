#!/bin/bash
# Script para executar os testes de concorrência
echo "=== Setup para Testes de Concorrência ==="
echo ""

# 1. Limpar e compilar
echo "[1/4] Limpar o projeto..."
./clean.sh all
echo ""

echo "[2/4] Compilando projeto + testes..."
./compile.sh
if [ $? -ne 0 ]; then
    echo "✗ Erro na compilação!"
    exit 1
fi
echo ""

# 2. Iniciar servidor em background
echo "[3/4] Iniciar o servidor em background..."
java -cp build server.Server &
SERVER_PID=$!
echo "Servidor iniciado (PID: $SERVER_PID)"
echo "Aguardando servidor estar pronto..."
sleep 3
echo ""

# 3. Executar testes
echo "[4/4] Executando testes..."
echo ""
java -cp build testes.Testes
TEST_EXIT_CODE=$?
echo ""

# 4. Parar servidor
echo "Parando servidor (PID: $SERVER_PID)..."
kill $SERVER_PID 2>/dev/null
wait $SERVER_PID 2>/dev/null
echo ""

# 5. Resultado final
if [ $TEST_EXIT_CODE -eq 0 ]; then
    echo "=== ✓ Todos os testes passaram! ==="
    exit 0
else
    echo "=== ✗ Alguns testes falharam! ==="
    exit 1
fi
