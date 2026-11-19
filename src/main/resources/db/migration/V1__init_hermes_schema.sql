-- =====================================================================
-- V1: Hermes Marketplace Production Schema, Constraints & B-Tree Indexes
-- =====================================================================

CREATE TABLE IF NOT EXISTS users (
                                     id BIGSERIAL PRIMARY KEY,
                                     phone VARCHAR(15) NOT NULL UNIQUE,
    full_name VARCHAR(120) NOT NULL,
    password VARCHAR(120) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'BUYER',
    avatar_url TEXT,
    national_code VARCHAR(10),
    email VARCHAR(120),
    birth_date VARCHAR(20),
    sheba_number VARCHAR(34),
    bio TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_users_role CHECK (role IN ('BUYER', 'SELLER', 'ADMIN'))
    );

ALTER TABLE users ALTER COLUMN avatar_url TYPE TEXT;
ALTER TABLE users ALTER COLUMN bio TYPE TEXT;

CREATE TABLE IF NOT EXISTS otp_verifications (
                                                 id BIGSERIAL PRIMARY KEY,
                                                 phone VARCHAR(15) NOT NULL UNIQUE,
    hashed_code VARCHAR(120) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    failed_attempts INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
    );

CREATE TABLE IF NOT EXISTS addresses (
                                         id BIGSERIAL PRIMARY KEY,
                                         user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title VARCHAR(80) NOT NULL,
    city VARCHAR(80) NOT NULL,
    full_address VARCHAR(500) NOT NULL,
    postal_code VARCHAR(20) NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE
    );

CREATE TABLE IF NOT EXISTS categories (
                                          id BIGSERIAL PRIMARY KEY,
                                          name VARCHAR(100) NOT NULL,
    icon_url TEXT,
    parent_id BIGINT REFERENCES categories(id) ON DELETE SET NULL
    );

ALTER TABLE categories ALTER COLUMN icon_url TYPE TEXT;

CREATE TABLE IF NOT EXISTS booths (
                                      id BIGSERIAL PRIMARY KEY,
                                      seller_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    name VARCHAR(150) NOT NULL,
    description TEXT,
    type VARCHAR(20) NOT NULL DEFAULT 'RETAIL',
    logo_url TEXT,
    banner_url TEXT,
    rating DOUBLE PRECISION NOT NULL DEFAULT 5.0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_booths_type CHECK (type IN ('RETAIL', 'SUPPLIER', 'PRODUCTION'))
    );

ALTER TABLE booths ALTER COLUMN logo_url TYPE TEXT;
ALTER TABLE booths ALTER COLUMN banner_url TYPE TEXT;
ALTER TABLE booths ALTER COLUMN description TYPE TEXT;

CREATE TABLE IF NOT EXISTS products (
                                        id BIGSERIAL PRIMARY KEY,
                                        booth_id BIGINT NOT NULL REFERENCES booths(id) ON DELETE RESTRICT,
    category_id BIGINT NOT NULL REFERENCES categories(id) ON DELETE RESTRICT,
    title VARCHAR(300) NOT NULL,
    description TEXT,
    price NUMERIC(18, 0) NOT NULL,
    discount_price NUMERIC(18, 0),
    china_price NUMERIC(18, 0),
    sale_type VARCHAR(20) NOT NULL DEFAULT 'SINGLE',
    min_order_quantity INTEGER NOT NULL DEFAULT 1,
    warehouse_origin VARCHAR(20) NOT NULL DEFAULT 'IRAN',
    delivery_time_range VARCHAR(120) NOT NULL DEFAULT 'آماده ارسال از انبار ایران (۱ تا ۳ روز کاری)',
    iran_stock_quantity INTEGER NOT NULL DEFAULT 0,
    china_stock_quantity INTEGER NOT NULL DEFAULT 0,
    stock_quantity INTEGER NOT NULL DEFAULT 0,
    image_url TEXT,
    is_hot_deal BOOLEAN NOT NULL DEFAULT FALSE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    rating DOUBLE PRECISION NOT NULL DEFAULT 5.0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_products_price_positive CHECK (price > 0),
    CONSTRAINT chk_products_stock_non_negative CHECK (iran_stock_quantity >= 0 AND china_stock_quantity >= 0),
    CONSTRAINT chk_products_sale_type CHECK (sale_type IN ('SINGLE', 'MOQ')),
    CONSTRAINT chk_products_origin CHECK (warehouse_origin IN ('IRAN', 'CHINA'))
    );

ALTER TABLE products ALTER COLUMN image_url TYPE TEXT;
ALTER TABLE products ALTER COLUMN description TYPE TEXT;
ALTER TABLE products ALTER COLUMN title TYPE VARCHAR(300);
ALTER TABLE products ADD COLUMN IF NOT EXISTS sale_type VARCHAR(20) NOT NULL DEFAULT 'SINGLE';
ALTER TABLE products ADD COLUMN IF NOT EXISTS min_order_quantity INTEGER NOT NULL DEFAULT 1;
ALTER TABLE products ADD COLUMN IF NOT EXISTS warehouse_origin VARCHAR(20) NOT NULL DEFAULT 'IRAN';
ALTER TABLE products ADD COLUMN IF NOT EXISTS china_price NUMERIC(18, 0);
ALTER TABLE products ADD COLUMN IF NOT EXISTS delivery_time_range VARCHAR(120) NOT NULL DEFAULT 'آماده ارسال از انبار ایران (۱ تا ۳ روز کاری)';
ALTER TABLE products ADD COLUMN IF NOT EXISTS iran_stock_quantity INTEGER NOT NULL DEFAULT 10;
ALTER TABLE products ADD COLUMN IF NOT EXISTS china_stock_quantity INTEGER NOT NULL DEFAULT 0;
ALTER TABLE products ADD COLUMN IF NOT EXISTS is_active BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE products ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS product_price_tiers (
                                                   id BIGSERIAL PRIMARY KEY,
                                                   product_id BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    min_quantity INTEGER NOT NULL,
    unit_price NUMERIC(18, 0) NOT NULL,
    CONSTRAINT chk_tier_positive CHECK (min_quantity >= 2 AND unit_price > 0),
    CONSTRAINT uq_product_tier_min_qty UNIQUE (product_id, min_quantity)
    );

