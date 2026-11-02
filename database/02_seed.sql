-- Data contoh untuk mencoba API. Password semua nasabah: Password123!
-- Jangan dipakai di production.

INSERT INTO customers (username, password_hash, full_name) VALUES
    ('budi', '$2a$10$CZPIphCNnNCZF75nbFXTK.mdcXkdhFGyFE3Xik3vNKVWzst7i4dAO', 'Budi Santoso'),
    ('siti', '$2a$10$CZPIphCNnNCZF75nbFXTK.mdcXkdhFGyFE3Xik3vNKVWzst7i4dAO', 'Siti Rahayu'),
    ('andi', '$2a$10$CZPIphCNnNCZF75nbFXTK.mdcXkdhFGyFE3Xik3vNKVWzst7i4dAO', 'Andi Wijaya');

INSERT INTO accounts (account_number, customer_id, balance, status) VALUES
    ('1000000001', (SELECT id FROM customers WHERE username = 'budi'), 10000000.00, 'ACTIVE'),
    ('1000000002', (SELECT id FROM customers WHERE username = 'siti'),  5000000.00, 'ACTIVE'),
    ('1000000003', (SELECT id FROM customers WHERE username = 'budi'),  1000000.00, 'ACTIVE'),
    ('1000000004', (SELECT id FROM customers WHERE username = 'andi'),  2000000.00, 'BLOCKED');
