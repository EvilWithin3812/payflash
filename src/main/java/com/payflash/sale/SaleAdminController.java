package com.payflash.sale;

import com.payflash.sale.SaleDtos.CreateSaleRequest;
import com.payflash.sale.SaleDtos.SaleResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/sales")
@Tag(name = "Admin")
public class SaleAdminController {

    private final SaleService sales;

    public SaleAdminController(SaleService sales) {
        this.sales = sales;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a flash sale and load its units into Redis (ADMIN only)")
    public SaleResponse create(@Valid @RequestBody CreateSaleRequest request) {
        return sales.create(request);
    }

    @PostMapping("/{saleId}/rebuild-inventory")
    @Operation(summary = "Rebuild a sale's Redis pool from the database, e.g. after Redis data loss (ADMIN only)")
    public SaleResponse rebuild(@PathVariable long saleId) {
        return sales.rebuildInventory(saleId);
    }
}
