package com.example.ledger;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.hibernate.annotations.Type;

@Entity
public class Entry {
    @Id
    private Long id;

    @Type(type = "jsonb")
    private String payload;
}
