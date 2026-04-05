package server;

/**
 * Cache de agregações para um produto específico num dia.
 * Armazena:
 * - Quantidade total vendida
 * - Volume total (quantidade * preço)
 * - Preço máximo
 */
public class ProductCache {
    private final int totalQtd;
    private final float totalVol;
    private final float maxPrice;

    public ProductCache(int totalQtd, float totalVol, float maxPrice) {
        this.totalQtd = totalQtd;
        this.totalVol = totalVol;
        this.maxPrice = maxPrice;
    }
    public int getTotalQtd() {
        return totalQtd;
    }

    public float getTotalVol() {
        return totalVol;
    }

    public float getMaxPrice() {
        return maxPrice;
    }
    /**
     * Calcula o preço médio.
     */
    public float getAveragePrice() {
        if (totalQtd == 0) return 0;
        return totalVol / totalQtd;
    }
}