CREATE TABLE IF NOT EXISTS banners (
                                       id BIGSERIAL PRIMARY KEY,
                                       title TEXT NOT NULL,
                                       image_url TEXT NOT NULL,
                                       target_url TEXT,
                                       is_active BOOLEAN NOT NULL DEFAULT TRUE
);

ALTER TABLE banners ALTER COLUMN image_url TYPE TEXT;
ALTER TABLE banners ALTER COLUMN title TYPE TEXT;
ALTER TABLE banners ALTER COLUMN target_url TYPE TEXT;

CREATE TABLE IF NOT EXISTS cart_items (
                                          id BIGSERIAL PRIMARY KEY,
                                          user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    product_id BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    quantity INTEGER NOT NULL DEFAULT 1,
    selected_warehouse VARCHAR(20) NOT NULL DEFAULT 'IRAN',
    CONSTRAINT chk_cart_qty_positive CHECK (quantity > 0),
    CONSTRAINT chk_cart_warehouse CHECK (selected_warehouse IN ('IRAN', 'CHINA'))
    );

ALTER TABLE cart_items ADD COLUMN IF NOT EXISTS selected_warehouse VARCHAR(20) NOT NULL DEFAULT 'IRAN';

-- Upgrade cart uniqueness from (user_id, product_id) to (user_id, product_id, selected_warehouse)
DO $migration$
DECLARE
rec RECORD;
BEGIN
FOR rec IN
SELECT c.conname
FROM pg_constraint c
         JOIN pg_class t ON c.conrelid = t.oid
WHERE t.relname = 'cart_items'
  AND c.contype = 'u'
  AND array_length(c.conkey, 1) = 2
    LOOP
        EXECUTE 'ALTER TABLE cart_items DROP CONSTRAINT ' || quote_ident(rec.conname);
END LOOP;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'uq_cart_user_product_warehouse'
    ) THEN
ALTER TABLE cart_items
    ADD CONSTRAINT uq_cart_user_product_warehouse
        UNIQUE (user_id, product_id, selected_warehouse);
END IF;
END
$migration$;

CREATE TABLE IF NOT EXISTS wishlist_items (
                                              id BIGSERIAL PRIMARY KEY,
                                              user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    product_id BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    added_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_wishlist_user_product UNIQUE (user_id, product_id)
    );

CREATE TABLE IF NOT EXISTS orders (
                                      id BIGSERIAL PRIMARY KEY,
                                      user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    address_id BIGINT NOT NULL REFERENCES addresses(id) ON DELETE RESTRICT,
    total_amount NUMERIC(18, 0) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PAID',
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_orders_status CHECK (status IN ('PENDING', 'PAID', 'SHIPPED', 'DELIVERED', 'CANCELLED'))
    );

CREATE TABLE IF NOT EXISTS order_items (
                                           id BIGSERIAL PRIMARY KEY,
                                           order_id BIGINT NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id BIGINT NOT NULL REFERENCES products(id) ON DELETE RESTRICT,
    quantity INTEGER NOT NULL,
    unit_price NUMERIC(18, 0) NOT NULL,
    selected_warehouse VARCHAR(20) NOT NULL DEFAULT 'IRAN',
    CONSTRAINT chk_order_item_qty CHECK (quantity > 0),
    CONSTRAINT chk_order_item_warehouse CHECK (selected_warehouse IN ('IRAN', 'CHINA'))
    );

ALTER TABLE order_items ADD COLUMN IF NOT EXISTS selected_warehouse VARCHAR(20) NOT NULL DEFAULT 'IRAN';

-- Targeted B-Tree Indexes for FKs, Filters & Sorting
CREATE INDEX IF NOT EXISTS idx_addresses_user_id ON addresses(user_id);
CREATE INDEX IF NOT EXISTS idx_categories_parent_id ON categories(parent_id);
CREATE INDEX IF NOT EXISTS idx_booths_seller_id ON booths(seller_id);
CREATE INDEX IF NOT EXISTS idx_booths_type ON booths(type);
CREATE INDEX IF NOT EXISTS idx_products_booth_id ON products(booth_id);
CREATE INDEX IF NOT EXISTS idx_products_category_id ON products(category_id);
CREATE INDEX IF NOT EXISTS idx_products_active_hot_deal ON products(is_active, is_hot_deal);
CREATE INDEX IF NOT EXISTS idx_products_active_sale_type ON products(is_active, sale_type);
CREATE INDEX IF NOT EXISTS idx_products_created_at ON products(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_price_tiers_product_id ON product_price_tiers(product_id);
CREATE INDEX IF NOT EXISTS idx_cart_items_user_id ON cart_items(user_id);
CREATE INDEX IF NOT EXISTS idx_cart_items_product_id ON cart_items(product_id);
CREATE INDEX IF NOT EXISTS idx_wishlist_user_id ON wishlist_items(user_id);
CREATE INDEX IF NOT EXISTS idx_orders_user_created ON orders(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_order_items_order_id ON order_items(order_id);
CREATE INDEX IF NOT EXISTS idx_otp_expires_at ON otp_verifications(expires_at);