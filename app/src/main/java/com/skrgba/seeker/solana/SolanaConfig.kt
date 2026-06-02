package com.skrgba.seeker.solana

object SolanaConfig {
    const val MOCK_MODE        = false // ECHTE ZAHLUNGEN AKTIVIERT FÜR RELEASE
    const val TREASURY_WALLET = "CsdUs6JfvEV8kdKbLE8xhZdLaA5RkddEWE1E2N71jrUr"

    /** Only the current treasury is accepted as proof of license. */
    val ACCEPTED_TREASURIES = setOf(TREASURY_WALLET)

    // Wallets die automatisch lizenziert sind (Treasury-Wallet des Developers)
    val WHITELISTED_WALLETS = setOf(TREASURY_WALLET)

    fun isWhitelisted(walletAddress: String) = walletAddress in WHITELISTED_WALLETS
    
    // Die Program ID deines deployten Anchor-Programms
    const val PROGRAM_ID      = "Fg6PaFpoGXkYsidMpWTK6W2BeZ7FEfcYkg476zPFsLnS"
    
    // Sobald du den SBT Mint hast, hier eintragen
    const val SBT_MINT        = "" 
    
    const val CLUSTER           = "mainnet-beta"
    val RPC_URL: String         get() = com.skrgba.seeker.BuildConfig.HELIUS_RPC_URL
    const val LICENSE_PRICE   = 50_000_000L  // 0.05 SOL
    const val CHEAT_PRICE     = 100_000_000L // 0.1 SOL
    const val LICENSE_SEED    = "license"
}
