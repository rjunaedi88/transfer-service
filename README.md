# Transfer Service

REST API transfer dana antar nasabah dengan Spring Boot 4.1, Java 21, dan PostgreSQL 17.

| Standar | Implementasi |
|---|---|
| Authentication | JWT HS256, masa berlaku 15 menit |
| Concurrency management | Row lock `SELECT ... FOR UPDATE` dengan urutan kunci tetap |
| Duplicate request protection | Header `Idempotency-Key` + unique constraint di database |
| Rate limiting | Token bucket (Bucket4j): per nasabah untuk transfer, per IP untuk login |
| Exception handling | Satu format error JSON untuk semua kasus |
| Unit test | 42 unit test + 12 integration test dengan PostgreSQL asli |
| Containerization | Multi-stage Dockerfile dan Docker Compose |

## Cara menjalankan

Yang dibutuhkan hanya Docker.

```bash
cp .env.example .env
docker compose up -d --build
```

Aplikasi siap di `http://localhost:8080` saat `docker compose ps` menampilkan status `healthy`.
Script di folder [database/](database/) dijalankan otomatis oleh PostgreSQL saat volume masih kosong.
Untuk menghapus container sekaligus datanya: `docker compose down -v`.

Data contoh, password semua nasabah `Password123!`:

| Username | Rekening | Saldo | Status |
|---|---|---|---|
| budi | 1000000001 | 10.000.000 | ACTIVE |
| budi | 1000000003 | 1.000.000 | ACTIVE |
| siti | 1000000002 | 5.000.000 | ACTIVE |
| andi | 1000000004 | 2.000.000 | BLOCKED |

## Cara memakai API

**1. Login untuk mendapatkan token.** Spesifikasi meminta satu endpoint transfer, sedangkan JWT harus
berasal dari suatu tempat, jadi endpoint ini ditambahkan sebagai pendukung.

```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"budi","password":"Password123!"}'
```

**2. Transfer.** Client membuat `Idempotency-Key` unik (mis. UUID, maksimal 64 karakter) untuk setiap
transaksi, dan memakai key yang sama saat mengulang transaksi itu.

```bash
curl -i -X POST http://localhost:8080/api/v1/transfers \
  -H "Authorization: Bearer <accessToken>" \
  -H "Idempotency-Key: 6f1c2f0a-0d7e-4a53-9b1e-3f6a1d2c4b10" \
  -H "Content-Type: application/json" \
  -d '{
    "sourceAccountNumber": "1000000001",
    "destinationAccountNumber": "1000000002",
    "amount": 150000,
    "description": "Bayar makan"
  }'
```

Respons `201 Created`:

```json
{
  "transferId": "0e689f52-b190-47a5-9a0e-7bf6373673ed",
  "status": "SUCCESS",
  "sourceAccountNumber": "1000000001",
  "destinationAccountNumber": "1000000002",
  "amount": 150000.00,
  "description": "Bayar makan",
  "createdAt": "2026-11-02T06:54:56.148192Z"
}
```

Mengulang request yang sama mengembalikan `200 OK` dengan isi identik dan header
`Idempotent-Replayed: true`, tanpa memindahkan uang lagi.

Semua error memakai format yang sama:

```json
{
  "code": "INSUFFICIENT_BALANCE",
  "message": "Insufficient balance",
  "path": "/api/v1/transfers",
  "timestamp": "2026-11-02T06:54:58.841Z"
}
```

| HTTP | Code | Kapan terjadi |
|---|---|---|
| 400 | `VALIDATION_ERROR`, `SAME_ACCOUNT` | Input tidak valid, header hilang, atau rekening asal sama dengan tujuan |
| 401 | `UNAUTHORIZED`, `INVALID_CREDENTIALS` | Token tidak ada, salah, atau kedaluwarsa; login gagal |
| 403 | `ACCOUNT_ACCESS_DENIED` | Rekening asal bukan milik nasabah yang login |
| 404 | `ACCOUNT_NOT_FOUND` | Rekening tidak ditemukan |
| 409 | `IDEMPOTENCY_KEY_CONFLICT` | Key sudah dipakai untuk request yang isinya berbeda |
| 422 | `INSUFFICIENT_BALANCE`, `ACCOUNT_INACTIVE` | Saldo kurang, atau rekening diblokir |
| 429 | `RATE_LIMIT_EXCEEDED` | Melebihi 20 transfer/menit per nasabah atau 5 login/menit per IP, disertai `Retry-After` |
| 503 | `SERVICE_BUSY` | Rekening sedang terkunci terlalu lama atau database penuh, aman diulang dengan key yang sama |

### Postman

Import [postman/transfer-service.postman_collection.json](postman/transfer-service.postman_collection.json),
lalu pilih **Run collection**. Token dan `Idempotency-Key` diatur otomatis, dan setiap request punya test
sendiri. Jika mencoba satu per satu, kirim `1. Login budi` lebih dulu. Request terakhir sengaja menghabiskan
kuota rate limit, jadi tunggu 1 menit sebelum menjalankan ulang.

## Arsitektur

