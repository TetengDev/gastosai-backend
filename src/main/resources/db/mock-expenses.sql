-- Sample data for gastos.ai (PostgreSQL).
-- Run manually in psql/pgAdmin against your database, matching the current JPA schema.

-- Categories are inserted first.
INSERT INTO categories (name) VALUES
('Food'),
('Transport'),
('Bills'),
('Shopping'),
('Entertainment'),
('Health')
ON CONFLICT (name) DO NOTHING;

INSERT INTO expenses (amount, category_id, date, note) VALUES
(89.50, (SELECT id FROM categories WHERE name = 'Food'), '2026-03-28', 'Groceries — weekend shop'),
(245.00, (SELECT id FROM categories WHERE name = 'Food'), '2026-03-30', 'Dinner with friends'),
(120.00, (SELECT id FROM categories WHERE name = 'Transport'), '2026-03-31', 'Gas station'),
(45.00, (SELECT id FROM categories WHERE name = 'Transport'), '2026-04-01', 'Metro + bus top-up'),
(250.50, (SELECT id FROM categories WHERE name = 'Food'), '2026-04-01', 'Lunch'),
(1899.00, (SELECT id FROM categories WHERE name = 'Bills'), '2026-04-02', 'Rent contribution'),
(599.00, (SELECT id FROM categories WHERE name = 'Bills'), '2026-04-02', 'Electricity'),
(350.00, (SELECT id FROM categories WHERE name = 'Shopping'), '2026-04-02', 'Clothing'),
(129.99, (SELECT id FROM categories WHERE name = 'Entertainment'), '2026-04-02', 'Streaming subscriptions'),
(75.00, (SELECT id FROM categories WHERE name = 'Health'), '2026-04-03', 'Pharmacy'),
(42.30, (SELECT id FROM categories WHERE name = 'Food'), '2026-04-03', 'Coffee & pastries'),
(2100.00, (SELECT id FROM categories WHERE name = 'Transport'), '2026-04-03', 'Car service'),
(15.50, (SELECT id FROM categories WHERE name = 'Food'), '2026-04-03', 'Snack'),
(480.00, (SELECT id FROM categories WHERE name = 'Bills'), '2026-04-03', 'Internet'),
(199.00, (SELECT id FROM categories WHERE name = 'Shopping'), '2026-04-03', 'Electronics accessory');
