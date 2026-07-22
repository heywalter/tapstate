-- Seed for the demo source. The official image runs every .sql here once, on an empty data
-- directory only, so this reflects a first boot and not a re-run over the named volume.
--
-- This is the same orders table the walkthrough uses, kept identical on purpose: the demo and the
-- walkthrough are one sample, not two that drift. The amount column is a decimal, which this preview
-- cannot put through a CEL expression -- so, like the walkthrough, it only ever passes through, and
-- no demo expression reads it.
CREATE TABLE IF NOT EXISTS orders (
  id       INT PRIMARY KEY,
  customer VARCHAR(64),
  amount   DECIMAL(10,2)
);

INSERT INTO orders (id, customer, amount) VALUES
  (1, 'alice', 10.00),
  (2, 'bob',   20.00),
  (3, 'carol', 30.00),
  (4, 'dave',  40.00),
  (5, 'erin',  50.00);
