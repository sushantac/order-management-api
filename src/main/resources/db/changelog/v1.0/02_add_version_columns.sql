--liquibase formatted sql

-- ============================================================================
-- PR #8 - Optimistic locking: add a version column to every table.
-- ----------------------------------------------------------------------------
-- Optimistic locking needs a per-row counter that Hibernate increments on
-- every UPDATE ("WHERE version = <read value>"). If the row changed in the
-- meantime, the UPDATE affects 0 rows and Hibernate throws an
-- OptimisticLockingFailureException - the lost-update is prevented.
--
-- DEFAULT 0 keeps existing rows valid and matches Hibernate's @Version start.
-- ============================================================================

--changeset sushant:8-add-version-to-customers
ALTER TABLE customers ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
--rollback ALTER TABLE customers DROP COLUMN version;

--changeset sushant:8-add-version-to-addresses
ALTER TABLE addresses ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
--rollback ALTER TABLE addresses DROP COLUMN version;

--changeset sushant:8-add-version-to-orders
ALTER TABLE orders ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
--rollback ALTER TABLE orders DROP COLUMN version;

--changeset sushant:8-add-version-to-order-items
ALTER TABLE order_items ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
--rollback ALTER TABLE order_items DROP COLUMN version;

--changeset sushant:8-add-version-to-products
ALTER TABLE products ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
--rollback ALTER TABLE products DROP COLUMN version;

--changeset sushant:8-add-version-to-categories
ALTER TABLE categories ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
--rollback ALTER TABLE categories DROP COLUMN version;

--changeset sushant:8-add-version-to-product-categories
ALTER TABLE product_categories ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
--rollback ALTER TABLE product_categories DROP COLUMN version;

--changeset sushant:8-add-version-to-payments
ALTER TABLE payments ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
--rollback ALTER TABLE payments DROP COLUMN version;
