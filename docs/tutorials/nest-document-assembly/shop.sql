-- The shop this tutorial assembles into one document.
--
-- Nine tables, about two thousand rows, generated deterministically from a numbers table: no random
-- values, so every run of this script produces exactly the same data and the row counts printed at the
-- end are the ones the tutorial quotes.
--
--   mysql -h 127.0.0.1 -P 3306 -u root -p < shop.sql
--
-- The distribution is deliberate. Only paid and shipped orders have an invoice and payments, only
-- shipped orders have a shipment, every fifth order has a second payment, every seventh has a second
-- parcel, and only every third line has options. That way "this branch is empty" and "this branch never
-- arrived" look different in the assembled document instead of both reading as nothing.

DROP DATABASE IF EXISTS shop;
CREATE DATABASE shop;
USE shop;

CREATE TABLE customers (
  id           INT PRIMARY KEY,
  code         VARCHAR(16)  NOT NULL,
  name         VARCHAR(64)  NOT NULL,
  email        VARCHAR(128),
  tier         ENUM('bronze','silver','gold') NOT NULL DEFAULT 'bronze',
  credit_limit DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  active       TINYINT(1)   NOT NULL DEFAULT 1,
  created_at   DATETIME     NOT NULL
);

CREATE TABLE products (
  id         INT PRIMARY KEY,
  sku        VARCHAR(32) NOT NULL,
  name       VARCHAR(96) NOT NULL,
  category   VARCHAR(32) NOT NULL,
  list_price DECIMAL(10,2) NOT NULL,
  active     TINYINT(1) NOT NULL DEFAULT 1
);

CREATE TABLE orders (
  id           INT PRIMARY KEY,
  order_no     VARCHAR(24) NOT NULL,
  customer_id  INT NOT NULL,
  status       ENUM('new','paid','shipped','cancelled') NOT NULL,
  currency     CHAR(3) NOT NULL DEFAULT 'USD',
  total_amount DECIMAL(12,2) NOT NULL,
  placed_at    DATETIME NOT NULL,
  note         VARCHAR(255)
);

-- One invoice per order at most, keyed by the order: a genuine one-to-one child.
CREATE TABLE order_invoices (
  id         INT PRIMARY KEY,
  order_id   INT NOT NULL UNIQUE,
  invoice_no VARCHAR(24) NOT NULL,
  issued_at  DATETIME NOT NULL,
  net_amount DECIMAL(12,2) NOT NULL,
  tax_amount DECIMAL(12,2) NOT NULL,
  settled    TINYINT(1) NOT NULL DEFAULT 0
);

CREATE TABLE order_items (
  id           INT PRIMARY KEY,
  order_id     INT NOT NULL,
  line_no      INT NOT NULL,
  product_id   INT NOT NULL,
  sku          VARCHAR(32) NOT NULL,
  qty          INT NOT NULL,
  unit_price   DECIMAL(10,2) NOT NULL,
  discount_pct DECIMAL(5,2) NOT NULL DEFAULT 0.00
);

CREATE TABLE item_options (
  id      INT PRIMARY KEY,
  item_id INT NOT NULL,
  name    VARCHAR(32) NOT NULL,
  value   VARCHAR(64) NOT NULL
);

CREATE TABLE payments (
  id       INT PRIMARY KEY,
  order_id INT NOT NULL,
  method   ENUM('card','wire','wallet') NOT NULL,
  amount   DECIMAL(12,2) NOT NULL,
  paid_at  DATETIME NOT NULL,
  txn_ref  VARCHAR(48) NOT NULL
);

CREATE TABLE shipments (
  id          INT PRIMARY KEY,
  order_id    INT NOT NULL,
  carrier     VARCHAR(32) NOT NULL,
  tracking_no VARCHAR(48) NOT NULL,
  shipped_at  DATETIME NOT NULL,
  status      ENUM('pending','in_transit','delivered') NOT NULL
);

CREATE TABLE shipment_events (
  id          INT PRIMARY KEY,
  shipment_id INT NOT NULL,
  happened_at DATETIME NOT NULL,
  code        VARCHAR(24) NOT NULL,
  location    VARCHAR(64) NOT NULL
);

