--liquibase formatted sql

-- ============================================================================
-- PR #10 - Auditing columns (created_at, updated_at, created_by, updated_by).
-- ----------------------------------------------------------------------------
-- Added to the seven ENTITY tables only. product_categories is a pure join
-- table (no JPA entity owns its rows), so auditing metadata does not apply.
--
-- Spring Data JPA's AuditingEntityListener populates these on persist/update;
-- the DEFAULTs keep any pre-existing rows and any non-JPA inserts valid.
-- ============================================================================

--changeset sushant:10-audit-customers
ALTER TABLE customers
    ADD COLUMN created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN created_by VARCHAR(64) NOT NULL DEFAULT 'system',
    ADD COLUMN updated_by VARCHAR(64) NOT NULL DEFAULT 'system';
--rollback ALTER TABLE customers DROP COLUMN created_by, DROP COLUMN updated_by, DROP COLUMN created_at, DROP COLUMN updated_at;

--changeset sushant:10-audit-addresses
ALTER TABLE addresses
    ADD COLUMN created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN created_by VARCHAR(64) NOT NULL DEFAULT 'system',
    ADD COLUMN updated_by VARCHAR(64) NOT NULL DEFAULT 'system';
--rollback ALTER TABLE addresses DROP COLUMN created_by, DROP COLUMN updated_by, DROP COLUMN created_at, DROP COLUMN updated_at;

--changeset sushant:10-audit-orders
ALTER TABLE orders
    ADD COLUMN created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN created_by VARCHAR(64) NOT NULL DEFAULT 'system',
    ADD COLUMN updated_by VARCHAR(64) NOT NULL DEFAULT 'system';
--rollback ALTER TABLE orders DROP COLUMN created_by, DROP COLUMN updated_by, DROP COLUMN created_at, DROP COLUMN updated_at;

--changeset sushant:10-audit-order-items
ALTER TABLE order_items
    ADD COLUMN created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN created_by VARCHAR(64) NOT NULL DEFAULT 'system',
    ADD COLUMN updated_by VARCHAR(64) NOT NULL DEFAULT 'system';
--rollback ALTER TABLE order_items DROP COLUMN created_by, DROP COLUMN updated_by, DROP COLUMN created_at, DROP COLUMN updated_at;

--changeset sushant:10-audit-products
ALTER TABLE products
    ADD COLUMN created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN created_by VARCHAR(64) NOT NULL DEFAULT 'system',
    ADD COLUMN updated_by VARCHAR(64) NOT NULL DEFAULT 'system';
--rollback ALTER TABLE products DROP COLUMN created_by, DROP COLUMN updated_by, DROP COLUMN created_at, DROP COLUMN updated_at;

--changeset sushant:10-audit-categories
ALTER TABLE categories
    ADD COLUMN created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN created_by VARCHAR(64) NOT NULL DEFAULT 'system',
    ADD COLUMN updated_by VARCHAR(64) NOT NULL DEFAULT 'system';
--rollback ALTER TABLE categories DROP COLUMN created_by, DROP COLUMN updated_by, DROP COLUMN created_at, DROP COLUMN updated_at;

--changeset sushant:10-audit-payments
ALTER TABLE payments
    ADD COLUMN created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN created_by VARCHAR(64) NOT NULL DEFAULT 'system',
    ADD COLUMN updated_by VARCHAR(64) NOT NULL DEFAULT 'system';
--rollback ALTER TABLE payments DROP COLUMN created_by, DROP COLUMN updated_by, DROP COLUMN created_at, DROP COLUMN updated_at;
