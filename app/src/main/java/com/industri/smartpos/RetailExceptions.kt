package com.industri.smartpos

/**
 * Kumpulan custom exception untuk Smart Retail POS Terminal
 * Sesuai ketentuan modul Pertemuan 12 (Error Handling & Debugging)
 */

/**
 * 1. Dilemparkan saat barcode produk tidak terdaftar dalam basis data katalog POS.
 */
class ProductBarcodeNotFoundException(val barcode: String) :
    Exception("Produk dengan barcode '$barcode' tidak ditemukan dalam sistem katalog POS!")

/**
 * 2. Dilemparkan saat gateway pembayaran pihak ketiga (EDC/QRIS) tidak merespons dalam batas waktu.
 */
class PaymentGatewayTimeoutException(val gatewayName: String) :
    Exception("Koneksi ke payment gateway '$gatewayName' mengalami batas waktu (timeout)! Silakan periksa jaringan EDC.")

/**
 * 3. Dilemparkan saat total transaksi melampaui batas wewenang kasir (Sesuai Tugas Mandiri 2).
 */
class CashierLimitExceededException(val maxLimit: Double) :
    Exception("Batas limit transaksi kasir terlampaui! Maksimal: Rp ${"%,.0f".format(maxLimit)}. Diperlukan otorisasi PIN Supervisor.")