CREATE TEMPORARY TABLE nums (n INT PRIMARY KEY);
INSERT INTO nums (n)
SELECT a.d + b.d*10 + c.d*100 + 1
FROM (SELECT 0 d UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
      UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a,
     (SELECT 0 d UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
      UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b,
     (SELECT 0 d UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4
      UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c;

INSERT INTO customers (id, code, name, email, tier, credit_limit, active, created_at)
SELECT n,
       CONCAT('CUST-', LPAD(n, 4, '0')),
       ELT(1 + (n % 6), 'Acme Ltd','Borealis GmbH','Cinder Co','Dunlin SA','Everest Inc','Fjord AB'),
       CONCAT('ops', n, '@example.com'),
       ELT(1 + (n % 3), 'bronze', 'silver', 'gold'),
       ROUND(5000 + n * 137.5, 2),
       IF(n % 7 = 0, 0, 1),
       TIMESTAMP('2025-01-05 09:00:00') + INTERVAL n DAY
FROM nums WHERE n <= 12;

INSERT INTO products (id, sku, name, category, list_price, active)
SELECT n,
       CONCAT('SKU-', LPAD(n, 5, '0')),
       CONCAT(ELT(1 + (n % 4), 'Widget','Gasket','Bearing','Coupler'), ' ', ELT(1 + (n % 3), 'S','M','L')),
       ELT(1 + (n % 4), 'hardware','seals','rotating','fittings'),
       ROUND(9.99 + n * 3.25, 2),
       IF(n % 11 = 0, 0, 1)
FROM nums WHERE n <= 20;

-- 300 roots: more than the smallest memory budget a nest accepts, so the layer behind memory is used.
INSERT INTO orders (id, order_no, customer_id, status, currency, total_amount, placed_at, note)
SELECT n,
       CONCAT('SO-2025-', LPAD(n, 5, '0')),
       1 + (n % 12),
       ELT(1 + (n % 4), 'new', 'paid', 'shipped', 'cancelled'),
       ELT(1 + (n % 3), 'USD', 'EUR', 'CNY'),
       ROUND(120 + n * 7.77, 2),
       TIMESTAMP('2025-06-01 08:00:00') + INTERVAL n HOUR,
       IF(n % 9 = 0, CONCAT('rush order, contact ops', n), NULL)
FROM nums WHERE n <= 300;

INSERT INTO order_invoices (id, order_id, invoice_no, issued_at, net_amount, tax_amount, settled)
SELECT o.id, o.id,
       CONCAT('INV-2025-', LPAD(o.id, 5, '0')),
       o.placed_at + INTERVAL 1 HOUR,
       ROUND(o.total_amount / 1.19, 2),
       ROUND(o.total_amount - (o.total_amount / 1.19), 2),
       IF(o.status = 'shipped', 1, 0)
FROM orders o
WHERE o.status IN ('paid', 'shipped');

-- Line ids are order_id * 10 + line_no, so a line's parent is readable by eye.
INSERT INTO order_items (id, order_id, line_no, product_id, sku, qty, unit_price, discount_pct)
SELECT o.id * 10 + l.n, o.id, l.n,
       1 + ((o.id + l.n) % 20),
       CONCAT('SKU-', LPAD(1 + ((o.id + l.n) % 20), 5, '0')),
       1 + ((o.id + l.n) % 5),
       ROUND(9.99 + ((o.id + l.n) % 20) * 3.25, 2),
       IF((o.id + l.n) % 6 = 0, 10.00, 0.00)
FROM orders o JOIN nums l ON l.n <= 1 + (o.id % 3);

INSERT INTO item_options (id, item_id, name, value)
SELECT i.id * 10 + k.n, i.id,
       ELT(k.n, 'colour', 'finish'),
       ELT(1 + ((i.id + k.n) % 4), 'black', 'steel', 'anodised', 'matte')
FROM order_items i JOIN nums k ON k.n <= 2
WHERE i.id % 3 = 0;

INSERT INTO payments (id, order_id, method, amount, paid_at, txn_ref)
SELECT o.id * 10 + p.n, o.id,
       ELT(1 + ((o.id + p.n) % 3), 'card', 'wire', 'wallet'),
       ROUND((120 + o.id * 7.77) / (1 + (o.id % 5 = 0)), 2),
       o.placed_at + INTERVAL (2 * p.n) HOUR,
       CONCAT('TXN-', LPAD(o.id, 6, '0'), '-', p.n)
FROM orders o JOIN nums p ON p.n <= IF(o.id % 5 = 0, 2, 1)
WHERE o.status IN ('paid', 'shipped');

INSERT INTO shipments (id, order_id, carrier, tracking_no, shipped_at, status)
SELECT o.id * 10 + s.n, o.id,
       ELT(1 + ((o.id + s.n) % 3), 'DHL', 'UPS', 'SF Express'),
       CONCAT('TRK', LPAD(o.id * 10 + s.n, 9, '0')),
       o.placed_at + INTERVAL (24 + s.n) HOUR,
       ELT(1 + ((o.id + s.n) % 3), 'pending', 'in_transit', 'delivered')
FROM orders o JOIN nums s ON s.n <= IF(o.id % 7 = 0, 2, 1)
WHERE o.status = 'shipped';

INSERT INTO shipment_events (id, shipment_id, happened_at, code, location)
SELECT sh.id * 10 + e.n, sh.id,
       sh.shipped_at + INTERVAL (6 * e.n) HOUR,
       ELT(e.n, 'PICKED_UP', 'IN_TRANSIT', 'DELIVERED'),
       ELT(1 + ((sh.id + e.n) % 5), 'Hamburg', 'Rotterdam', 'Shenzhen', 'Chicago', 'Osaka')
FROM shipments sh JOIN nums e ON e.n <= 2 + (sh.id % 2);

SELECT 'customers' AS table_name, COUNT(*) AS rows_loaded FROM customers
UNION ALL SELECT 'products', COUNT(*) FROM products
UNION ALL SELECT 'orders', COUNT(*) FROM orders
UNION ALL SELECT 'order_invoices', COUNT(*) FROM order_invoices
UNION ALL SELECT 'order_items', COUNT(*) FROM order_items
UNION ALL SELECT 'item_options', COUNT(*) FROM item_options
UNION ALL SELECT 'payments', COUNT(*) FROM payments
UNION ALL SELECT 'shipments', COUNT(*) FROM shipments
UNION ALL SELECT 'shipment_events', COUNT(*) FROM shipment_events;
