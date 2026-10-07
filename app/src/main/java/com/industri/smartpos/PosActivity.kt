package com.industri.smartpos

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.industri.smartpos.databinding.ActivityPosBinding
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Smart Retail POS Terminal Activity
 * Implementasi Praktikum Pertemuan 12: Error Handling & Debugging
 * - CoroutineExceptionHandler sebagai pengaman utama fatal crash
 * - runCatching { ... } untuk idiom fungsional pemrosesan transaksi
 * - Custom Exceptions: ProductBarcodeNotFoundException, PaymentGatewayTimeoutException, CashierLimitExceededException
 * - Snackbar error kustom (#991B1B) dengan tombol 'COBA LAGI' (#FEF08A)
 * - Tugas 1: Max Retry Threshold (3x) & Fallback ke Pembayaran Tunai Manual
 * - Tugas 2: Otorisasi PIN Supervisor untuk transaksi melebihi limit Rp 10.000.000
 */
class PosActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPosBinding

    // Counter untuk Tugas Mandiri 1: Batas percobaan retry berturut-turut
    private var retryAttempt: Int = 0
    private val maxRetryThreshold = 3

    // Flag otorisasi supervisor untuk Tugas Mandiri 2
    private var isSupervisorAuthorized: Boolean = false

    // Katalog produk simulasi POS
    private val productCatalog = mapOf(
        "BRG-001" to ("Kopi Arabika Premium 250g" to 75000.0),
        "BRG-002" to ("Susu Organik Fresh 1L" to 35000.0),
        "BRG-003" to ("Roti Gandum Utuh High Fiber" to 25000.0),
        "BRG-004" to ("Paket Sembako Retail Mart" to 150000.0),
        "BRG-VIP" to ("Paket Grosir Mesin Espresso" to 12500000.0)
    )

    // Nilai rupiah formatter
    private val currencyFormat: NumberFormat = NumberFormat.getCurrencyInstance(Locale("id", "ID"))

    /**
     * CoroutineExceptionHandler sebagai jaring pengaman fatal crash utama
     * Sesuai ketentuan modul: WAJIB tangkap dan catat error fatal ke Timber.e
     */
    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Timber.e(throwable, "FATAL CRASH dicegah oleh CoroutineExceptionHandler! Sistem tetap stabil.")

        runOnUiThread {
            updateUi(
                UiState.Error(
                    message = "FATAL SYSTEM ERROR: ${throwable.localizedMessage ?: "Kesalahan internal sistem"}",
                    exception = throwable
                )
            )
            showErrorSnackbar(
                errorMessage = "Fatal Crash tertangkap: ${throwable.message}",
                onRetry = {
                    Timber.i("Mencoba me-restart status terminal setelah fatal crash...")
                    resetToIdle()
                }
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPosBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Timber.i("PosActivity diinisialisasi. POS Terminal siap beroperasi.")

        setupListeners()
        updateRetryBadge()
        updateUi(UiState.Idle)
    }

    private fun setupListeners() {
        // Tombol proses transaksi manual
        binding.btnProcessTransaction.setOnClickListener {
            val barcode = binding.etBarcode.text?.toString()?.trim().orEmpty()
            val totalAmountStr = binding.etTotalAmount.text?.toString()?.trim().orEmpty()
            val totalAmount = totalAmountStr.toDoubleOrNull() ?: 0.0
            val gateway = binding.etGateway.text?.toString()?.trim().orEmpty()

            executeTransaction(barcode, totalAmount, gateway)
        }

        // Preset 1: Skenario Transaksi Normal (Success)
        binding.btnSimSuccess.setOnClickListener {
            Timber.d("Pemicu pengujian: Skenario Transaksi Normal")
            binding.etBarcode.setText("BRG-001")
            binding.etTotalAmount.setText("75000")
            binding.etGateway.setText("QRIS Bank Central")
            isSupervisorAuthorized = false
            executeTransaction("BRG-001", 75000.0, "QRIS Bank Central")
        }

        // Preset 2: Skenario Barcode Tidak Ditemukan (ProductBarcodeNotFoundException)
        binding.btnSimBarcodeNotFound.setOnClickListener {
            Timber.d("Pemicu pengujian: Skenario Barcode Not Found")
            val invalidBarcode = "BRG-999-UNKNOWN"
            binding.etBarcode.setText(invalidBarcode)
            binding.etTotalAmount.setText("50000")
            binding.etGateway.setText("QRIS Bank Central")
            executeTransaction(invalidBarcode, 50000.0, "QRIS Bank Central")
        }

        // Preset 3: Skenario Gateway Timeout (PaymentGatewayTimeoutException)
        binding.btnSimGatewayTimeout.setOnClickListener {
            Timber.d("Pemicu pengujian: Skenario Payment Gateway Timeout")
            binding.etBarcode.setText("BRG-002")
            binding.etTotalAmount.setText("35000")
            binding.etGateway.setText("EDC Server (Timeout)")
            executeTransaction("BRG-002", 35000.0, "EDC Server (Timeout)")
        }

        // Preset 4: Skenario Limit Kasir > 10 Juta (CashierLimitExceededException - Tugas 2)
        binding.btnSimLimitExceeded.setOnClickListener {
            Timber.d("Pemicu pengujian: Skenario Limit Kasir Terlampaui (> 10 Juta)")
            binding.etBarcode.setText("BRG-VIP")
            binding.etTotalAmount.setText("12500000")
            binding.etGateway.setText("Debit EDC Bank Mandiri")
            isSupervisorAuthorized = false
            executeTransaction("BRG-VIP", 12500000.0, "Debit EDC Bank Mandiri")
        }

        // Preset 5: Skenario Fatal Crash (Diuji lewat CoroutineExceptionHandler)
        binding.btnSimFatalCrash.setOnClickListener {
            Timber.w("Pemicu pengujian: Mensimulasikan Fatal Crash tidak tertangani...")
            triggerSimulatedFatalCrash()
        }
    }

    /**
     * Menjalankan transaksi POS menggunakan coroutine dan idiom fungsional runCatching { ... }
     */
    private fun executeTransaction(barcode: String, amount: Double, gateway: String) {
        lifecycleScope.launch(coroutineExceptionHandler) {
            updateUi(UiState.Loading)
            Timber.i("Memulai proses transaksi [Barcode: %s | Total: %s | Gateway: %s | Auth: %b]",
                barcode, formatRupiah(amount), gateway, isSupervisorAuthorized)

            // Idiom fungsional runCatching sesuai modul Pertemuan 12
            val transactionResult = runCatching {
                // Simulasi latensi pemrosesan terminal
                delay(800)

                // 1. Validasi Barcode di Katalog POS
                val product = productCatalog[barcode]
                    ?: throw ProductBarcodeNotFoundException(barcode)

                // 2. Validasi Limit Kasir (Tugas Mandiri 2)
                val maxLimit = 10000000.0
                if (amount > maxLimit && !isSupervisorAuthorized) {
                    throw CashierLimitExceededException(maxLimit)
                }

                // 3. Validasi Payment Gateway
                if (gateway.contains("Timeout", ignoreCase = true)) {
                    delay(1000)
                    throw PaymentGatewayTimeoutException(gateway)
                }

                // Buat objek transaksi sukses
                val txId = "TRX-${System.currentTimeMillis().toString().takeLast(6)}"
                val timestamp = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())

                UiState.Success(
                    transactionId = txId,
                    productName = product.first,
                    barcode = barcode,
                    totalAmount = amount,
                    paymentMethod = gateway,
                    timestamp = timestamp,
                    notes = if (isSupervisorAuthorized) "Disetujui oleh Supervisor (PIN Valid)" else "Transaksi Reguler Kasir"
                )
            }

            // Penanganan Hasil runCatching
            transactionResult.onSuccess { successState ->
                Timber.i("Transaksi %s BERHASIL diproses. Total: %s", successState.transactionId, formatRupiah(successState.totalAmount))
                // Reset counter retry saat berhasil
                retryAttempt = 0
                isSupervisorAuthorized = false
                updateRetryBadge()
                updateUi(successState)
            }

            transactionResult.onFailure { error ->
                handleTransactionFailure(error, barcode, amount, gateway)
            }
        }
    }

    /**
     * Penanganan error dengan pencatatan Timber yang ketat (Dilarang empty catch block)
     */
    private fun handleTransactionFailure(error: Throwable, barcode: String, amount: Double, gateway: String) {
        // Catat error sesuai standar modul
        Timber.e(error, "Transaksi GAGAL diproses: %s", error.message)

        updateUi(UiState.Error(error.localizedMessage ?: "Terjadi kesalahan", error))

        when (error) {
            is ProductBarcodeNotFoundException -> {
                Timber.w("Peringatan Kasir: Barcode '%s' tidak ditemukan dalam database.", error.barcode)
                showErrorSnackbar(
                    errorMessage = error.message ?: "Barcode tidak ditemukan",
                    onRetry = {
                        handleRetryAction {
                            executeTransaction(barcode, amount, gateway)
                        }
                    }
                )
            }

            is PaymentGatewayTimeoutException -> {
                Timber.w("Peringatan Jaringan: Gateway '%s' tidak merespons (Timeout).", error.gatewayName)
                showErrorSnackbar(
                    errorMessage = error.message ?: "Gateway pembayaran timeout",
                    onRetry = {
                        handleRetryAction {
                            executeTransaction(barcode, amount, gateway)
                        }
                    }
                )
            }

            is CashierLimitExceededException -> {
                Timber.w("Peringatan Limit: Nominal %s melampaui limit kasir %s. Menampilkan dialog otorisasi PIN Supervisor.",
                    formatRupiah(amount), formatRupiah(error.maxLimit))

                showSupervisorPinDialog(barcode, amount, gateway)
            }

            else -> {
                Timber.e(error, "Kesalahan umum tidak terduga pada transaksi POS")
                showErrorSnackbar(
                    errorMessage = "Error Sistem: ${error.message}",
                    onRetry = {
                        handleRetryAction {
                            executeTransaction(barcode, amount, gateway)
                        }
                    }
                )
            }
        }
    }

    /**
     * Tugas Mandiri 1: Retry Counter & Fallback Mode
     * Jika tombol 'COBA LAGI' diklik 3 kali berturut-turut dan masih gagal, tampilkan AlertDialog Fallback.
     */
    private fun handleRetryAction(retryBlock: () -> Unit) {
        retryAttempt++
        updateRetryBadge()
        Timber.i("Percobaan retry ke-%d dari maksimal %d", retryAttempt, maxRetryThreshold)

        if (retryAttempt >= maxRetryThreshold) {
            Timber.w("Batas retry tercapai (%d/%d). Memunculkan AlertDialog Fallback ke Tunai Manual.", retryAttempt, maxRetryThreshold)
            showFallbackDialog()
        } else {
            retryBlock()
        }
    }

    /**
     * AlertDialog Fallback untuk Tugas Mandiri 1
     */
    private fun showFallbackDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.fallback_dialog_title))
            .setMessage(getString(R.string.fallback_dialog_message))
            .setIcon(R.drawable.ic_error_alert)
            .setCancelable(false)
            .setPositiveButton(getString(R.string.dialog_action_cash)) { dialog, _ ->
                dialog.dismiss()
                Timber.i("Kasir memilih opsi fallback: Alihkan ke Pembayaran Tunai Manual.")
                binding.etGateway.setText("Tunai Manual (SOP Kasir)")
                retryAttempt = 0
                updateRetryBadge()

                val barcode = binding.etBarcode.text?.toString()?.trim().orEmpty()
                val totalAmount = binding.etTotalAmount.text?.toString()?.trim()?.toDoubleOrNull() ?: 0.0

                // Jalankan transaksi dengan metode Tunai Manual
                executeTransaction(barcode, totalAmount, "Tunai Manual (SOP Kasir)")
            }
            .setNegativeButton(getString(R.string.dialog_action_cancel)) { dialog, _ ->
                dialog.dismiss()
                Timber.d("Kasir membatalkan pengalihan fallback tunai manual.")
                resetToIdle()
            }
            .show()
    }

    /**
     * Tugas Mandiri 2: Otorisasi PIN Supervisor saat CashierLimitExceededException
     */
    private fun showSupervisorPinDialog(barcode: String, amount: Double, gateway: String) {
        val pinInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Masukkan 4 Digit PIN Supervisor (1234)"
            setPadding(48, 36, 48, 36)
            textSize = 16f
        }

        val container = FrameLayout(this).apply {
            addView(pinInput)
            setPadding(32, 16, 32, 8)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.supervisor_dialog_title))
            .setMessage(getString(R.string.supervisor_dialog_message))
            .setView(container)
            .setIcon(R.drawable.ic_error_alert)
            .setCancelable(false)
            .setPositiveButton(getString(R.string.dialog_action_authorize)) { dialog, _ ->
                val enteredPin = pinInput.text.toString().trim()
                if (enteredPin == "1234") {
                    dialog.dismiss()
                    Timber.i("Otorisasi Supervisor BERHASIL. PIN valid dimasukkan. Transaksi diizinkan.")
                    isSupervisorAuthorized = true
                    binding.tvActionHint.visibility = View.VISIBLE
                    binding.tvActionHint.text = "✓ Transaksi telah diotorisasi oleh Supervisor."

                    // Lanjutkan proses transaksi dengan wewenang supervisor
                    executeTransaction(barcode, amount, gateway)
                } else {
                    Timber.e("Otorisasi Supervisor GAGAL: PIN salah (%s)!", enteredPin)
                    showErrorSnackbar(
                        errorMessage = "PIN Supervisor Salah! Transaksi di atas Rp 10 Juta ditolak.",
                        onRetry = {
                            showSupervisorPinDialog(barcode, amount, gateway)
                        }
                    )
                }
            }
            .setNegativeButton(getString(R.string.dialog_action_cancel)) { dialog, _ ->
                dialog.dismiss()
                Timber.d("Otorisasi supervisor dibatalkan oleh kasir.")
                resetToIdle()
            }
            .show()
    }

    /**
     * Menampilkan Snackbar Error Modern:
     * - Background merah gelap: #991B1B
     * - Tombol 'COBA LAGI' (Retry) warna kuning: #FEF08A
     */
    private fun showErrorSnackbar(errorMessage: String, onRetry: () -> Unit) {
        val snackbar = Snackbar.make(binding.coordinatorLayout, errorMessage, Snackbar.LENGTH_LONG)
        snackbar.setBackgroundTint(Color.parseColor("#991B1B"))
        snackbar.setTextColor(Color.WHITE)
        snackbar.setAction(getString(R.string.btn_retry)) {
            Timber.d("Tombol 'COBA LAGI' ditekan pada Snackbar error.")
            onRetry()
        }
        snackbar.setActionTextColor(Color.parseColor("#FEF08A"))
        snackbar.show()
    }

    /**
     * Memperbarui antarmuka berdasarkan UiState
     */
    private fun updateUi(state: UiState) {
        when (state) {
            is UiState.Idle -> {
                binding.progressBar.visibility = View.GONE
                binding.ivStatusIcon.setImageResource(R.drawable.ic_check_circle)
                binding.ivStatusIcon.setColorFilter(Color.parseColor("#059669"))
                binding.tvStatusTitle.text = getString(R.string.status_idle)
                binding.tvStatusTitle.setTextColor(Color.parseColor("#0F172A"))
                binding.tvStatusDetail.text = "Terminal POS siap melayani transaksi.\nSilakan masukkan barcode & total nominal atau tekan preset skenario di atas."
                binding.cardResult.strokeColor = Color.parseColor("#E2E8F0")
                binding.tvActionHint.visibility = View.GONE
            }

            is UiState.Loading -> {
                binding.progressBar.visibility = View.VISIBLE
                binding.tvStatusTitle.text = getString(R.string.status_loading)
                binding.tvStatusTitle.setTextColor(Color.parseColor("#0D9488"))
                binding.tvStatusDetail.text = "Menghubungi katalog POS dan gateway perbankan...\nMohon tunggu sejenak."
                binding.cardResult.strokeColor = Color.parseColor("#0D9488")
            }

            is UiState.Success -> {
                binding.progressBar.visibility = View.GONE
                binding.ivStatusIcon.setImageResource(R.drawable.ic_check_circle)
                binding.ivStatusIcon.setColorFilter(Color.parseColor("#059669"))
                binding.tvStatusTitle.text = "TRANSAKSI SUKSES: ${state.transactionId}"
                binding.tvStatusTitle.setTextColor(Color.parseColor("#059669"))
                binding.cardResult.strokeColor = Color.parseColor("#059669")

                val receipt = buildString {
                    appendLine("============== STRUK RESMI POS ==============")
                    appendLine("ID Transaksi : ${state.transactionId}")
                    appendLine("Waktu        : ${state.timestamp}")
                    appendLine("Barcode      : ${state.barcode}")
                    appendLine("Nama Produk  : ${state.productName}")
                    appendLine("Total Bayar  : ${formatRupiah(state.totalAmount)}")
                    appendLine("Metode Bayar : ${state.paymentMethod}")
                    if (state.notes.isNotEmpty()) {
                        appendLine("Catatan      : ${state.notes}")
                    }
                    appendLine("Status       : LUNAS / APPROVED")
                    appendLine("==============================================")
                }
                binding.tvStatusDetail.text = receipt
                binding.tvActionHint.visibility = View.VISIBLE
                binding.tvActionHint.text = "✓ Transaksi tersimpan ke jurnal POS & tercatat di Timber log."
            }

            is UiState.Error -> {
                binding.progressBar.visibility = View.GONE
                binding.ivStatusIcon.setImageResource(R.drawable.ic_error_alert)
                binding.ivStatusIcon.setColorFilter(Color.parseColor("#DC2626"))
                binding.tvStatusTitle.text = "TRANSAKSI GAGAL / ERROR"
                binding.tvStatusTitle.setTextColor(Color.parseColor("#DC2626"))
                binding.cardResult.strokeColor = Color.parseColor("#DC2626")

                val errorLogDetail = buildString {
                    appendLine("[ERROR DETAIL TERMINAL POS]")
                    appendLine("Pesan Error : ${state.message}")
                    appendLine("Tipe Kelas  : ${state.exception?.javaClass?.simpleName ?: "UnknownException"}")
                    appendLine("Waktu Error : ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}")
                    appendLine("Status Log  : Tercatat via Timber.e() / Timber.w()")
                    if (retryAttempt > 0) {
                        appendLine("Retry Count : $retryAttempt dari $maxRetryThreshold batas maksimal")
                    }
                }
                binding.tvStatusDetail.text = errorLogDetail
            }
        }
    }

    private fun updateRetryBadge() {
        binding.tvRetryBadge.text = "Retry: $retryAttempt/$maxRetryThreshold"
        when {
            retryAttempt >= maxRetryThreshold -> {
                binding.tvRetryBadge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_error)
            }
            retryAttempt > 0 -> {
                binding.tvRetryBadge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_warning)
            }
            else -> {
                binding.tvRetryBadge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_info)
            }
        }
    }

    private fun resetToIdle() {
        retryAttempt = 0
        isSupervisorAuthorized = false
        updateRetryBadge()
        updateUi(UiState.Idle)
    }

    private fun formatRupiah(amount: Double): String {
        return "Rp " + String.format(Locale("id", "ID"), "%,.0f", amount)
    }

    /**
     * Memanggil fatal crash di dalam coroutine untuk menguji CoroutineExceptionHandler
     */
    private fun triggerSimulatedFatalCrash() {
        lifecycleScope.launch(coroutineExceptionHandler) {
            updateUi(UiState.Loading)
            delay(400)
            throw RuntimeException("Simulasi Fatal Crash tidak terduga pada Thread Coroutine Terminal POS!")
        }
    }
}
