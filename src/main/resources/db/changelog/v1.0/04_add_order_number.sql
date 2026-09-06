--liquibase formatted sql

-- ============================================================================
-- PR #13 - order_number column.
-- ----------------------------------------------------------------------------
-- @PrePersist on Order generates a human-friendly order number. The column is
-- nullable so pre-existing rows stay valid; every NEW order gets one from the
-- callback. A UNIQUE index guards against duplicates even if two app instances
-- ever misbehave.
-- ============================================================================

--changeset sushant:13-add-order-number-column
ALTER TABLE orders ADD COLUMN order_number VARCHAR(40);
--rollback ALTER TABLE orders DROP COLUMN order_number;

--changeset sushant:13-order-number-unique-index
CREATE UNIQUE INDEX uq_orders_order_number ON orders (order_number);
--rollback DROP INDEX IF EXISTS uq_orders_order_number;
