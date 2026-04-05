#!/bin/bash
# Limpar o projeto
echo "Purgato."

# Remover diretório build
if [ -d "build" ]; then
    rm -rf build
    echo "Diretório 'build' removido."
else
    echo "Diretório 'build' não encontrado."
fi

# Se parâmetro for "all", remover também data
if [ "$1" = "all" ]; then
    if [ -d "data" ]; then
        rm -rf data
        echo "Diretório 'data' removido."
    else
        echo "Diretório 'data' não encontrado."
    fi
fi

echo "Limpeza concluída!"
