These drives link together based on the frequency set for each drive. All drives with the same frequency and privacy share the same contents, regardless of who crafted them. Whether in your AE2 system, another dimension, or on another player’s system, the contents are globally synchronized.

***

## EnderDrives

**EnderDrives** is a mod that offers a way to store your items **in the End — but digitally**. Each drive has a type limit based on its capacity.  
This is an artificial limit for gameplay mechanics, not due to storage limitations, but it is heavily suggested to try and keep the least amount of types in the system as possible.  
The system has been reliably tested up to **250,000 records**.  Exceed that with caution.

***

## Scope / Privacy Options

**Global:**  
Items stored under this scope are shared server-wide. Any player with a drive set to the same frequency can access the same shared inventory.

**Private:**  
Drives in private mode are tied to your UUID and only you can assign them to your private space. Other players can use the drive through your ME system, but the inventory is yours alone.

**Team:**  
Team drives are accessible by all members of your **FTB Team**. Any member can craft a drive and set it to a team frequency. Internally, the data is tied to the party owner’s UUID, so even if the team disbands, the original owner still has access.

***

## Powered by EnderDB

To handle storage at such large scales, EnderDrives uses a purpose-built database called **EnderDB**. All items are stored with their full data component (NBT), ensuring perfect accuracy in identifying and tracking items.  
Each stored item is indexed by its **frequency**, **scope**, and **full NBT**, along with a **count**. The count is stored as a signed 64-bit long, allowing up to **9 quintillion items** in a single record.

***

## EnderDB Features

The goal of EnderDB is **speed**, **reliability**, and **efficiency**. EnderDB uses:

*   **Rotating Write-Ahead Logging (WAL)** for data integrity
*   **Batch Commits** for performance
*   **CRC32 Checksums** for data integrity
*   **Compact Binary Storage** for performance
*   **In-Memory Caching** for performance
*   **Dedicated Background Threading**
*   **Safe Handling of Shutdowns and Crashes**

All operations are **atomic**, ensuring that **no data is ever lost or corrupted**, even in the event of world corruption or server failure.

***

**Data is saved in your world folder:**  
`saves/world/data/enderdrives/enderdrives.bin`  
Write-ahead log (WAL) files are stored alongside this file for recovery and performance.
