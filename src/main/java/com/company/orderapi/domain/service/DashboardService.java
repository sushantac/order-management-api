package com.company.orderapi.domain.service;

import com.company.orderapi.domain.repository.CustomerRepository;
import com.company.orderapi.domain.repository.OrderRepository;
import com.company.orderapi.domain.repository.ProductRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * PR #30 - fan-out with VIRTUAL THREADS.
 *
 * <p>A dashboard page needs three INDEPENDENT aggregates (customer count,
 * product count, total revenue). Sequential reads add their latencies; instead
 * we fan out three tasks onto a virtual-thread executor, run them
 * concurrently, then join all results before returning.
 *
 * <p>This is structured concurrency: the executor is created and CLOSED in a
 * try-with-resources block, so every child task has finished (or failed)
 * before the method returns - no orphan threads can outlive the operation.
 * JDK 22+ models the same idea natively as {@code StructuredTaskScope}; we stay
 * preview-free on the Java 21 build target.
 */
@Service
public class DashboardService {

    private final CustomerRepository customers;
    private final ProductRepository products;
    private final OrderRepository orders;

    public DashboardService(CustomerRepository customers,
                            ProductRepository products,
                            OrderRepository orders) {
        this.customers = customers;
        this.products = products;
        this.orders = orders;
    }

    /** Independent aggregates of the whole catalogue. */
    public record DashboardSummary(long customerCount, long productCount,
                                   BigDecimal totalRevenue) {
    }

    public DashboardSummary fetch() {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<Long> customerCount = customers::count;
            Callable<Long> productCount = products::count;
            Callable<BigDecimal> revenue = orders::totalRevenue;
            Future<Long> customersFuture = executor.submit(customerCount);
            Future<Long> productsFuture = executor.submit(productCount);
            Future<BigDecimal> revenueFuture = executor.submit(revenue);
            return new DashboardSummary(customersFuture.get(), productsFuture.get(),
                    revenueFuture.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Dashboard fan-out interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Dashboard fan-out failed", e.getCause());
        }
    }
}
