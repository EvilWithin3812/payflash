package com.payflash.sale;

import com.payflash.common.BusinessException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Small in-memory cache of sale definitions. The reserve endpoint is the hot path of a flash sale:
 * without this, every one of thousands of requests per second would read the same row from Postgres.
 * Sales are immutable once created, so a short TTL is only there to pick up newly created sales
 * on other instances.
 */
@Component
public class SaleCatalog {

    private static final long TTL_NANOS = Duration.ofSeconds(30).toNanos();
    private static final long LIST_TTL_NANOS = Duration.ofSeconds(2).toNanos();

    private final SaleRepository sales;
    private final Map<Long, Entry> cache = new ConcurrentHashMap<>();
    private volatile ListEntry notEnded;

    public SaleCatalog(SaleRepository sales) {
        this.sales = sales;
    }

    public Sale get(long saleId) {
        Entry e = cache.get(saleId);
        if (e != null && System.nanoTime() - e.loadedAt < TTL_NANOS) {
            return e.sale;
        }
        Sale sale = sales.findById(saleId).orElseThrow(() -> BusinessException.notFound("Sale not found"));
        cache.put(saleId, new Entry(sale, System.nanoTime()));
        return sale;
    }

    /** Upcoming and live sales, cached briefly (used by the stock broadcaster twice a second). */
    public List<Sale> notEnded() {
        ListEntry e = notEnded;
        if (e != null && System.nanoTime() - e.loadedAt < LIST_TTL_NANOS) {
            return e.sales;
        }
        List<Sale> fresh = sales.findNotEnded(java.time.Instant.now());
        notEnded = new ListEntry(fresh, System.nanoTime());
        return fresh;
    }

    public void put(Sale sale) {
        cache.put(sale.getId(), new Entry(sale, System.nanoTime()));
        notEnded = null;
    }

    private record Entry(Sale sale, long loadedAt) {
    }

    private record ListEntry(List<Sale> sales, long loadedAt) {
    }
}
