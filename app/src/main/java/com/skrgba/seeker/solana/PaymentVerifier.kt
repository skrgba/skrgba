package com.skrgba.seeker.solana

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * NEU — Bulletproof Payment Verifier.
 *
 * Verifiziert STRENG on-chain dass eine echte 0.1 SOL Zahlung an die
 * Treasury-Wallet stattgefunden hat. Egal was das Wallet zurückgibt
 * (Success-Signatur kann zu einer fehlgeschlagenen TX gehören!), diese
 * Klasse prüft auf der Blockchain ob:
 *
 *   1. Die TX existiert
 *   2. Die TX hat keinen on-chain Fehler (status.err == null)
 *   3. Die TX ist confirmed/finalized
 *   4. Mindestens LICENSE_PRICE Lamports sind an Treasury geflossen
 *      (postBalance - preBalance >= LICENSE_PRICE)
 *
 * Damit ist "kostenlos freischalten" technisch ausgeschlossen.
 */
class PaymentVerifier(
    private val rpcUrl: String = SolanaConfig.RPC_URL,
    private val treasuryAddress: String = SolanaConfig.TREASURY_WALLET,
    private val expectedLamports: Long = SolanaConfig.LICENSE_PRICE
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        // Allow many concurrent connections to the same RPC host
        .dispatcher(okhttp3.Dispatcher().apply {
            maxRequests = 50
            maxRequestsPerHost = 50
        })
        .build()
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    sealed class VerificationResult {
        data class Verified(val signature: String, val lamportsTransferred: Long) : VerificationResult()
        data class TxFailedOnChain(val signature: String, val error: String) : VerificationResult()
        data class WrongAmount(val signature: String, val transferred: Long, val expected: Long) : VerificationResult()
        data class TxNotFound(val signature: String) : VerificationResult()
        data class RpcError(val signature: String, val message: String) : VerificationResult()
    }

    /**
     * Wartet bis die TX confirmed ist und prüft dann den tatsächlich übertragenen Betrag.
     * Maximale Wartezeit: ca. 30 Sekunden (12 × 2.5s).
     */
    suspend fun verifyPayment(signature: String): VerificationResult {
        Log.d(TAG, "Verifying payment: $signature on RPC: $rpcUrl")

        // Schritt 1: Auf Confirmation warten
        val confirmed = waitForConfirmation(signature)
        if (confirmed != null) {
            Log.e(TAG, "Confirmation failed for $signature: $confirmed")
            return confirmed  // ein Fehler
        }

        // Schritt 2: TX-Details holen und Lamport-Differenz prüfen
        return checkTransferAmount(signature)
    }

    private suspend fun waitForConfirmation(signature: String): VerificationResult? {
        val maxAttempts = 12
        val delayMs = 2500L
        for (attempt in 1..maxAttempts) {
            try {
                val statusJson = postRpc(JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("id", 1)
                    put("method", "getSignatureStatuses")
                    put("params", JSONArray().apply {
                        put(JSONArray().apply { put(signature) })
                        put(JSONObject().apply { put("searchTransactionHistory", true) })
                    })
                })
                val statusValue = statusJson
                    .optJSONObject("result")
                    ?.optJSONArray("value")
                    ?.optJSONObject(0)

                if (statusValue == null) {
                    // Noch nicht propagiert
                    Log.d(TAG, "Attempt $attempt: TX $signature not found yet, waiting...")
                    delay(delayMs)
                    continue
                }

                // err prüfen — wenn die TX on-chain gefehlt hat
                val err = statusValue.opt("err")
                if (err != null && err != JSONObject.NULL) {
                    Log.e(TAG, "TX failed on-chain: $err for $signature")
                    return VerificationResult.TxFailedOnChain(signature, err.toString())
                }

                val confirmationStatus = statusValue.optString("confirmationStatus", "")
                Log.d(TAG, "Attempt $attempt: TX $signature status: $confirmationStatus")
                if (confirmationStatus == "confirmed" || confirmationStatus == "finalized") {
                    Log.d(TAG, "TX confirmed: $signature")
                    return null  // Confirmation OK, weitermachen mit Amount-Check
                }

                delay(delayMs)
            } catch (e: Exception) {
                Log.e(TAG, "Status check attempt $attempt failed", e)
                if (attempt == maxAttempts) {
                    return VerificationResult.RpcError(signature, "Status nach $maxAttempts Versuchen nicht abrufbar: ${e.message}")
                }
                delay(delayMs)
            }
        }
        return VerificationResult.TxNotFound(signature)
    }

    private suspend fun checkTransferAmount(signature: String): VerificationResult =
        checkTransferAmountAgainst(signature, setOf(treasuryAddress))

    /**
     * Same as checkTransferAmount but checks the transferred amount against a SET
     * of accepted treasury addresses (current + historical). Used by the restore
     * scan so users who paid to a previous treasury are still recognised.
     */
    private suspend fun checkTransferAmountAgainst(signature: String, acceptedTreasuries: Set<String>): VerificationResult {
        return try {
            val txJson = postRpc(JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "getTransaction")
                put("params", JSONArray().apply {
                    put(signature)
                    put(JSONObject().apply {
                        put("encoding", "json")
                        put("maxSupportedTransactionVersion", 0)
                        put("commitment", "confirmed")
                    })
                })
            })

            val result = txJson.optJSONObject("result")
                ?: run {
                    Log.e(TAG, "Transaction $signature NOT FOUND in getTransaction result")
                    return VerificationResult.TxNotFound(signature)
                }

            // accountKeys können in transaction.message.accountKeys ODER in
            // transaction.message.staticAccountKeys liegen, je nach TX-Version
            val transaction = result.optJSONObject("transaction")
            if (transaction == null) {
                Log.e(TAG, "Transaction object missing for $signature")
                return VerificationResult.RpcError(signature, "Transaction object missing")
            }
            val message = transaction.optJSONObject("message")
                ?: return VerificationResult.RpcError(signature, "Message-Objekt fehlt in TX")

            val accountKeys = message.optJSONArray("accountKeys")
                ?: message.optJSONArray("staticAccountKeys")
                ?: return VerificationResult.RpcError(signature, "AccountKeys fehlen in TX")

            var treasuryIndex = -1
            var matchedTreasury = ""
            for (i in 0 until accountKeys.length()) {
                val key = accountKeys.optString(i)
                if (key in acceptedTreasuries) {
                    treasuryIndex = i
                    matchedTreasury = key
                    break
                }
            }
            if (treasuryIndex < 0) {
                Log.e(TAG, "No accepted treasury found in TX-Accounts for $signature")
                return VerificationResult.WrongAmount(signature, 0, expectedLamports)
            }

            val meta = result.optJSONObject("meta")
                ?: return VerificationResult.RpcError(signature, "Meta-Objekt fehlt")
            
            val err = meta.opt("err")
            if (err != null && err != JSONObject.NULL) {
                Log.e(TAG, "TX $signature failed on-chain (meta.err): $err")
                return VerificationResult.TxFailedOnChain(signature, err.toString())
            }

            val pre = meta.optJSONArray("preBalances")
                ?: return VerificationResult.RpcError(signature, "preBalances fehlen")
            val post = meta.optJSONArray("postBalances")
                ?: return VerificationResult.RpcError(signature, "postBalances fehlen")

            if (treasuryIndex >= pre.length() || treasuryIndex >= post.length()) {
                Log.e(TAG, "Index mismatch for treasury index $treasuryIndex in balances")
                return VerificationResult.RpcError(signature, "Index-Mismatch in Balances")
            }

            val transferred = post.getLong(treasuryIndex) - pre.getLong(treasuryIndex)
            Log.d(TAG, "Signature: $signature  matched treasury: $matchedTreasury")
            Log.d(TAG, "Pre-balance: ${pre.getLong(treasuryIndex)}  Post-balance: ${post.getLong(treasuryIndex)}")
            Log.d(TAG, "Transferred: $transferred (expected >= $expectedLamports)")

            if (transferred >= expectedLamports) {
                Log.i(TAG, "✓ Payment VERIFIED for $signature")
                VerificationResult.Verified(signature, transferred)
            } else {
                Log.e(TAG, "✗ Payment AMOUNT MISMATCH for $signature: transferred $transferred, expected $expectedLamports")
                VerificationResult.WrongAmount(signature, transferred, expectedLamports)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Amount check failed", e)
            VerificationResult.RpcError(signature, e.message ?: "Unknown")
        }
    }

    /**
     * Scans the TREASURY wallet's transaction history for payments FROM the
     * given user wallet. This is much faster than scanning the user's wallet
     * because the treasury only contains license payments.
     *
     * Also checks all ACCEPTED_TREASURIES for historical treasury migrations.
     */
    suspend fun scanForExistingPayment(walletAddress: String): String? = coroutineScope {
        try {
            val started = System.currentTimeMillis()
            Log.i(TAG, "Scanning treasury wallets for payments from $walletAddress (need >=$expectedLamports lamports)")

            for (treasury in SolanaConfig.ACCEPTED_TREASURIES) {
                val result = scanTreasuryForUser(treasury, walletAddress)
                if (result != null) {
                    val elapsed = System.currentTimeMillis() - started
                    Log.i(TAG, "✓ Payment found in ${elapsed}ms from treasury $treasury: $result")
                    return@coroutineScope result
                }
            }
            val elapsed = System.currentTimeMillis() - started
            Log.w(TAG, "No payment found across ${SolanaConfig.ACCEPTED_TREASURIES.size} treasuries after ${elapsed}ms")
            null
        } catch (e: Exception) {
            Log.e(TAG, "scanForExistingPayment failed", e)
            null
        }
    }

    private suspend fun scanTreasuryForUser(treasury: String, walletAddress: String): String? = coroutineScope {
        try {
            val response = postRpc(JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "getSignaturesForAddress")
                put("params", JSONArray().apply {
                    put(treasury)
                    put(JSONObject().apply { put("limit", 1000) })
                })
            })
            val sigArray = response.optJSONArray("result") ?: return@coroutineScope null

            val signatures = (0 until sigArray.length()).mapNotNull { i ->
                val obj = sigArray.optJSONObject(i) ?: return@mapNotNull null
                val err = obj.opt("err")
                if (err != null && err != JSONObject.NULL) return@mapNotNull null
                obj.optString("signature").takeIf { it.isNotEmpty() }
            }

            Log.i(TAG, "Treasury $treasury: ${signatures.size} candidates, checking for user $walletAddress…")

            val chunkSize = 20
            for (chunk in signatures.chunked(chunkSize)) {
                val results = chunk.map { sig ->
                    async { sig to checkTxForUserPayment(sig, walletAddress, treasury) }
                }.awaitAll()
                val match = results.firstOrNull { it.second == true }
                if (match != null) return@coroutineScope match.first
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "scanTreasuryForUser failed for $treasury", e)
            null
        }
    }

    private suspend fun checkTxForUserPayment(signature: String, walletAddress: String, treasury: String): Boolean {
        return try {
            val txJson = postRpc(JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "getTransaction")
                put("params", JSONArray().apply {
                    put(signature)
                    put(JSONObject().apply {
                        put("encoding", "json")
                        put("maxSupportedTransactionVersion", 0)
                        put("commitment", "confirmed")
                    })
                })
            })

            val result = txJson.optJSONObject("result") ?: return false
            val transaction = result.optJSONObject("transaction") ?: return false
            val message = transaction.optJSONObject("message") ?: return false
            val accountKeys = message.optJSONArray("accountKeys")
                ?: message.optJSONArray("staticAccountKeys")
                ?: return false

            val accounts = (0 until accountKeys.length()).map { accountKeys.optString(it) }
            if (walletAddress !in accounts) return false

            val meta = result.optJSONObject("meta") ?: return false
            val err = meta.opt("err")
            if (err != null && err != JSONObject.NULL) return false

            val treasuryIndex = accounts.indexOf(treasury)
            if (treasuryIndex < 0) return false

            val pre = meta.optJSONArray("preBalances") ?: return false
            val post = meta.optJSONArray("postBalances") ?: return false
            if (treasuryIndex >= pre.length() || treasuryIndex >= post.length()) return false

            val transferred = post.getLong(treasuryIndex) - pre.getLong(treasuryIndex)
            if (transferred >= expectedLamports) {
                Log.i(TAG, "✓ Found payment from $walletAddress: $signature ($transferred lamports)")
                true
            } else false
        } catch (e: Exception) {
            Log.e(TAG, "checkTxForUserPayment failed for $signature", e)
            false
        }
    }

    private suspend fun postRpc(json: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(rpcUrl)
            .post(json.toString().toRequestBody(mediaType))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("RPC HTTP ${response.code}")
            JSONObject(response.body?.string() ?: "{}")
        }
    }

    companion object {
        private const val TAG = "PaymentVerifier"
    }
}
