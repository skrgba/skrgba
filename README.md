<div align="center">

```
   ███████╗██╗  ██╗██████╗  ██████╗ ██████╗  █████╗ 
   ██╔════╝██║ ██╔╝██╔══██╗██╔════╝ ██╔══██╗██╔══██╗
   ███████╗█████╔╝ ██████╔╝██║  ███╗██████╔╝███████║
   ╚════██║██╔═██╗ ██╔══██╗██║   ██║██╔══██╗██╔══██║
   ███████║██║  ██╗██║  ██║╚██████╔╝██████╔╝██║  ██║
   ╚══════╝╚═╝  ╚═╝╚═╝  ╚═╝ ╚═════╝ ╚═════╝ ╚═╝  ╚═╝
```

### 🎮 GBA Emulator · Native Solana dApp

*A Game Boy Advance emulator with on-chain licensing — pay once, play forever.*

<br/>

[![Platform](https://img.shields.io/badge/Platform-Solana%20Seeker-9945FF?style=for-the-badge&logo=solana&logoColor=white)](https://solanamobile.com/seeker)
[![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-3DDC84?style=for-the-badge&logo=android&logoColor=white)](#)
[![Architecture](https://img.shields.io/badge/Arch-ARM64%20%7C%20ARMv7%20%7C%20x86__64-0A0A0A?style=for-the-badge)](#)
[![Engine](https://img.shields.io/badge/Engine-mGBA%20libretro-FF6B35?style=for-the-badge)](https://mgba.io/)

</div>

---

## Overview

**SKRGBA** is a Game Boy Advance emulator for Android with native Solana blockchain integration. Access is gated by a one-time on-chain license payment — no subscriptions, no accounts, no central server. Your license lives on the blockchain and is automatically restored whenever you reconnect the same wallet.

---

## 💰 Licensing

| Feature | Price | Details |
| :--- | :--- | :--- |
| **App License** | 0.05 SOL | Unlocks core emulator access — paid once |
| **Anti-Cheat Fee** | 0.1 SOL | Optional shame fee to unlock cheat code entry |

Both payments are tied to your Solana wallet address. If you reinstall the app, they are restored automatically when you reconnect the same wallet — no repurchase needed. All transactions are verified on-chain before access is granted.

The Anti-Cheat Fee deliberately costs more than the App License — we believe games are meant to be earned, not bypassed.

---

## 🛠 Technical Specifications

### Blockchain Integration

| Feature | Implementation |
| :--- | :--- |
| **Wallet Support** | Any MWA-compatible Solana wallet (Solana Seeker Seed Vault recommended) |
| **Wallet Adapter** | Native Mobile Wallet Adapter (MWA) |
| **Licensing** | On-chain SOL transfer · verified via RPC |
| **License Recovery** | Automatic — wallet transaction history scan on reconnect |

- **On-Chain Licensing** — License is verified by checking that a real SOL transfer to the treasury wallet occurred on-chain. No license server. No central authority.
- **License Recovery** — The last 50 transactions of your wallet are scanned on reconnect. If a prior payment is found, the license is restored automatically without repaying.

### Emulation & Performance

| Component | Implementation |
| :--- | :--- |
| **Emulation Core** | mGBA libretro |
| **Target Chipset** | ARM64-V8A · ARMv7 · x86_64 |
| **Graphics** | OpenGL ES + custom GLSL shaders |
| **Haptics** | Direct emulator-input mapping |

- **Engine** — Powered by the [mGBA](https://mgba.io/) libretro core, optimized for ARM64-V8A.
- **Graphics** — OpenGL ES hardware acceleration with custom GLSL shaders for accurate pixel scaling on high-density displays.
- **Haptics** — GBA button inputs are mapped to haptic feedback for tactile response.

### Progress & Persistence

- **Persistent Storage** — Save states and in-game SRAM are stored in the user's `Documents` directory.
- **Survives Reinstall** — Game saves are written to `Documents`, so progress persists across app uninstalls and reinstalls.

---

## 📲 Installation & Usage

### Requirements

| | |
| :--- | :--- |
| **Hardware** | Solana Seeker *(recommended)* or any Android device with a MWA wallet |
| **OS** | Android 8.0+ (API 26+) |
| **Architecture** | ARM64-V8A · ARMv7 · x86_64 |
| **Wallet** | Solana wallet with ≥ 0.05 SOL |

### Steps

```text
1.  Install    →   Available on the Solana Seeker dApp Store
2.  Connect    →   Tap "Connect Wallet" — approve via your Solana wallet
3.  License    →   Pay 0.05 SOL once to unlock full emulator access
4.  Load ROM   →   Import a GBA ROM from your device storage
5.  Play       →   Your license is saved on-chain and auto-restored on reinstall
```

> [!TIP]
> The 0.05 SOL license fee is paid once and never again — even across reinstalls.  
> Tap the 🔒 cheat button any time to unlock cheat code entry for an additional 0.1 SOL.

---

## ⚖️ Legal

SKRGBA is an **emulator framework**. It does not include, distribute, or endorse any game files (ROMs) or BIOS images. Users are solely responsible for providing their own legally obtained software.

- [Privacy Policy](./privacy.html)
- [Terms of Use](./terms.html)

---

<div align="center">

**© 2026 SKRGBA** · [skrgba.xyz](https://skrgba.xyz/) · Powered by Solana

*Pay once. Play forever.*

</div>
