package com.hexated

import android.app.AlertDialog
import android.content.Context
import android.util.Log
import android.widget.EditText
import android.widget.Toast
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NontonAnimeIDPlugin : Plugin() {
    override fun load(context: Context) {
        val provider = NontonAnimeID()
        try {
            val customUrl = getKey<String>(NontonAnimeIDSettings.KEY_CUSTOM_URL)
            if (!customUrl.isNullOrBlank()) {
                provider.mainUrl = customUrl
            }
        } catch (e: Throwable) {
            Log.w("NontonAnimeIDPlugin", "Could not read customUrl: ${e.message}")
        }

        try {
            registerMainAPI(provider)
        } catch (e: Throwable) {
            Log.e("NontonAnimeIDPlugin", "Error registering MainAPI: ${e.message}")
        }

        openSettings = { ctx ->
            try {
                showMainSettingsDialog(ctx, provider)
            } catch (e: Throwable) {
                Toast.makeText(ctx, "Gagal membuka pengaturan: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showMainSettingsDialog(ctx: Context, provider: NontonAnimeID) {
        val options = arrayOf(
            "Verifikasi Cloudflare (Turnstile WebView)",
            "Input Manual Cookie cf_clearance",
            "Status Cloudflare & Hapus Cookie",
            "Kustom Domain URL",
            "Reset ke Pengaturan Awal"
        )

        AlertDialog.Builder(ctx)
            .setTitle("Pengaturan NontonAnimeID")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showCloudflareSolveDialog(ctx, provider)
                    1 -> showManualCookieDialog(ctx)
                    2 -> showCloudflareStatusDialog(ctx)
                    3 -> showCustomDomainDialog(ctx, provider)
                    4 -> showResetDialog(ctx, provider)
                }
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun showCloudflareSolveDialog(ctx: Context, provider: NontonAnimeID) {
        NontonAnimeIDCloudflare.showInteractiveDialog(ctx, provider.mainUrl)
    }

    private fun showManualCookieDialog(ctx: Context) {
        val input = EditText(ctx)
        input.hint = "Paste token cf_clearance di sini"
        val current = NontonAnimeIDCloudflare.getSavedClearance()
        if (!current.isNullOrBlank()) {
            input.setText(current)
        }

        AlertDialog.Builder(ctx)
            .setTitle("Input Manual Cookie cf_clearance")
            .setMessage("Jika WebView otomatis terkendala, salin nilai cookie cf_clearance dari browser:")
            .setView(input)
            .setPositiveButton("Simpan") { _, _ ->
                val cookie = input.text.toString().trim()
                if (cookie.isNotBlank()) {
                    NontonAnimeIDCloudflare.setSavedClearance(cookie)
                    Toast.makeText(ctx, "Cookie cf_clearance berhasil disimpan!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showCloudflareStatusDialog(ctx: Context) {
        val clearance = NontonAnimeIDCloudflare.getSavedClearance()
        val ua = NontonAnimeIDCloudflare.getSavedUserAgent()

        val statusMsg = if (!clearance.isNullOrBlank()) {
            "Status: TERHUBUNG / AKTIF\n\nToken cf_clearance: ${clearance.take(15)}...\nUser-Agent: ${ua?.take(30)}..."
        } else {
            "Status: BELUM TERVERIFIKASI\n\nSilakan pilih 'Verifikasi Cloudflare' jika anime gagal dimuat."
        }

        AlertDialog.Builder(ctx)
            .setTitle("Status Cloudflare")
            .setMessage(statusMsg)
            .setPositiveButton("Tutup", null)
            .setNeutralButton("Hapus Cookie") { _, _ ->
                NontonAnimeIDCloudflare.clearSavedClearance()
                Toast.makeText(ctx, "Cookie Cloudflare berhasil dihapus.", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showCustomDomainDialog(ctx: Context, provider: NontonAnimeID) {
        val input = EditText(ctx)
        val current = getKey<String>(NontonAnimeIDSettings.KEY_CUSTOM_URL) ?: NontonAnimeIDSettings.DEFAULT_URL
        input.setText(current)

        AlertDialog.Builder(ctx)
            .setTitle("Kustom Domain URL")
            .setMessage("Masukkan domain NontonAnimeID aktif jika domain default terblokir:")
            .setView(input)
            .setPositiveButton("Simpan") { _, _ ->
                val newUrl = input.text.toString().trim().removeSuffix("/")
                if (newUrl.startsWith("http://") || newUrl.startsWith("https://")) {
                    setKey(NontonAnimeIDSettings.KEY_CUSTOM_URL, newUrl)
                    provider.mainUrl = newUrl
                    Toast.makeText(ctx, "Domain disimpan: $newUrl", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(ctx, "URL harus diawali dengan http:// atau https://", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Reset Bawaan") { _, _ ->
                setKey(NontonAnimeIDSettings.KEY_CUSTOM_URL, NontonAnimeIDSettings.DEFAULT_URL)
                provider.mainUrl = NontonAnimeIDSettings.DEFAULT_URL
                Toast.makeText(ctx, "Domain direset ke: ${NontonAnimeIDSettings.DEFAULT_URL}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showResetDialog(ctx: Context, provider: NontonAnimeID) {
        AlertDialog.Builder(ctx)
            .setTitle("Reset Pengaturan")
            .setMessage("Kembalikan semua setelan NontonAnimeID ke pengaturan awal?")
            .setPositiveButton("Ya, Reset") { _, _ ->
                setKey(NontonAnimeIDSettings.KEY_CUSTOM_URL, NontonAnimeIDSettings.DEFAULT_URL)
                NontonAnimeIDCloudflare.clearSavedClearance()
                provider.mainUrl = NontonAnimeIDSettings.DEFAULT_URL
                Toast.makeText(ctx, "Semua setelan berhasil direset ke bawaan!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Batal", null)
            .show()
    }
}
