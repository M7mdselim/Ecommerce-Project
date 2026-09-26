# CQRS in the Product Service — Capstone Analysis (Task 41)

## Overview

This document evaluates the application of the Command Query Responsibility Segregation (CQRS)
pattern within the Product microservice. It identifies **one case where CQRS is genuinely
justified** and **one case where it is not**, explaining the reasoning for each decision.

---

## ✅ Justified Case — Product Read Projection with `inStock` Display Field

### What was built

The `ProductResponse` DTO is a dedicated **read-side projection** that:

1. Exposes the data the _client_ needs (id, name, description, price, category).
2. Adds a computed, display-oriented field — **`inStock`** — derived from the product price
   without a second network call.
3. Is returned by all query endpoints (`GET /api/v1/products`, `GET /api/v1/products/{id}`,
   `GET /api/v1/products/search`).

The write side uses `CreateProductRequest` — a separate DTO validated with `@NotBlank`,
`@NotNull`, and `@DecimalMin`, which never reaches the read path.

### Why CQRS is justified here

| Criterion | Evidence |
|---|---|
| **Different shape** | The write model (`Product` entity) contains all JPA mappings. The read model (`ProductResponse`) is a lightweight record with a derived `inStock` field unsuitable for persistence. |
| **Different validation rules** | The write path enforces `price > 0`. The read path just maps and computes — no validation. A single DTO would need conditional logic or nullable constraints, making validation brittle. |
| **Cache independence** | The read projection is cached under a stable key (`products::{id}`). If the write side changed the entity structure, it would invalidate only the write path, not the serialized read cache entries. |
| **Evolvability** | Future iterations can add more display fields (e.g., `discountedPrice`, `reviewScore`) to `ProductResponse` without touching the `Product` entity or write validation. |
| **Scalability signal** | Product reads vastly outnumber writes in any catalog service. Separating the read model allows it to be optimized (Redis caching, pagination, field projection) independently of writes. |

### Code pointers

- [`ProductResponse.java`](file:///d:/Microservices Training/Ecommerce Project/product/src/main/java/com/microservices/pro/productservice/dto/ProductResponse.java) — read projection record.
- [`CreateProductRequest.java`](file:///d:/Microservices Training/Ecommerce Project/product/src/main/java/com/microservices/pro/productservice/dto/CreateProductRequest.java) — command-side DTO.
- [`ProductService.java`](file:///d:/Microservices Training/Ecommerce Project/product/src/main/java/com/microservices/pro/productservice/service/ProductService.java) — `findAll()` / `findById()` on query side; `save()` / `update()` on command side.

---

## ❌ Unjustified Case — Product Delete Operation

### What currently exists

```java
// ProductService.java
@CacheEvict(value = "products", allEntries = true)
public void deleteById(Long id) {
    productRepository.deleteById(id);
}
```

```java
// ProductController.java
@DeleteMapping("/{id}")
public ResponseEntity<Void> deleteProduct(@PathVariable Long id) {
    productService.deleteById(id);
    return ResponseEntity.noContent().build();
}
```

### Why CQRS is **not** justified here

CQRS aims to decouple a model when read and write shapes diverge meaningfully.
For deletion, there is **no read model at all** — the operation has no output projection,
carries no business-domain data, and produces only a side-effect (row removal + cache eviction).

| Criterion | Assessment |
|---|---|
| **Shape divergence** | None. The command payload is a single `Long id`. There is no corresponding read representation. Adding a separate `DeleteProductCommand` record wrapping a `Long` would be pure ceremony. |
| **Validation** | There is nothing to validate except "does the product exist?" — which is handled by a simple `Optional` check, not a DTO constraint. |
| **Separate data store** | The service uses a single PostgreSQL database. Without a dedicated read-side store (e.g., Elasticsearch or a materialized view), splitting delete into a command/event bus adds infrastructure cost with no throughput benefit. |
| **Complexity cost** | Introducing a `DeleteProductCommand`, a `ProductDeletedEvent`, and an event handler for a `DELETE /api/v1/products/{id}` endpoint would add three classes and an event bus for an operation that is already one line of repository code. |
| **Scale justification** | Delete is a low-frequency, low-latency operation. It does not need independent scaling, a read-optimized model, or a separate cache namespace. |

### Recommendation

Keep delete as a **simple, synchronous command** directly calling the repository.
CQRS should be applied when the complexity it introduces is outweighed by the benefits
of model separation, not as a blanket pattern applied to every operation.

---

## Summary Table

| Use Case | CQRS Applied? | Justified? | Key Reason |
|---|---|---|---|
| Product reads (all / by-id / search) | ✅ Yes | ✅ Yes | Read shape differs from write shape; `inStock` display field; caching independence; evolvability |
| Product delete | ❌ No | ✅ Correct decision | No output model; no validation; one-line repository call; adding CQRS would be pure over-engineering |

---

## Further Reading

- Martin Fowler — [CQRS](https://martinfowler.com/bliki/CQRS.html)
- Microsoft — [CQRS Pattern](https://learn.microsoft.com/en-us/azure/architecture/patterns/cqrs)
- Greg Young — "CQRS Documents" (2010)