Aplikasi memakai arsitektur berlapis dalam satu service. Pilihan ini cukup untuk satu endpoint,
mudah dipahami, dan setiap lapisan bisa diuji terpisah.

```
Request
  → RateLimitFilter + validasi JWT   (security)
  → TransferController               validasi input
  → TransferService                  duplicate request protection
  → TransferExecutor                 satu transaksi database: kunci, cek, pindahkan uang
  → Repository → PostgreSQL
```

`TransferService` dan `TransferExecutor` sengaja dipisah. Pelanggaran unique constraint baru bisa
ditangani dengan aman setelah transaksinya selesai di-rollback, dan itu terjadi di luar `@Transactional`.

### Concurrency management

Dua request yang membaca saldo bersamaan bisa sama-sama lolos cek saldo, lalu saldo berkurang dua kali
atau menjadi minus. Karena itu kedua rekening dikunci dengan `SELECT ... FOR UPDATE` sebelum saldo dibaca.

- **Pessimistic, bukan optimistic.** Transfer dari satu rekening sering berebut baris yang sama.
  Optimistic lock akan membuat banyak request gagal dan harus diulang, sedangkan pessimistic lock
  membuat request mengantre.
- **Urutan kunci tetap.** Rekening selalu dikunci berurutan menurut nomor rekening, sehingga transfer
  A→B dan B→A yang berjalan bersamaan tidak deadlock.
- **Tidak menunggu tanpa batas.** `lock_timeout` 3 detik membuat request yang tertahan terlalu lama
  mendapat `503`, bukan menggantung.
- **Lapisan terakhir di database.** `CHECK (balance >= 0)` menjaga saldo tidak bisa minus walau ada bug di aplikasi.

### Duplicate request protection

Timeout jaringan membuat client tidak tahu apakah transfernya berhasil, sehingga client akan mengulang.
Tiga pemeriksaan menangani kasus ini:

1. **Sebelum transaksi:** jika key sudah ada, hasil lama dikembalikan tanpa mengunci rekening.
2. **Setelah mendapat kunci:** request kembar yang tadi mengantre melihat hasil pemenang. Tanpa langkah
   ini, request kembar bisa gagal `INSUFFICIENT_BALANCE` karena saldonya baru saja dipakai oleh pemenang.
3. **Unique constraint `(customer_id, idempotency_key)`:** penjaga akhir untuk kasus yang tidak saling
   tertahan kunci, misalnya key sama dengan rekening asal berbeda.

Request juga disimpan dalam bentuk hash SHA-256. Key sama dengan isi berbeda ditolak `409`, supaya
kesalahan client tidak diam-diam mengembalikan transaksi yang salah. Hanya transfer sukses yang disimpan,
jadi transfer yang gagal bisa dicoba lagi dengan key yang sama.

### Struktur tabel

Script lengkap ada di [database/01_schema.sql](database/01_schema.sql) (tabel dan function) dan
[database/02_seed.sql](database/02_seed.sql) (data).

```
customers 1 ── n accounts 1 ── n account_mutations n ── 1 transfers
```

| Tabel | Fungsi | Kolom penting |
|---|---|---|
| `customers` | Nasabah dan kredensial login | `username` (unique), `password_hash` (BCrypt) |
| `accounts` | Rekening dan saldo | `account_number` (unique), `balance` (`NUMERIC(18,2)`, `CHECK >= 0`), `status` |
| `transfers` | Satu baris per transfer sukses | `idempotency_key`, `request_hash`, rekening asal dan tujuan, `amount` |
| `account_mutations` | Jejak audit saldo | `type` (DEBIT/CREDIT), `balance_after` |

- Nominal memakai `NUMERIC` dan `BigDecimal`, bukan floating point, supaya tidak ada error pembulatan.
- Setiap transfer menghasilkan tepat satu DEBIT dan satu CREDIT, sehingga perubahan saldo bisa ditelusuri.
- Function `set_updated_at()` dipakai trigger untuk mengisi `accounts.updated_at`, termasuk saat data
  diubah di luar aplikasi. Logika transfer tetap di Java supaya bisa di-unit test.
- Skema dikelola oleh script SQL. Hibernate hanya memvalidasi (`ddl-auto: validate`), sehingga aplikasi
  gagal start jika entity tidak cocok dengan tabel.

## Test

Membutuhkan JDK 21, Maven 3.9, dan Docker untuk integration test.

```bash
mvn test     # unit test
mvn verify   # unit test + integration test
```

`TransferFlowIT` menguji API lewat HTTP terhadap PostgreSQL asli yang dijalankan Testcontainers, memakai
script dari folder `database/`. Skenario utamanya:

- 40 transfer paralel dari saldo yang hanya cukup untuk 10: tepat 10 berhasil, saldo akhir 0.
- 20 request paralel dengan key sama sebesar seluruh saldo: uang berpindah sekali, semua menerima `transferId` yang sama.
- 60 transfer paralel berlawanan arah (A→B dan B→A): semua berhasil tanpa deadlock, total saldo tetap.
- Rekening dikunci terlalu lama: request mendapat `503`, tidak ada uang berpindah, dan retry berhasil.
