package com.norypt.haven.security

/**
 * Explicit lock/unlock states of the Haven session. The app's VaultSession owns the database
 * handles; this type is the single source of truth for what is currently open.
 */
public sealed interface SessionState {
    /** Nothing open. The alarm runtime keeps working from its Direct Boot store. */
    public data object Locked : SessionState

    /** Password entered, Argon2id running. UI must block further input. */
    public data class Unlocking(public val vault: String) : SessionState

    /** Content vault (reminders/tasks) open; password vault possibly open too. */
    public data class Unlocked(public val contentOpen: Boolean, public val passwordsOpen: Boolean) : SessionState

    /** A backup export or restore is running under fresh authentication; locking is deferred until it ends. */
    public data class BackupOperation(public val restore: Boolean) : SessionState

    /** Connections closing, buffers wiping. */
    public data object Locking : SessionState

    /** Hardware key gone / envelope unreadable. Recovery is only from a backup. */
    public data class UnrecoverableKeyError(public val reason: String) : SessionState

    public val isAnyVaultOpen: Boolean
        get() = this is Unlocked || this is BackupOperation
}
