package com.hexated

import android.app.AlertDialog
import android.content.Context
import android.widget.EditText
import android.widget.Toast
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KuramanimePlugin : Plugin() {
    override fun load(context: Context) {
        val provider = Kuramanime()
        registerMainAPI(provider)

        openSettings = { ctx ->
            showMainSettingsDialog(ctx, provider)
        }
    }

    private fun showMainSettingsDialog(ctx: Context, provider: Kuramanime) {
        val options = arrayOf(
            "Filter Konten (Hentai / Aman)",
            "Filter Negara (Anime / Donghua)",
            "Opsi Server Kuramadrive (kDrive / kTurbo)",
            "Pilihan Server Streaming (s1 / s2 / Semua)",
            "Kustom Domain URL",
            "Reset ke Pengaturan Awal"
        )

        AlertDialog.Builder(ctx)
            .setTitle("Pengaturan Kuramanime")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showGenreDialog(ctx)
                    1 -> showCountryDialog(ctx)
                    2 -> showKuramadriveOptionDialog(ctx)
                    3 -> showStreamServerDialog(ctx)
                    4 -> showCustomDomainDialog(ctx, provider)
                    5 -> showResetDialog(ctx, provider)
                }
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun showGenreDialog(ctx: Context) {
        val items = arrayOf(
            "Semua Konten (Bawaan)",
            "Sembunyikan Hentai (Aman)",
            "Hanya Tampilkan Hentai (18+)"
        )
        val values = arrayOf("all", "only_not_hentai", "only_hentai")
        val current = getKey<String>(KuramanimeSettings.KEY_GENRE) ?: KuramanimeSettings.DEFAULT_GENRE
        val selectedIndex = values.indexOf(current).coerceAtLeast(0)

        AlertDialog.Builder(ctx)
            .setTitle("Filter Konten (Genre)")
            .setSingleChoiceItems(items, selectedIndex) { dialog, which ->
                setKey(KuramanimeSettings.KEY_GENRE, values[which])
                Toast.makeText(ctx, "Filter konten: ${items[which]}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showCountryDialog(ctx: Context) {
        val items = arrayOf(
            "Semua Negara (Bawaan)",
            "Hanya Jepang (Anime JP)",
            "Hanya Tiongkok (Donghua CN)",
            "Hanya Korea (KR)",
            "Hanya Indonesia (ID)",
            "Hanya Malaysia (MY)",
            "Hanya Amerika Serikat (US)"
        )
        val values = arrayOf("all", "JP", "CN", "KR", "ID", "MY", "US")
        val current = getKey<String>(KuramanimeSettings.KEY_COUNTRY) ?: KuramanimeSettings.DEFAULT_COUNTRY
        val selectedIndex = values.indexOf(current).coerceAtLeast(0)

        AlertDialog.Builder(ctx)
            .setTitle("Filter Negara Asal")
            .setSingleChoiceItems(items, selectedIndex) { dialog, which ->
                setKey(KuramanimeSettings.KEY_COUNTRY, values[which])
                Toast.makeText(ctx, "Filter negara: ${items[which]}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showKuramadriveOptionDialog(ctx: Context) {
        val items = arrayOf(
            "kDrive (Server Bawaan)",
            "kTurbo (Server Rilisan Baru)"
        )
        val values = arrayOf("kdrive", "kturbo")
        val current = getKey<String>(KuramanimeSettings.KEY_DRIVE_OPTION) ?: KuramanimeSettings.DEFAULT_DRIVE_OPTION
        val selectedIndex = values.indexOf(current).coerceAtLeast(0)

        AlertDialog.Builder(ctx)
            .setTitle("Opsi Server Kuramadrive")
            .setSingleChoiceItems(items, selectedIndex) { dialog, which ->
                setKey(KuramanimeSettings.KEY_DRIVE_OPTION, values[which])
                Toast.makeText(ctx, "Server Kuramadrive: ${items[which]}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showStreamServerDialog(ctx: Context) {
        val items = arrayOf(
            "Semua Server (s1 & s2) (Rekomendasi)",
            "Hanya Kuramadrive s1",
            "Hanya Kuramadrive s2 (Google CDN)"
        )
        val values = arrayOf("all", "kuramadrive", "kuramadrive-v2")
        val current = getKey<String>(KuramanimeSettings.KEY_STREAM_SERVER) ?: KuramanimeSettings.DEFAULT_STREAM_SERVER
        val selectedIndex = values.indexOf(current).coerceAtLeast(0)

        AlertDialog.Builder(ctx)
            .setTitle("Pilihan Server Streaming")
            .setSingleChoiceItems(items, selectedIndex) { dialog, which ->
                setKey(KuramanimeSettings.KEY_STREAM_SERVER, values[which])
                Toast.makeText(ctx, "Server streaming: ${items[which]}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showCustomDomainDialog(ctx: Context, provider: Kuramanime) {
        val input = EditText(ctx)
        val current = getKey<String>(KuramanimeSettings.KEY_CUSTOM_URL) ?: KuramanimeSettings.DEFAULT_URL
        input.setText(current)

        AlertDialog.Builder(ctx)
            .setTitle("Kustom Domain URL")
            .setMessage("Masukkan domain Kuramanime yang aktif jika domain default terblokir:")
            .setView(input)
            .setPositiveButton("Simpan") { _, _ ->
                val newUrl = input.text.toString().trim().removeSuffix("/")
                if (newUrl.startsWith("http://") || newUrl.startsWith("https://")) {
                    setKey(KuramanimeSettings.KEY_CUSTOM_URL, newUrl)
                    provider.mainUrl = newUrl
                    Toast.makeText(ctx, "Domain disimpan: $newUrl", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(ctx, "URL harus diawali dengan http:// atau https://", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Reset Bawaan") { _, _ ->
                setKey(KuramanimeSettings.KEY_CUSTOM_URL, KuramanimeSettings.DEFAULT_URL)
                provider.mainUrl = KuramanimeSettings.DEFAULT_URL
                Toast.makeText(ctx, "Domain direset ke: ${KuramanimeSettings.DEFAULT_URL}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun showResetDialog(ctx: Context, provider: Kuramanime) {
        AlertDialog.Builder(ctx)
            .setTitle("Reset Pengaturan")
            .setMessage("Kembalikan semua setelan Kuramanime ke pengaturan awal?")
            .setPositiveButton("Ya, Reset") { _, _ ->
                setKey(KuramanimeSettings.KEY_GENRE, KuramanimeSettings.DEFAULT_GENRE)
                setKey(KuramanimeSettings.KEY_COUNTRY, KuramanimeSettings.DEFAULT_COUNTRY)
                setKey(KuramanimeSettings.KEY_DRIVE_OPTION, KuramanimeSettings.DEFAULT_DRIVE_OPTION)
                setKey(KuramanimeSettings.KEY_STREAM_SERVER, KuramanimeSettings.DEFAULT_STREAM_SERVER)
                setKey(KuramanimeSettings.KEY_CUSTOM_URL, KuramanimeSettings.DEFAULT_URL)
                provider.mainUrl = KuramanimeSettings.DEFAULT_URL
                Toast.makeText(ctx, "Semua setelan berhasil direset ke bawaan!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Batal", null)
            .show()
    }
}
