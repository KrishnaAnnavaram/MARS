package com.example.order;

import javax.validation.constraints.NotNull;

public class OrderRequest {
    @NotNull
    private String sku;
}
