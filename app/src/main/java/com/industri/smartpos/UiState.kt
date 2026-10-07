package com.industri.smartpos

/**
 * Representasi status UI untuk Smart Retail POS Terminal
 * Sesuai ketentuan modul Pertemuan 12: Sealed class dengan varian Idle, Loading, Success, Error
 */
sealed class UiState {
    /** Terminal dalam keadaan siap menerima transaksi baru */
    data object Idle : UiState()

    /** Terminal sedang memproses validasi, katalog, dan gateway pembayaran */
    data object Loading : UiState()

    /** Transaksi berhasil diproses */
    data class Success(
        val transactionId: String,
        val productName: String,
        val barcode: String,
        val totalAmount: Double,
        val paymentMethod: String,
        val timestamp: String,
        val notes: String = ""
    ) : UiState()

    /** Transaksi mengalami kegagalan atau eksepsi */
    data class Error(
        val message: String,
        val exception: Throwable? = null
    ) : UiState()
}
